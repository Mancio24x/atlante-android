package app.atlante.library;

import app.atlante.library.storage.BackupArchive;
import app.atlante.library.model.Book;
import app.atlante.library.ui.BookAdapter;
import app.atlante.library.model.BookMatcher;
import app.atlante.library.sync.CalibreSync;
import app.atlante.library.storage.CatalogImport;
import app.atlante.library.ui.Covers;
import app.atlante.library.ebook.EbookParser;
import app.atlante.library.sync.HardcoverSync;
import app.atlante.library.sync.KindleClippings;
import app.atlante.library.sync.KoreaderSync;
import app.atlante.library.model.LibraryQuery;
import app.atlante.library.storage.LibraryStore;
import app.atlante.library.online.OnlineCatalog;
import app.atlante.library.model.ReadingJournal;
import app.atlante.library.ui.ReadingMap;
import app.atlante.library.online.SbnCatalog;
import app.atlante.library.online.SeriesCatalog;
import app.atlante.library.sync.SyncCore;
import app.atlante.library.ui.ThemePalette;

import org.json.JSONObject;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** Android edition: Material 3 components, private storage and the system document picker. */
public final class MainActivity extends AppCompatActivity {
    public int INK, PAPER, WHITE, SAGE, COPPER, GOLD, MUTED, LINE, ON_ACCENT;
    private ThemePalette palette = ThemePalette.ALL[0];
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable filterTask;
    private final ExecutorService catalogWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService coverWorker = Executors.newSingleThreadExecutor();
    private final Covers covers = new Covers();
    private FrameLayout body;
    private RecyclerView grid;
    private BookAdapter adapter;
    private String gridKey;
    private static final int IMPORT = 101, EXPORT = 102, COVER = 103, BACKUP = 104, RESTORE = 105, CLIPPINGS = 106;
    private boolean backupFiles = true;
    /** EPUBs whose table of contents was already read in this session (found or not). */
    private final Set<String> tocChecked = new HashSet<>();
    private final Runnable savePosition = () -> { if (this.store != null) save(); };
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<Intent> importLauncher = results(IMPORT), exportLauncher = results(EXPORT),
        coverLauncher = results(COVER), backupLauncher = results(BACKUP), restoreLauncher = results(RESTORE), clippingsLauncher = results(CLIPPINGS);
    /** Enabled only when Back has something to close inside the app, so the system can animate leaving it. */
    private final OnBackPressedCallback back = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() { goBack(); }
    };

    private ActivityResultLauncher<Intent> results(int request) {
        return registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result -> handleResult(request, result.getResultCode(), result.getData()));
    }
    private LibraryStore store;
    private LinearLayout root, content;
    private FrameLayout screen, drawer;
    private int insetTop, insetBottom;
    private String lastOnlineQuery = "";
    private String destination = "Home", detailId;
    private final Map<String, android.os.Parcelable> libraryPositions = new HashMap<>();
    private final Map<String, Integer> pagePositions = new HashMap<>();
    private ScrollView companionScroll;
    private boolean shelfView, editingPage;
    private final Map<String,String> destinationQueries = new HashMap<>();
    private app.atlante.library.ui.compose.CatalogStripView.Handle catalogStrip;
    private String timerBook;
    private long timerAccumulated, timerMark, timerBoot, timerWall;
    private int timerFrom;
    private boolean timerRunning;
    private TextView timerLabel;
    private final Runnable timerTick = new Runnable() { public void run() {
        if (timerLabel != null) timerLabel.setText(elapsedLabel(timerSeconds()));
        uiHandler.postDelayed(this, 1000);
    }};
    private String tab = "Tutti i libri", query = "", mapAuthor = "", exportId;
    private String filterGenre = "", filterFormat = "", filterSaga = "", sortOrder = "Titolo", filterAuthor = "", filterLanguage = "", filterDecade = "", filterUniverse = "";
    /** Library tabs shown as chips; each one but the first is a reading state. */
    private static final String[] STATUS_TABS = {"Tutti i libri", "Da leggere", "In lettura", "In pausa", "Letti", "Preferiti"};
    private String coverBookId;
    /** Book chosen with «Invia a CrossPoint» on its page, offered on the CrossPoint page until sent or dropped. */
    private String crossPointSend;
    private Book reading;
    private List<String> pages;
    private int page;
    private float fontSize = 20;
    private boolean readerDark, manga, busy, listView;
    private TextView notice;

    @Override public void onCreate(Bundle state) {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        // The Material theme must be set before the activity creates its views.
        applyTheme(prefs.getString("theme", DynamicColors.isDynamicColorAvailable() ? "dynamic" : "atlante"));
        super.onCreate(state);
        app.atlante.library.ui.L.init(this); app.atlante.library.ui.L.watch(getWindow().getDecorView());
        app.atlante.library.diagnostics.CrashLog.install(this);   // also when the app was started without AtlanteApp (tests)
        if (state == null) { reportCrashesSinceLastTime(); askReportConsent(); }
        getOnBackPressedDispatcher().addCallback(this, back);
        if (state != null) {
            tab = state.getString("tab", tab); if (tab.equals("Desideri")) tab = "Tutti i libri"; query = state.getString("query", "");
            mapAuthor = state.getString("author", ""); exportId = state.getString("export"); coverBookId = state.getString("coverBook");
            backupFiles = state.getBoolean("backupFiles", true);
        }
        filterGenre = prefs.getString("filterGenre", "");
        filterFormat = prefs.getString("filterFormat", ""); filterSaga = prefs.getString("filterSaga", ""); sortOrder = prefs.getString("sortOrder", "Titolo");
        filterAuthor = prefs.getString("filterAuthor", ""); filterLanguage = prefs.getString("filterLanguage", ""); filterDecade = prefs.getString("filterDecade", ""); filterUniverse = prefs.getString("filterUniverse", "");
        listView = prefs.getBoolean("listView", false);
        OnlineCatalog.configure(new File(getCacheDir(), "catalog"));
        // Temporary data: expired suggestions and covers over the limit go away; sagas refresh in the background.
        app.atlante.library.saga.CacheManager.cleanupAsync(this);
        app.atlante.library.saga.Hardcover.configure(this);
        app.atlante.library.online.CoverSearch.setPrefer(getPreferences(MODE_PRIVATE).getString("coverPrefer", "italian"));
        app.atlante.library.saga.SagaRepository.configureCatalog(this);
        try { app.atlante.library.saga.SagaRefreshWorker.schedule(this); app.atlante.library.saga.SagaScanWorker.start(this); }
        catch (RuntimeException ignored) { /* WorkManager unavailable: sagas are recognized when a book is opened. */ }
        fontSize = Math.max(8, Math.min(80, prefs.getFloat("fontSize", 20)));
        readerDark = prefs.getBoolean("dark", false); manga = prefs.getBoolean("manga", false);
        try { store = LibraryStore.shared(this); }
        catch (Exception e) {
            dialogBuilder().setTitle("Libreria non disponibile")
                .setMessage("I dati salvati non sono stati modificati. " + message(e))
                .setCancelable(false).setPositiveButton("Chiudi", (d, w) -> finish()).show();
            return;
        }
        // The app always opens on Home. Only a restart made by the app itself (a theme change) keeps the page.
        boolean keepPage = state != null && prefs.getBoolean("keepPageOnce", false);
        // A theme applied from a settings page reloads the app: come back to that page (Aspetto), not the index.
        if (keepPage) settingsSection = prefs.getString("settingsSectionOnce", "");
        prefs.edit().remove("keepPageOnce").remove("settingsSectionOnce").apply();
        destination = keepPage ? state.getString("destination", "Home") : "Home";
        if (destination.equals("Cerca")) destination = "Biblioteca"; // "Cerca" was replaced by Sync: the library has the search bar
        shelfView = prefs.getBoolean("shelfView", false);
        restoreTimer(); showLibrary();
        if (state == null && isWidgetIntent(getIntent())) widgetIntent = getIntent();
        if (state == null && app.atlante.library.sync.HardcoverAuth.isCallback(getIntent().getData())) finishHardcoverLogin(getIntent().getData());
        if (keepPage && state.getString("detail") != null) { Book b = store.find(state.getString("detail")); if (b != null) details(b); }
        if (keepPage && state.getString("reading") != null) {
            Book b = store.find(state.getString("reading")); if (b != null) openReader(b);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("coverBook", coverBookId); state.putBoolean("backupFiles", backupFiles);
        state.putString("destination", destination); state.putString("detail", detailId);
        state.putString("tab", tab); state.putString("query", query);
        state.putString("author", mapAuthor); state.putString("export", exportId);
        if (reading != null) state.putString("reading", reading.id);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (store == null) return;
        // Editing pages (timer, progress, review, notes) only relayout: rebuilding them would lose what you were
        // doing and send the timer back to the book page.
        if (editingPage) return;
        if (reading != null && pages != null) showReader(); else if (detailId != null && liveBook(detailId) != null) details(liveBook(detailId)); else showLibrary();
    }

    private void openDrawer() {
        if (drawer != null) { closeDrawer(); return; }
        drawer = new FrameLayout(this); FrameLayout overlay = drawer;
        View scrim = new View(this); scrim.setBackgroundColor(0x99000000);
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1,-1)); scrim.setOnClickListener(v -> closeDrawer());
        int width = Math.min(dp(320), Math.round(getResources().getDisplayMetrics().widthPixels * .86f));
        ScrollView scroll = new ScrollView(this);
        GradientDrawable sheet = new GradientDrawable(); sheet.setColor(WHITE);
        sheet.setCornerRadii(new float[]{0, 0, dp(28), dp(28), dp(28), dp(28), 0, 0}); scroll.setBackground(sheet); scroll.setClipToOutline(true);
        overlay.addView(scroll, new FrameLayout.LayoutParams(width, -1, Gravity.START));
        LinearLayout menu = column(); menu.setPadding(dp(12), Math.max(dp(12), insetTop), dp(12), Math.max(dp(24), insetBottom)); scroll.addView(menu);
        LinearLayout top = row(); menu.addView(top);
        Button close = iconButton(R.drawable.ic_menu, "Chiudi menu laterale", this::closeDrawer); top.addView(close);
        top.addView(text("Atlante", 24, INK, true), new LinearLayout.LayoutParams(0, -2, 1)); gap(menu, 12);
        menu.addView(sectionLabel("  BIBLIOTECA")); gap(menu, 4);
        boolean libraryTab = Arrays.asList(STATUS_TABS).contains(tab);
        List<Book> all = store.snapshot(); Set<String> sagaNames = new HashSet<>(); for (Book b : all) if (!b.series.isEmpty()) sagaNames.add(b.series.toLowerCase(Locale.ROOT));
        int authorCount = app.atlante.library.ui.MapGroups.authorNames(all).size();
        Set<String> universeNames = new HashSet<>(); for (Book b : all) { String u = LibraryQuery.graphUniverses.get(b.id); if (u != null && !u.isEmpty()) universeNames.add(u.toLowerCase(Locale.ROOT)); }
        String[][] places = {{"Tutti i libri", Integer.toString(all.size())}, {"Saghe", Integer.toString(sagaNames.size())}, {"Mappa", authorCount + (authorCount == 1 ? " autore" : " autori")}, {"Universi", universeNames.isEmpty() ? "" : Integer.toString(universeNames.size())}};
        int[] icons = {R.drawable.ic_library, R.drawable.ic_list, R.drawable.ic_map, R.drawable.ic_public};
        for (int i = 0; i < places.length; i++) {
            String name = places[i][0]; boolean selected = name.equals("Tutti i libri") ? libraryTab && destination.equals("Biblioteca") : tab.equals(name) && destination.equals("Biblioteca");
            menu.addView(drawerPlace(icons[i], name, places[i][1], selected, () -> { closeDrawer(); destination = "Biblioteca"; tab = name; openSaga = ""; showLibrary(); }));
        }
        gap(menu, 8); rule(menu); gap(menu, 8);
        menu.addView(drawerTool(R.drawable.ic_collections, "Collezioni", false, () -> { closeDrawer(); collections(); }));
        menu.addView(drawerTool(R.drawable.ic_search, "Cerca libri online", false, () -> { closeDrawer(); onlineSearch(); }));
        menu.addView(drawerTool(R.drawable.ic_book, "CrossPoint", destination.equals("CrossPoint"), () -> { closeDrawer(); navigate("CrossPoint"); }));
        // Backups stay reachable (they are the only copy of notes and progress outside the phone), outside the settings.
        View catalog = drawerTool(R.drawable.ic_cloud, "Backup", false, () -> { closeDrawer(); backupMenu(); }); catalog.setEnabled(!busy); catalog.setAlpha(busy ? .4f : 1f); menu.addView(catalog);
        menu.addView(drawerTool(R.drawable.ic_settings, "Impostazioni", false, () -> { closeDrawer(); settings(); }));
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        screen.addView(overlay,new FrameLayout.LayoutParams(-1,-1)); close.requestFocus();
        scroll.setTranslationX(-width); scroll.animate().translationX(0).setDuration(220).start();
        updateBack();
    }
    /** Drawer row of a library place (design 7a): icon, name, count on the right; a filled pill when selected. */
    private View drawerPlace(int icon, String label, String count, boolean selected, Runnable action) {
        LinearLayout item = row(); item.setPadding(dp(16), 0, dp(20), 0); item.setMinimumHeight(dp(56));
        GradientDrawable g = background(selected ? LINE : Color.TRANSPARENT, Color.TRANSPARENT); g.setCornerRadius(dp(28)); item.setBackground(g);
        ImageView i = new ImageView(this); i.setImageResource(icon); i.setColorFilter(INK); item.addView(i, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = text(label, 15, INK, false); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); t.setPadding(dp(14), 0, dp(8), 0); item.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (!count.isEmpty()) item.addView(text(count, 13, MUTED, false));
        item.setContentDescription(label + (count.isEmpty() ? "" : ", " + count) + (selected ? ", selezionato" : "")); item.setOnClickListener(v -> action.run());
        item.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(56)));
        return item;
    }
    /** Design 7a, the tools under the places: a muted icon, the label, a slightly lower row. */
    private View drawerTool(int icon, String label, boolean selected, Runnable action) {
        View item = drawerPlace(icon, label, "", selected, action);
        ((ImageView) ((LinearLayout) item).getChildAt(0)).setColorFilter(MUTED);
        item.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(52)));
        return item;
    }
    /** Navigation drawer row: a full-width pill, filled with the secondary container when selected. */
    private Button drawerItem(String label, boolean selected, Runnable action) {
        MaterialButton item = button(label, selected ? R.style.Atlante_Button_Tonal : R.style.Atlante_Button_Text, action);
        item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); item.setMinHeight(dp(56)); item.setTextSize(15);
        item.setTextColor(INK); item.setPadding(dp(20), 0, dp(20), 0);
        return item;
    }
    private void closeDrawer() {
        if (drawer == null) return;
        FrameLayout closing = drawer; drawer = null; updateBack();
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        closing.animate().alpha(0).setDuration(140).withEndAction(() -> {
            if (closing.getParent() instanceof ViewGroup) ((ViewGroup)closing.getParent()).removeView(closing);
        }).start();
    }
    private void settings() { settingsSection=""; if(!destination.equals("Impostazioni"))getPreferences(MODE_PRIVATE).edit().putString("settingsReturn",destination).apply(); navigate("Impostazioni"); }

    /** Impostazioni (design 6a): an index of seven rows with the current value; each opens its own page. */
    private String settingsSection = "";
    private static final String[][] SETTINGS = {
        {"PERSONALE","aspetto","Aspetto","tema colori scuro chiaro"},{"PERSONALE","obiettivi","Obiettivi di lettura","rotta libri pagine minuti"},
        {"PERSONALE","lettura","Lettura","lettore testo dimensione scuro"},{"PERSONALE","lingua","Lingua","lingua app interfaccia inglese english italiano ricerca libri"},
        {"BIBLIOTECA","libreria","Libreria e catalogo","importa ebook epub copertine cache"},
        {"BIBLIOTECA","ripara","Ripara libreria","ripara duplicati doppioni saghe mappe metadati copertine generi hardcover qualità operazioni background"},{"BIBLIOTECA","hardcover","Hardcover","chiave saghe universi"},
        {"INFO","info","Privacy e progetto","privacy github aggiornamenti versione"}};
    private String settingsTitle(String key) { if (key.equals("tema")) return "Il mio tema"; for (String[] r : SETTINGS) if (r[1].equals(key)) return r[2]; return "Impostazioni"; }
    private String settingsValue(String key) {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        switch (key) {
            case "aspetto": return palette.id.equals("custom") ? "Il mio tema" : "Tema " + palette.name;
            case "obiettivi": { app.atlante.library.model.ReadingGoals g = new app.atlante.library.model.ReadingGoals(prefs); ArrayList<String> v = new ArrayList<>();
                if (g.getYearOn()) v.add(g.getYearBooks() + " libri l’anno"); if (g.getMonthOn()) v.add(g.getMonthBooks() + " al mese"); if (g.getDailyPagesOn()) v.add(g.getDailyPages() + " pagine al giorno"); if (g.getDailyMinutesOn()) v.add(g.getDailyMinutes() + " minuti al giorno");
                return v.isEmpty() ? "Nessun obiettivo" : TextUtils.join(" · ", v); }
            case "lettura": return (readerDark ? "Lettore scuro" : "Lettore chiaro") + " · testo " + Math.round(fontSize);
            case "lingua": return (app.atlante.library.ui.L.getEnglish() ? "English" : "Italiano") + " · ricerca libri: " + OnlineCatalog.languageName(prefs.getString("bookLanguage", app.atlante.library.ui.L.getEnglish() ? "eng" : "ita")) + (prefs.getBoolean("onlyLanguage", false) ? " · solo questa" : "");
            case "libreria": return "Importa eBook, copertine, cache";
            case "ripara": return "Duplicati, saghe, mappe, copertine, generi";
            case "hardcover": return app.atlante.library.sync.HardcoverAuth.connected(this) ? "Collegato · saghe, numeri e universi" : "Non collegato";
            default: return "Cosa resta sul telefono · aggiornamenti";
        }
    }
    private int settingsIcon(String key) {
        switch (key) { case "aspetto": return R.drawable.ic_palette; case "obiettivi": return R.drawable.ic_flag; case "lettura": return R.drawable.ic_book; case "lingua": return R.drawable.ic_translate;
            case "libreria": return R.drawable.ic_library_check; case "ripara": return R.drawable.ic_build; case "hardcover": return R.drawable.ic_link; default: return R.drawable.ic_info; }
    }
    private void settingsPage(LinearLayout panel) {
        if (settingsSection.isEmpty()) { panel.addView(settingsCompose(""), new LinearLayout.LayoutParams(-1, -2)); return; }
        boolean inTheme = settingsSection.equals("tema");
        TextView back = text(inTheme ? "‹ Aspetto" : "‹ Tutte le impostazioni", 14, SAGE, false); back.setMinHeight(dp(44)); back.setGravity(Gravity.CENTER_VERTICAL); back.setPadding(dp(4), 0, dp(4), 0);
        back.setContentDescription(inTheme ? "Torna ad Aspetto" : "Torna a tutte le impostazioni"); back.setOnClickListener(v -> goBack()); pill(back);
        panel.addView(back, new LinearLayout.LayoutParams(-2, -2)); gap(panel, 12);
        switch (settingsSection) {
            case "aspetto": customThemeCircle(panel); gap(panel,16);
        sectionHeader(panel, "Temi pronti", "", null); panel.addView(hint("Tocca un tema: si applica subito a tutta l’app."));
        LinearLayout themes = column(); themeTiles(themes);
        panel.addView(themes); gap(panel,20);

        appearanceMode(panel); gap(panel,20);
                break;
            case "tema": customThemePage(panel); break;
            case "obiettivi":
        panel.addView(app.atlante.library.ui.compose.GoalsView.create(this, palette, getPreferences(MODE_PRIVATE), store.snapshot())); gap(panel,20);

                break;
            case "lettura": case "lingua": panel.addView(settingsCompose(settingsSection), new LinearLayout.LayoutParams(-1, -2)); break;
            case "libreria": {
        MaterialButton importBooks = (MaterialButton) button("Importa eBook", true, this::chooseBooks); importBooks.setIconResource(R.drawable.ic_add); importBooks.setEnabled(!busy);
        panel.addView(importBooks, new LinearLayout.LayoutParams(-1,-2));
        panel.addView(text("EPUB, TXT, CBZ e AZW3 senza DRM, anche più file insieme. Prima di aggiungerli vedi titolo, autore e copertina e puoi correggerli; se il libro è già nella libreria puoi collegare il file alla sua scheda.",13,MUTED,false)); gap(panel,12);
        coverPreference(panel); gap(panel,12);
        panel.addView(button("Ripara libreria ›",false,() -> { settingsSection = "ripara"; showCompanion(); }));
        panel.addView(text("Duplicati, saghe, mappe, metadati, copertine e generi: ora sono tutti in Impostazioni › Ripara libreria.",13,MUTED,false)); gap(panel,12);
        panel.addView(expander("Cache e dati temporanei", app.atlante.library.ui.compose.CacheSettingsView.create(this, palette), false)); gap(panel,20);

                break; }
            case "ripara": panel.addView(app.atlante.library.ui.compose.RepairLibraryView.create(this, palette, repairHost), new LinearLayout.LayoutParams(-1, -2)); break;
            case "hardcover": hardcoverSection(panel); break;
            default: panel.addView(settingsCompose("info"), new LinearLayout.LayoutParams(-1, -2));
                diagnosticsSection(panel);
                break;
        }
    }
    private TextView hint(String value) { TextView t = text(value, 13, MUTED, false); t.setPadding(dp(4), 0, dp(4), dp(14)); return t; }
    /** The Compose part of the settings (design 6a): the index and the Lettura, Lingua and Privacy pages. */
    private View settingsCompose(String page) {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        ArrayList<app.atlante.library.ui.compose.SettingsRow> rows = new ArrayList<>();
        for (String[] r : SETTINGS) rows.add(new app.atlante.library.ui.compose.SettingsRow(r[0], r[1], r[2], settingsValue(r[1]), settingsIcon(r[1]), r[3], r[1].equals("ripara") ? prefs.getInt("qualityProblems", 0) : 0));
        ArrayList<String> languages = new ArrayList<>(); int chosen = 0; String code = prefs.getString("bookLanguage", app.atlante.library.ui.L.getEnglish() ? "eng" : "ita");
        for (int i = 0; i < OnlineCatalog.LANGUAGES.length; i++) { languages.add(OnlineCatalog.LANGUAGES[i][2]); if (OnlineCatalog.LANGUAGES[i][0].equals(code)) chosen = i; }
        app.atlante.library.ui.compose.SettingsState state = new app.atlante.library.ui.compose.SettingsState(rows, readerDark, fontSize, languages, chosen, prefs.getBoolean("onlyLanguage", false),
            Arrays.asList("", "it", "en").indexOf(app.atlante.library.ui.L.getChoice()));
        return app.atlante.library.ui.compose.SettingsView.create(this, palette, page, state, new app.atlante.library.ui.compose.SettingsHost() {
            @Override public void open(String key) { settingsSection = key; showCompanion(); if (companionScroll != null) companionScroll.post(() -> { if (companionScroll != null) companionScroll.scrollTo(0, 0); }); }
            @Override public void setReaderDark(boolean dark) { readerDark = dark; prefs.edit().putBoolean("dark", dark).apply(); }
            @Override public void setFontSize(float size) { fontSize = size; prefs.edit().putFloat("fontSize", size).apply(); }
            @Override public void setLanguage(int index) { prefs.edit().putString("bookLanguage", OnlineCatalog.LANGUAGES[index][0]).apply(); }
            @Override public void setOnlyLanguage(boolean only) { prefs.edit().putBoolean("onlyLanguage", only).apply(); }
            @Override public void setAppLanguage(int index) { app.atlante.library.ui.L.choose(MainActivity.this, new String[]{"", "it", "en"}[index]); recreateHere(); }
            @Override public void openProject() { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Mancio24x/atlante-android"))); }
        });
    }

    /**
     * Each person's own Hardcover key (free, made in their Hardcover account): it stays on this phone and is used
     * both by Sync and by the saga engine, which with it recognizes saga, volume number, sub-series and universe.
     */
    /**
     * Qualità del catalogo: every problem of the catalog with its count, the books concerned (a tap opens the sheet)
     * and the action that fixes it. Nothing is changed without you: merges and saga changes always show a preview.
     */
    private void catalogQuality() {
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        TextView wait = text("Controllo del catalogo…", 14, MUTED, false); panel.addView(wait);
        AlertDialog dialog = dialogBuilder().setTitle("Qualità del catalogo").setView(scroll).setNegativeButton("Chiudi", null).create();
        dialog.show();
        worker.execute(() -> {
            List<Book> all = store.snapshot();
            ArrayList<Book> noCover = new ArrayList<>(), smallCover = new ArrayList<>(), noAuthor = new ArrayList<>(), noIsbn = new ArrayList<>(),
                noGenre = new ArrayList<>(), noPages = new ArrayList<>(), noNumber = new ArrayList<>(), doubles = new ArrayList<>(), sagaCheck = new ArrayList<>();
            Map<String, List<Book>> byKey = new HashMap<>();
            Set<String> uncertain = app.atlante.library.saga.CatalogQuality.uncertainSagaBooks(this);
            for (Book b : all) {
                String source = b.meta.optString("coverSource", "");
                int w = coverWidth(b);
                if (w == 0) noCover.add(b); else if (w < 300 && !source.equals("gallery") && !source.equals("online")) smallCover.add(b);
                if (b.author.isEmpty() || b.author.equals("Autore sconosciuto")) noAuthor.add(b);
                if (BookMatcher.isbns(b.isbn).isEmpty()) noIsbn.add(b);
                if (app.atlante.library.saga.Genres.of(b).isEmpty()) noGenre.add(b);
                if (b.totalPages == 0) noPages.add(b);
                if (!b.series.isEmpty() && b.number == 0) noNumber.add(b);
                if (uncertain.contains(b.id)) sagaCheck.add(b);
                // Possible doubles: same title and first author, or the same ISBN (shown only, never merged by itself).
                List<String> authors = app.atlante.library.model.SearchIndex.authors(b);
                byKey.computeIfAbsent("t:" + app.atlante.library.model.SearchIndex.normalize(b.title) + "|" + (authors.isEmpty() ? "" : surnameOf(authors.get(0)).toLowerCase(Locale.ROOT)), k -> new ArrayList<>()).add(b);
                for (String i : BookMatcher.isbns(b.isbn)) byKey.computeIfAbsent("i:" + i, k -> new ArrayList<>()).add(b);
            }
            LinkedHashSet<Book> seen = new LinkedHashSet<>();
            for (List<Book> group : byKey.values()) if (group.size() > 1) seen.addAll(group);
            doubles.addAll(seen);
            doubles.sort((a, b) -> app.atlante.library.ebook.EbookParser.naturalCompare(a.title, b.title));
            runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                panel.removeView(wait);
                panel.addView(text(all.size() + " libri controllati.", 14, MUTED, false)); gap(panel, 8);
                qualityRow(panel, dialog, "Possibili doppioni", "Stesso titolo e autore, o stesso ISBN. «Ripara duplicati» unisce da solo quelli certi (stesso ISBN, stessa opera nel catalogo, stesso libro su Hardcover) e ti propone gli altri.", doubles,
                    "Ripara duplicati della libreria", () -> { dialog.dismiss(); startRepair(app.atlante.library.catalog.RepairWorker.LIBRARY); });
                if (app.atlante.library.sync.HardcoverAuth.connected(this))
                    qualityRow(panel, dialog, "Doppioni di Hardcover da verificare", "Sync unisce da sola i doppioni certi; qui quelli dubbi, con l’anteprima.", null, "Ripara duplicati Hardcover", () -> { dialog.dismiss(); repairHardcoverDuplicates(); });
                qualityRow(panel, dialog, "Saghe da verificare", "Il motore delle saghe non è sicuro: conferma o correggi in Ripara saghe.", sagaCheck, "Riconosci saghe e ordine", () -> { dialog.dismiss(); recognizeSeries(); });
                qualityRow(panel, dialog, "In una saga, senza numero", "Posizione da verificare: nessun numero viene inventato.", noNumber, null, null);
                qualityRow(panel, dialog, "Senza copertina", "", noCover, "Cerca le copertine", () -> { dialog.dismiss(); improveCovers(noCover, true, false); });
                qualityRow(panel, dialog, "Copertina piccola o sfocata", "Meno di 300 pixel; le copertine scelte da te non sono contate.", smallCover, "Migliora le copertine", () -> { dialog.dismiss(); improveCovers(smallCover, true, true); });
                qualityRow(panel, dialog, "Autore mancante", "", noAuthor, null, null);
                qualityRow(panel, dialog, "Senza ISBN", "Senza ISBN il riconoscimento delle edizioni usa titolo e autore.", noIsbn, null, null);
                qualityRow(panel, dialog, "Senza genere riconosciuto", "Dal catalogo offline, da Hardcover o dalla classificazione delle biblioteche; mai inventato.", noGenre,
                    "Indicizza i generi", () -> { dialog.dismiss(); startRepair(app.atlante.library.catalog.RepairWorker.GENRES); });
                qualityRow(panel, dialog, "Senza numero di pagine", "", noPages, null, null);
                panel.addView(button("Completa metadati (catalogo offline, poi online)", false, () -> { dialog.dismiss(); startRepair(app.atlante.library.catalog.RepairWorker.METADATA); }));
                panel.addView(text("Riempie solo i campi vuoti: quello che hai scritto, la tua edizione e la copertina scelta restano. Per un solo libro: apri il libro › «Completa i dati online».", 13, MUTED, false));
                panel.addView(button("Operazioni in background", R.style.Atlante_Button_Text, () -> { dialog.dismiss(); showOperations(); }));
            });
        });
    }

    private void qualityRow(LinearLayout panel, AlertDialog dialog, String title, String hint, List<Book> books, String action, Runnable run) {
        LinearLayout box = surface(panel, "");
        box.addView(text(title + (books == null ? "" : " · " + books.size()), 16, books != null && books.isEmpty() ? MUTED : INK, true));
        if (!hint.isEmpty()) box.addView(text(hint, 13, MUTED, false));
        if (books != null && books.isEmpty()) { box.addView(text("Tutto a posto ✓", 13, MUTED, false)); return; }
        LinearLayout buttons = row(); box.addView(buttons);
        if (books != null) buttons.addView(button("Mostra", R.style.Atlante_Button_Text, () -> {
            String[] labels = new String[books.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = books.get(i).title + " · " + books.get(i).author;
            dialogBuilder().setTitle(title).setItems(labels, (d, which) -> { dialog.dismiss(); details(books.get(which)); }).setNegativeButton("Chiudi", null).show();
        }));
        if (action != null) buttons.addView(button(action, R.style.Atlante_Button_Text, run));
    }

    /** Impostazioni › Copertine: what the app prefers when it chooses by itself, and the first filter of "Cambia copertina". */
    private void coverPreference(LinearLayout p) {
        p.addView(text("Copertine preferite",15,INK,true));
        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        String[][] options = {{"italian","Edizione italiana"},{"original","Copertina originale o inglese"},{"all","Mostra tutte quando scelgo"}};
        String current = getPreferences(MODE_PRIVATE).getString("coverPrefer","italian");
        for (String[] o : options) {
            com.google.android.material.radiobutton.MaterialRadioButton r = new com.google.android.material.radiobutton.MaterialRadioButton(this);
            r.setText(o[1]); r.setTextSize(15); r.setId(View.generateViewId()); r.setMinHeight(dp(48));
            group.addView(r); if (o[0].equals(current)) r.setChecked(true);
            r.setOnCheckedChangeListener((v, on) -> { if (!on) return;
                getPreferences(MODE_PRIVATE).edit().putString("coverPrefer", o[0]).apply();
                app.atlante.library.online.CoverSearch.setPrefer(o[0]); });
        }
        p.addView(group);
        p.addView(text("Vale per le copertine trovate dall’app e per il primo filtro di «Cambia copertina» nella scheda del libro. Con Hardcover collegato trovi anche le copertine inglesi e originali dei tuoi libri italiani. Una copertina scelta da te resta ovunque, anche nei widget.",13,MUTED,false));
    }

    private void hardcoverSection(LinearLayout p) {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        boolean on=app.atlante.library.sync.HardcoverAuth.connected(this);
        boolean oauth=app.atlante.library.sync.HardcoverAuth.usesOauth(this);
        String user=prefs.getString("hardcoverUser","");
        p.addView(text(on?"Chiave inserita"+(user.isEmpty()?"":" · collegato come @"+user):"Nessuna chiave inserita",15,on?INK:MUTED,false));
        p.addView(text("Con la tua chiave personale di Hardcover Atlante riconosce saga, numero del volume, sotto-serie e universo (per esempio il Cosmere) di tutti i libri, anche con il titolo italiano, più in fretta e con più precisione. Senza chiave usa Wikidata e Open Library. Ognuno usa la propria chiave: è gratuita e resta solo su questo telefono.",13,MUTED,false)); gap(p,8);
        MaterialSwitch use=new MaterialSwitch(this); use.setText("Usa Hardcover per riconoscere le saghe"); use.setTextSize(15); use.setMinHeight(dp(48));
        use.setChecked(prefs.getBoolean("hardcoverSagas",true)); use.setEnabled(on); p.addView(use);
        use.setOnCheckedChangeListener((v,checked)->{
            prefs.edit().putBoolean("hardcoverSagas",checked).apply();
            app.atlante.library.saga.Hardcover.configure(this);
            if(checked) try { app.atlante.library.saga.SagaScanWorker.recheck(this); } catch(RuntimeException ignored) { }
        });
        if(oauth) p.addView(button("Scollega Hardcover",R.style.Atlante_Button_Text,this::disconnectHardcover),new LinearLayout.LayoutParams(-1,-2));
        else {
            // Hardcover asks apps to connect with OAuth: approve on hardcover.app, no key to create or paste.
            if(app.atlante.library.sync.HardcoverAuth.getOauthAvailable()) p.addView(button("Collega con Hardcover",true,()->app.atlante.library.sync.HardcoverAuth.start(this)),new LinearLayout.LayoutParams(-1,-2));
            boolean primary=!on&&!app.atlante.library.sync.HardcoverAuth.getOauthAvailable();
            p.addView(button(on?"Cambia o rimuovi la chiave":"Usa una chiave personale",primary,this::hardcoverKey),new LinearLayout.LayoutParams(-1,-2));
            if(!on) p.addView(button("Crea la chiave su Hardcover ↗",R.style.Atlante_Button_Text,()->openLink("https://hardcover.app/account/api")),new LinearLayout.LayoutParams(-1,-2));
        }
    }

    /** Asks for the key, checks it with Hardcover before saving, then starts recognizing the library with it. */
    private void hardcoverKey() {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        LinearLayout panel=column(); panel.setPadding(dp(24),dp(8),dp(24),dp(4));
        panel.addView(text("Su hardcover.app accedi, apri Impostazioni › Hardcover API, copia la chiave e incollala qui. La stessa chiave serve anche per Sync.",14,MUTED,false)); gap(panel,8);
        EditText token=field(panel,"Chiave personale di Hardcover",prefs.getString("hardcoverToken",""));
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD); token.setSingleLine(true);
        com.google.android.material.dialog.MaterialAlertDialogBuilder dialog=dialogBuilder().setTitle("Chiave Hardcover").setView(scrolled(panel)).setNegativeButton("Annulla",null)
            .setPositiveButton("Verifica e salva",(d,w)->checkHardcoverKey(token.getText().toString().trim()));
        if(!prefs.getString("hardcoverToken","").isEmpty()) dialog.setNeutralButton("Rimuovi",(d,w)->{
            prefs.edit().remove("hardcoverToken").remove("hardcoverUser").remove("hardcoverLast").apply();
            app.atlante.library.saga.Hardcover.configure(this); showLibrary();
        });
        dialog.show();
    }

    private void disconnectHardcover() {
        worker.execute(()->{
            app.atlante.library.sync.HardcoverAuth.disconnect(this);
            runOnUiThread(()->{ app.atlante.library.saga.Hardcover.configure(this); app.atlante.library.ui.L.toast(this,"Hardcover scollegato",Toast.LENGTH_SHORT).show(); showLibrary(); });
        });
    }

    /** Back from hardcover.app with the user's approval: tokens, user name, then the sagas are checked with Hardcover. */
    private void finishHardcoverLogin(Uri uri) {
        AlertDialog waiting=loading("Hardcover","Collegamento in corso…");
        worker.execute(()->{
            try {
                app.atlante.library.sync.HardcoverAuth.finish(this,uri);
                String user=app.atlante.library.saga.Hardcover.username(app.atlante.library.sync.HardcoverAuth.token(this));
                runOnUiThread(()->{ waiting.dismiss(); if(isFinishing()||isDestroyed()) return;
                    getPreferences(MODE_PRIVATE).edit().putString("hardcoverUser",user).apply();
                    app.atlante.library.saga.Hardcover.configure(this);
                    try { app.atlante.library.saga.SagaScanWorker.recheck(this); } catch(RuntimeException ignored) { }
                    app.atlante.library.ui.L.toast(this,"Collegato a Hardcover come @"+user+". Riconosco le saghe della libreria in background.",Toast.LENGTH_LONG).show();
                    showLibrary(); });
            } catch(Exception e) { runOnUiThread(()->{ waiting.dismiss(); error(e); }); }
        });
    }

    private void checkHardcoverKey(String key) {
        if(key.isEmpty()) return;
        AlertDialog waiting=loading("Hardcover","Verifico la chiave…");
        worker.execute(()->{
            try {
                String user=app.atlante.library.saga.Hardcover.username(key);
                runOnUiThread(()->{ waiting.dismiss(); if(isFinishing()||isDestroyed()) return;
                    saveHardcoverKey(key,user);
                    app.atlante.library.ui.L.toast(this,"Chiave valida: collegato come @"+user+". Riconosco le saghe della libreria in background.",Toast.LENGTH_LONG).show();
                    showLibrary(); });
            } catch(Exception e) { runOnUiThread(()->{ waiting.dismiss(); error(e); }); }
        });
    }

    /** Saves a key and, when it is new, checks again with Hardcover the books without a sure saga. */
    private void saveHardcoverKey(String key, String user) {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        boolean changed=!key.equals(prefs.getString("hardcoverToken",""));
        android.content.SharedPreferences.Editor e=prefs.edit().putString("hardcoverToken",key);
        if(user!=null) e.putString("hardcoverUser",user);
        e.apply();
        app.atlante.library.saga.Hardcover.configure(this);
        if(changed&&!key.isEmpty()) try { app.atlante.library.saga.SagaScanWorker.recheck(this); } catch(RuntimeException ignored) { }
    }

    /** Light/dark mode, shared by every theme. */
    private void appearanceMode(LinearLayout p) {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        Picker mode=new Picker(p,"Modalità",Arrays.asList("Tema scelto","Chiara","Scura","Automatica"),
            Arrays.asList("Tema scelto","Chiara","Scura","Automatica").get(Arrays.asList("theme","light","dark","auto").indexOf(prefs.getString("appearanceMode","theme"))));
        p.addView(button("Applica modalità",false,()->{prefs.edit().putString("appearanceMode",new String[]{"theme","light","dark","auto"}[mode.position()]).commit();recreateHere();}));
    }

    /** The three colours of "Il mio tema" (stored as "#RRGGBB" strings); the current theme's until one is chosen. */
    private int[] customColors() {
        try { JSONObject j=new JSONObject(getPreferences(MODE_PRIVATE).getString("customPalette","{}"));
            return new int[]{android.graphics.Color.parseColor(j.getString("paper")),android.graphics.Color.parseColor(j.getString("surface")),android.graphics.Color.parseColor(j.getString("accent"))}; }
        catch(Exception e) { return new int[]{PAPER,WHITE,SAGE}; }
    }

    /** The round "Il mio tema" button: opens its own full-screen page. */
    private void customThemeCircle(LinearLayout p) {
        int[] c=customColors();
        p.addView(app.atlante.library.ui.compose.ThemeEditorView.circle(this,palette,c[0],c[1],c[2],palette.id.equals("custom"),()->{settingsSection="tema";showCompanion();if(companionScroll!=null)companionScroll.post(()->{if(companionScroll!=null)companionScroll.scrollTo(0,0);});}),new LinearLayout.LayoutParams(-1,-2));
    }

    /** "Il mio tema": colours above, preview below; applying it is all there is (one custom theme, nothing to name or save). */
    private void customThemePage(LinearLayout p) {
        int[] c=customColors();
        p.addView(app.atlante.library.ui.compose.ThemeEditorView.create(this,palette,c[0],c[1],c[2],(paper,surface,accent)->{
            try {
                JSONObject colors=new JSONObject().put("paper",String.format(Locale.ROOT,"#%06X",paper&0xffffff)).put("surface",String.format(Locale.ROOT,"#%06X",surface&0xffffff)).put("accent",String.format(Locale.ROOT,"#%06X",accent&0xffffff));
                getPreferences(MODE_PRIVATE).edit().putString("customPalette",colors.toString()).putString("theme","custom").remove("savedThemes").commit();
                settingsSection="aspetto"; recreateHere();
            } catch(Exception e){error(e);}
        }),new LinearLayout.LayoutParams(-1,-2));
    }

    /** Applies the Material theme of a palette; "dynamic" takes Material You colors from the wallpaper (Android 12+). */
    private void applyTheme(String id) {
        if (id.equals("dynamic") && DynamicColors.isDynamicColorAvailable()) {
            boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            setTheme(night ? R.style.Atlante_Base_Dark : R.style.Atlante_Base);
            DynamicColors.applyToActivityIfAvailable(this);
            applyPalette(app.atlante.library.ui.ThemePreferences.resolve(getPreferences(MODE_PRIVATE),fromTheme(getTheme(), night),night));
            return;
        }
        ThemePalette p = ThemePalette.find(id);
        if(id.equals("custom"))p=new ThemePalette("custom","Il mio tema","Tre colori personalizzati",p.dark,p.paper,p.surface,p.ink,p.muted,p.accent,p.onAccent,p.line,p.warm);
        boolean systemDark=(getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        p=app.atlante.library.ui.ThemePreferences.resolve(getPreferences(MODE_PRIVATE),p,systemDark);
        setTheme(getPreferences(MODE_PRIVATE).getString("appearanceMode","theme").equals("theme")&&!id.equals("custom")?paletteStyle(p.id):p.dark?R.style.Atlante_Base_Dark:R.style.Atlante_Base); applyPalette(p);
    }
    private static int paletteStyle(String id) {
        switch (id) {
            case "rose": return R.style.Atlante_Palette_rose;
            case "lavender": return R.style.Atlante_Palette_lavender;
            case "sage": return R.style.Atlante_Palette_sage;
            case "blue": return R.style.Atlante_Palette_blue;
            case "sand": return R.style.Atlante_Palette_sand;
            case "graphite": return R.style.Atlante_Palette_graphite;
            case "midnight": return R.style.Atlante_Palette_midnight;
            case "amoled": return R.style.Atlante_Palette_amoled;
            default: return R.style.Atlante_Palette_atlante;
        }
    }
    /** Palette for the hand-drawn parts (map, covers, reader) read from the Material You theme. */
    private static ThemePalette fromTheme(android.content.res.Resources.Theme theme, boolean night) {
        return new ThemePalette("dynamic", "Colori del telefono", "Material You · segue lo sfondo e il tema del telefono", night,
            attr(theme, com.google.android.material.R.attr.colorSurface), attr(theme, com.google.android.material.R.attr.colorSurfaceContainerLow),
            attr(theme, com.google.android.material.R.attr.colorOnSurface), attr(theme, com.google.android.material.R.attr.colorOnSurfaceVariant),
            attr(theme, androidx.appcompat.R.attr.colorPrimary), attr(theme, com.google.android.material.R.attr.colorOnPrimary),
            attr(theme, com.google.android.material.R.attr.colorOutlineVariant), attr(theme, com.google.android.material.R.attr.colorTertiary));
    }
    private static int attr(android.content.res.Resources.Theme theme, int id) {
        android.util.TypedValue value = new android.util.TypedValue(); theme.resolveAttribute(id, value, true); return value.data;
    }
    private void applyPalette(ThemePalette p) {
        palette = p; PAPER = p.paper; WHITE = p.surface; INK = p.ink; MUTED = blend(p.muted, p.ink, .3f);   // secondary text a little darker: notes stay readable
        SAGE = p.accent; ON_ACCENT = p.onAccent; COPPER = p.warm; GOLD = p.onAccent; LINE = p.line;
    }
    private AlertDialog loading(String title, String message) {
        LinearLayout panel = row(); panel.setPadding(dp(24),dp(16),dp(24),dp(16));
        CircularProgressIndicator spinner = new CircularProgressIndicator(this); spinner.setIndeterminate(true);
        panel.addView(spinner);
        TextView label = text(message,15,INK,false); label.setPadding(dp(16),0,0,0); panel.addView(label);
        AlertDialog dialog = dialogBuilder().setTitle(title).setView(panel).setCancelable(false).create(); dialog.show(); return dialog;
    }
    private MaterialAlertDialogBuilder dialogBuilder() {
        return new MaterialAlertDialogBuilder(this) {
            @Override public AlertDialog create() {
                AlertDialog dialog = super.create();
                if (dialog.getWindow() != null) app.atlante.library.ui.L.watch(dialog.getWindow().getDecorView());
                if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                return dialog;
            }
        };
    }
    private void themeTiles(LinearLayout panel) {
        ArrayList<ThemePalette> options = new ArrayList<>();
        if (DynamicColors.isDynamicColorAvailable()) {
            boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            Context preview = DynamicColors.wrapContextIfAvailable(new ContextThemeWrapper(this, night ? R.style.Atlante_Base_Dark : R.style.Atlante_Base));
            options.add(fromTheme(preview.getTheme(), night));
        }
        options.addAll(Arrays.asList(ThemePalette.ALL));
        for (ThemePalette option : options) {
            LinearLayout tile = row(); tile.setPadding(dp(16), dp(14), dp(16), dp(14));
            tile.setBackground(background(option.surface, option.id.equals(palette.id) ? option.accent : option.line));
            LinearLayout label = column(); tile.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            label.addView(text(option.name + (option.id.equals(palette.id) ? "  ✓" : ""), 18, option.ink, true));
            label.addView(text(option.description, 12, option.muted, false));
            for (int color : new int[]{option.paper, option.accent, option.ink}) {
                View swatch = new View(this); swatch.setBackground(background(color, option.line));
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(18), dp(30)); size.setMargins(dp(4),0,0,0); tile.addView(swatch,size);
            }
            tile.setContentDescription(app.atlante.library.ui.L.t(option.name) + ", " + app.atlante.library.ui.L.t(option.description)); tile.setMinimumHeight(dp(64));
            // Material themes apply when the activity is created: save and recreate.
            tile.setOnClickListener(v -> { getPreferences(MODE_PRIVATE).edit().putString("theme", option.id).commit(); recreateHere(); });
            panel.addView(tile); gap(panel, 10);
        }
    }

    public int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    TextView text(String value, float size, int color, boolean serif) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(serif ? Typeface.create("serif", Typeface.NORMAL) : Typeface.DEFAULT);
        t.setLineSpacing(dp(2), 1); return t;
    }
    GradientDrawable background(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(22));
        if (stroke != Color.TRANSPARENT) d.setStroke(dp(1), stroke);
        return d;
    }
    void gap(LinearLayout v, int height) { View s = new View(this); v.addView(s, new LinearLayout.LayoutParams(1, dp(height))); }
    void rule(LinearLayout v) { View s = new View(this); s.setBackgroundColor(LINE); v.addView(s, new LinearLayout.LayoutParams(-1, dp(1))); }
    Button button(String label, boolean primary, Runnable action) {
        return button(label, primary ? 0 : R.style.Atlante_Button_Tonal, action);
    }
    /** Material 3 button; {@code overlay} picks the style (0 = filled, or Atlante.Button.*). */
    MaterialButton button(String label, int overlay, Runnable action) {
        MaterialButton b = new MaterialButton(overlay == 0 ? this : new ContextThemeWrapper(this, overlay));
        if(overlay==0){b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(SAGE));b.setTextColor(ON_ACCENT);}
        else {b.setTextColor(SAGE);if(overlay==R.style.Atlante_Button_Tonal)b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));
            // A text button is still a button: a light outline says it can be pressed.
            if(overlay==R.style.Atlante_Button_Text){b.setStrokeWidth(dp(1));b.setStrokeColor(android.content.res.ColorStateList.valueOf(blend(LINE,SAGE,.3f)));}}
        b.setText(label); b.setMinHeight(dp(48)); b.setOnClickListener(v -> action.run());
        return b;
    }
    /** A tappable text (a link such as «‹ Tutte le impostazioni»): a pill with a light outline, so it reads as a button. */
    TextView pill(TextView t) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(dp(22)); d.setStroke(dp(1), blend(LINE, SAGE, .3f)); d.setColor(android.graphics.Color.TRANSPARENT);
        t.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(blend(PAPER, SAGE, .18f)), d, null));
        t.setPadding(dp(16), 0, dp(16), 0); t.setMinHeight(dp(44)); t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    MaterialButton iconButton(int icon, String description, Runnable action) {
        MaterialButton b = button("", R.style.Atlante_Button_Icon, action);
        b.setIconResource(icon); b.setIconTint(android.content.res.ColorStateList.valueOf(INK)); b.setIconSize(dp(24));
        b.setContentDescription(description); b.setTooltipText(description);
        return b;
    }
    TextView sectionLabel(String value) {
        TextView label = text(value, 12, SAGE, false); label.setLetterSpacing(.08f); label.setTypeface(Typeface.DEFAULT_BOLD); return label;
    }
    MaterialCardView card(LinearLayout content) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(WHITE); card.setStrokeColor(LINE); card.setStrokeWidth(dp(1));
        card.setCardElevation(0); card.setRadius(dp(16)); card.addView(content); return card;
    }
    void base(int color) {
        editingPage = false;   // every new page starts as a normal page; editorPage() sets it again
        boolean light = (((color >> 16) & 255) * 299 + ((color >> 8) & 255) * 587 + (color & 255) * 114) > 150000;
        int flags = light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
        if (android.os.Build.VERSION.SDK_INT >= 27 && light) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(android.os.Build.VERSION.SDK_INT >= 27 || !light ? color : 0xff193239);
        drawer = null;
        screen = new FrameLayout(this);
        root = column(); root.setBackgroundColor(color); screen.addView(root, new FrameLayout.LayoutParams(-1,-1));
        // API 35 draws edge-to-edge: keep all controls clear of system bars and cutouts.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            insetTop = insets.getSystemWindowInsetTop(); insetBottom = insets.getSystemWindowInsetBottom();
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(screen); root.requestApplyInsets();
    }
    private static String joinNonEmpty(String sep, String... parts) {
        StringBuilder out = new StringBuilder(); for (String p : parts) if (p != null && !p.isEmpty()) out.append(sep).append(p); return out.toString();
    }

    /** The offline catalog (read-only, indexed): null if it cannot be opened. */
    app.atlante.library.catalog.CatalogDao catalogDao() {
        try { return app.atlante.library.catalog.CatalogDatabase.get(this).catalog(); } catch (Exception e) { return null; }
    }
    /**
     * New crash reports since the last start: copied to Download/Atlante/Diagnostica and, if the app closed for an
     * error, said once, with a way to share the report.
     */
    private void reportCrashesSinceLastTime() {
        android.content.Context context = getApplicationContext();
        new Thread(() -> {
            // Finished repairs with nothing to decide: gone (Operazioni keeps only work in progress and decisions).
            try { kotlinx.coroutines.BuildersKt.runBlocking(kotlin.coroutines.EmptyCoroutineContext.INSTANCE, (scope, cont) -> app.atlante.library.saga.db.AtlanteDatabase.get(context).saga().dropSettled(cont)); } catch (Exception ignored) { }
            int crashes = app.atlante.library.diagnostics.CrashLog.exportNew(context);
            if (crashes > 0) try { app.atlante.library.diagnostics.ReportUploader.now(context); } catch (RuntimeException ignored) { }
            if (crashes > 0) runOnUiThread(() -> { if (isFinishing() || isDestroyed()) return;
                dialogBuilder().setTitle("L’app si era chiusa per un errore")
                    .setMessage("Il rapporto è salvato sul telefono, in " + app.atlante.library.diagnostics.CrashLog.publicFolder() + " e in Impostazioni › Privacy e progetto. Puoi condividerlo per farlo correggere.")
                    .setNegativeButton("Chiudi", null).setPositiveButton("Condividi", (d, w) -> shareDiagnostics()).show(); });
        }).start();
    }

    /** Once: may the app send its crash and error reports to the project (without personal data)? */
    private void askReportConsent() {
        if (!app.atlante.library.diagnostics.ReportUploader.configured() || app.atlante.library.diagnostics.ReportUploader.consent(this) != null) return;
        dialogBuilder().setTitle("Aiuta a migliorare Atlante")
            .setMessage("Se l’app si chiude o va in errore, posso inviare il rapporto al progetto, ogni tanto e solo con internet. Prima di inviarlo tolgo titoli, nomi, email e chiavi; il telefono è un codice casuale. Puoi cambiare idea in Impostazioni › Privacy e progetto.")
            .setCancelable(false)
            .setNegativeButton("No, grazie", (d, w) -> app.atlante.library.diagnostics.ReportUploader.setConsent(this, false))
            .setPositiveButton("Invia i rapporti", (d, w) -> app.atlante.library.diagnostics.ReportUploader.setConsent(this, true)).show();
    }

    /** Privacy e progetto › Diagnostica: how many reports, where they are, share or clear them. */
    private void diagnosticsSection(LinearLayout panel) {
        sectionHeader(panel, "Diagnostica", "", null);
        java.util.List<File> all = app.atlante.library.diagnostics.CrashLog.reports(this);
        int crashes = app.atlante.library.diagnostics.CrashLog.crashes(this).size();
        panel.addView(text(all.isEmpty() ? "Nessun errore registrato." : crashes + " rapporti di crash · " + (all.size() - crashes) + " registro degli errori", 15, INK, false));
        panel.addView(text("Se l’app si chiude o mostra un errore, il dettaglio resta sul telefono (anche in " + app.atlante.library.diagnostics.CrashLog.publicFolder() + "). Non viene inviato niente: lo condividi tu, se vuoi.", 13, MUTED, false));
        gap(panel, 8);
        if (app.atlante.library.diagnostics.ReportUploader.configured()) {
            boolean on = Boolean.TRUE.equals(app.atlante.library.diagnostics.ReportUploader.consent(this));
            com.google.android.material.materialswitch.MaterialSwitch send = new com.google.android.material.materialswitch.MaterialSwitch(this);
            send.setText("Invia i rapporti al progetto (senza dati personali)"); send.setChecked(on); send.setMinHeight(dp(56)); send.setTextColor(INK);
            send.setOnCheckedChangeListener((v, checked) -> app.atlante.library.diagnostics.ReportUploader.setConsent(this, checked));
            panel.addView(send, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout buttons = row(); panel.addView(buttons);
        buttons.addView(button("Condividi i rapporti", R.style.Atlante_Button_Outlined, this::shareDiagnostics));
        if (!all.isEmpty()) { View space = new View(this); buttons.addView(space, new LinearLayout.LayoutParams(dp(8), 1));
            buttons.addView(button("Svuota", R.style.Atlante_Button_Text, () -> { app.atlante.library.diagnostics.CrashLog.clear(this); showCompanion(); })); }
        gap(panel, 20);
    }

    /** Every report (crashes and errors) shared as text files: nothing leaves the phone unless you send it. */
    void shareDiagnostics() {
        java.util.List<File> files = app.atlante.library.diagnostics.CrashLog.reports(this);
        if (files.isEmpty()) { app.atlante.library.ui.L.toast(this, "Nessun rapporto di errore", Toast.LENGTH_SHORT).show(); return; }
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) uris.add(androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", f));
        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .putExtra(Intent.EXTRA_SUBJECT, "Atlante Library · rapporti di errore").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(send, app.atlante.library.ui.L.t("Condividi i rapporti"))); }
        catch (android.content.ActivityNotFoundException e) { error(e); }
    }

    /** A job that ends after the page is gone (rotation, leaving the app) reports nothing: a dialog on a closed activity crashes. */
    void error(Exception e) { app.atlante.library.diagnostics.CrashLog.error(this, "Messaggio di errore in " + destination, e); if (isFinishing() || isDestroyed()) return; dialogBuilder().setTitle("Operazione non completata").setMessage(message(e)).setPositiveButton("Chiudi", null).show(); }
    static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    void save() { try { store.save(); } catch (Exception e) { error(e); } }

    private void showLibrary() {
        detailId = null; editingPage = false; timerLabel = null; uiHandler.removeCallbacks(timerTick); onlineBook = null;
        if (!destination.equals("Biblioteca") && !destination.equals("Cerca")) { showCompanion(); return; }
        reading = null; pages = null;
        base(PAPER);
        boolean map = destination.equals("Biblioteca") && tab.equals("Mappa");
        LinearLayout header = column(); header.setPadding(dp(8), dp(4), dp(8), dp(8));
        root.addView(header);
        if (statusPage()) { libraryTop(header); addLibraryStage(false); return; }
        LinearLayout masthead = row(); header.addView(masthead);
        masthead.addView(iconButton(R.drawable.ic_menu, "Apri menu laterale", this::openDrawer));
        boolean place = destination.equals("Biblioteca") && (map || tab.equals("Saghe") || tab.equals("Universi"));
        if (place) {
            if (tab.equals("Saghe") && !openSaga.isEmpty()) { masthead.removeAllViews(); masthead.addView(iconButton(R.drawable.ic_back, "Torna alle saghe", () -> { openSaga = ""; showLibrary(); })); }
            String universe = tab.equals("Saghe") && !openSaga.isEmpty() ? sagaUniverse(openSaga) : "";
            masthead.addView(pageHeading(map ? "MAPPA DELLE LETTURE" : tab.equals("Saghe") ? (openSaga.isEmpty() ? "SAGHE" : universe.isEmpty() ? "SAGA" : "SAGA · " + universe.toUpperCase(Locale.ITALIAN)) : "UNIVERSI",
                map ? "Autore → saghe → libri" : tab.equals("Saghe") ? (openSaga.isEmpty() ? "Una storia dopo l’altra" : openSaga) : "Mondi narrativi"), new LinearLayout.LayoutParams(0, -2, 1));
            if (map) masthead.addView(iconButton(R.drawable.ic_search, "Cerca un autore", this::chooseMapAuthor));
        } else {
        TextView brand = text(destination.equals("Cerca") ? "Cerca" : tab.equals("Autori") ? "Autori" : "Atlante", 22, INK, true);
        brand.setPadding(dp(8), 0, 0, 0); brand.setMaxLines(1); brand.setEllipsize(TextUtils.TruncateAt.END);
        masthead.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        }
        if (!place) masthead.addView(iconButton(listView ? R.drawable.ic_grid : R.drawable.ic_list, listView ? "Griglia" : "Elenco", () -> {
            String[] modes = {"Scaffale", "Griglia", "Elenco"};
            dialogBuilder().setTitle("Vista biblioteca").setSingleChoiceItems(modes, shelfView ? 0 : listView ? 2 : 1, (dialog, which) -> {
                shelfView = which == 0; listView = which == 2;
                getPreferences(MODE_PRIVATE).edit().putBoolean("listView", listView).putBoolean("shelfView", shelfView).apply();
                dialog.dismiss(); showLibrary();
            }).setNegativeButton("Chiudi", null).show();
        }));
        if (!place) masthead.addView(iconButton(R.drawable.ic_settings, "Impostazioni", this::settings));
        LinearLayout controls = column(); controls.setPadding(dp(8), dp(4), dp(8), 0); header.addView(controls);
        if (map) mapHeader(controls); else if (!place) libraryHeader(controls);
        if(destination.equals("Cerca")) controls.addView(button("Cerca anche online",R.style.Atlante_Button_Text,()->{lastOnlineQuery=query;prefillOnline=true;onlineSearch();}));
        addLibraryStage(!place);   // Saghe, Mappa, Universi (designs 7b-7d) have no "Aggiungi libro" over them
    }
    /** The scrolling area under the library header; the extended button only where the top bar has no "+". */
    private void addLibraryStage(boolean fab) {
        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        body = new FrameLayout(this); grid = null; adapter = null; content = null;
        stage.addView(body, new FrameLayout.LayoutParams(-1, -1));
        if (fab) {
            ExtendedFloatingActionButton add = new ExtendedFloatingActionButton(this);
            add.setText("Aggiungi libro"); add.setIconResource(R.drawable.ic_add); add.setOnClickListener(v -> manualBook());
            FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            corner.setMargins(dp(16), dp(16), dp(16), dp(16)); stage.addView(add, corner);
        }
        notice = text("", 13, MUTED, false);
        renderContent(); addBottomNav(); updateBack();
    }

    /**
     * [Barra di ricerca] [Genere] [Autore] in one row, at most one suggestion under it (an author, a saga or a genre
     * matching what you type: a tap applies it as a filter and clears the text), then the reading states and the
     * other filters (saga, language, decade, format, order).
     */
    /** The library proper (a reading-state tab), as opposed to Cerca, maps and indexes. */
    private boolean statusPage() { return destination.equals("Biblioteca") && Arrays.asList(STATUS_TABS).contains(tab); }

    /**
     * Design 3a: [☰] [search pill with Filtri] [+] on one row, then the reading states with their counts.
     * Genre, author, other filters and order live behind the Filtri button; the catalog strip and the single
     * suggestion stay under the search.
     */
    private void libraryTop(LinearLayout header) {
        LinearLayout line = row(); header.addView(line, new LinearLayout.LayoutParams(-1, -2));
        line.addView(iconButton(R.drawable.ic_menu, "Apri menu laterale", this::openDrawer));
        LinearLayout pill = row(); pill.setPadding(dp(14), 0, dp(2), 0); GradientDrawable shape = background(WHITE, LINE); shape.setCornerRadius(dp(24)); pill.setBackground(shape);
        LinearLayout.LayoutParams pillSize = new LinearLayout.LayoutParams(0, dp(48), 1); pillSize.setMargins(dp(4), 0, dp(4), 0); line.addView(pill, pillSize);
        ImageView glass = new ImageView(this); glass.setImageResource(R.drawable.ic_search); glass.setColorFilter(MUTED); pill.addView(glass, new LinearLayout.LayoutParams(dp(20), dp(20)));
        EditText search = new EditText(this); search.setBackground(null); search.setSingleLine(true); search.setTextSize(15); search.setTextColor(INK); search.setHintTextColor(MUTED);
        int total = store.snapshot().size();
        // Narrow phones: the short hint, so it is never cut ("Cerca in 9 lib").
        search.setHint(getResources().getConfiguration().screenWidthDp < 400 ? "Cerca" : "Cerca in " + total + (total == 1 ? " libro" : " libri")); search.setContentDescription("Cerca nella biblioteca");
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH); search.setText(query); search.setPadding(dp(10), 0, dp(4), 0);
        pill.addView(search, new LinearLayout.LayoutParams(0, -1, 1));
        int active = activeFilters();
        MaterialButton tune = iconButton(R.drawable.ic_tune, active > 0 ? "Filtri e ordine, " + active + (active == 1 ? " attivo" : " attivi") : "Filtri e ordine", this::libraryMenu);
        tune.setIconTint(android.content.res.ColorStateList.valueOf(active > 0 ? COPPER : SAGE)); pill.addView(tune, new LinearLayout.LayoutParams(dp(44), dp(44)));
        line.addView(iconButton(listView || shelfView ? R.drawable.ic_grid : R.drawable.ic_list, listView || shelfView ? "Griglia" : "Elenco", this::chooseView));
        line.addView(iconButton(R.drawable.ic_add, "Aggiungi libro", this::manualBook));
        Chip suggestion = new Chip(this); suggestion.setVisibility(View.GONE); suggestion.setChipIconResource(R.drawable.ic_tune); suggestion.setChipIconVisible(true);
        header.addView(suggestion, new LinearLayout.LayoutParams(-2, -2));
        app.atlante.library.ui.compose.CatalogStripView.Handle strip0 = app.atlante.library.ui.compose.CatalogStripView.create(this, palette, sagaHost);
        header.addView(strip0.getView(), new LinearLayout.LayoutParams(-1, -2));
        catalogStrip = strip0; strip0.update(query);
        watchSearch(search, suggestion);
        HorizontalScrollView strip = new HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false); strip.setPadding(0, dp(10), 0, 0);
        LinearLayout chips = row(); strip.addView(chips); header.addView(strip);
        List<Book> all = store.snapshot();
        for (String name : STATUS_TABS) {
            int count = LibraryQuery.select(all, "", name, "", "", "Titolo", "", "", "", "", "").size();
            if (name.equals("Preferiti") && count == 0 && !tab.equals(name)) continue;
            boolean on = name.equals(tab);
            TextView chip = pillChip(name.equals("Tutti i libri") ? "Tutti" : name, Integer.toString(count), on);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34)); lp.setMargins(chips.getChildCount() == 0 ? 0 : dp(8), 0, 0, 0);
            chips.addView(chip, lp);
            chip.setOnClickListener(v -> { if (!tab.equals(name)) { tab = name; updateBack(); showLibrary(); } });
        }
    }
    /** A rounded chip: filled with the ink colour when selected, a hairline otherwise; the count in a lighter tone. */
    TextView pillChip(String label, String count, boolean on) {
        TextView chip = text("", 13, on ? PAPER : INK, false); chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        SpannableStringBuilder value = new SpannableStringBuilder(label);
        if (!count.isEmpty()) { int from = value.length(); value.append("  ").append(count); value.setSpan(new android.text.style.ForegroundColorSpan(on ? (PAPER & 0x00FFFFFF) | 0xB3000000 : MUTED), from, value.length(), 0); }
        chip.setText(value); chip.setGravity(Gravity.CENTER); chip.setPadding(dp(13), 0, dp(13), 0); chip.setMinWidth(dp(48));
        GradientDrawable g = background(on ? INK : Color.TRANSPARENT, on ? INK : LINE); g.setCornerRadius(dp(17)); chip.setBackground(g);
        chip.setContentDescription(label + (count.isEmpty() ? "" : ", " + count) + (on ? ", selezionato" : ""));
        return chip;
    }
    private int activeFilters() {
        int n = 0; for (String f : new String[]{filterGenre, filterFormat, filterSaga, filterAuthor, filterLanguage, filterDecade, filterUniverse}) if (!f.isEmpty()) n++; return n;
    }
    /**
     * Filtri e ordine as a bottom sheet: the active filters as chips (× removes one), then Genere, Autore and the
     * other filters with the order, each showing its value; «Azzera» clears them all.
     */
    private void libraryMenu() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        if (sheet.getWindow() != null) app.atlante.library.ui.L.watch(sheet.getWindow().getDecorView());
        LinearLayout p = column(); p.setPadding(dp(20), dp(8), dp(20), dp(24));
        LinearLayout head = row(); p.addView(head, new LinearLayout.LayoutParams(-1, -2));
        head.addView(text("Filtri e ordine", 22, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (hasFilters()) head.addView(button("Azzera", R.style.Atlante_Button_Text, () -> { sheet.dismiss(); clearFilters(); applyLibraryFilters(); }));
        String[][] active = {{"Genere", filterGenre}, {"Autore", filterAuthor}, {"Saga", filterSaga}, {"Universo", filterUniverse}, {"Lingua", filterLanguage}, {"Anni", filterDecade}, {"Formato", filterFormat}};
        com.google.android.material.chip.ChipGroup chips = new com.google.android.material.chip.ChipGroup(this); chips.setChipSpacing(dp(6));
        for (String[] f : active) if (!f[1].isEmpty()) {
            Chip c = new Chip(this); c.setText(f[0] + ": " + (f[0].equals("Lingua") ? OnlineCatalog.languageName(f[1]) : f[1])); c.setCloseIconVisible(true);
            c.setCloseIconContentDescription("Togli il filtro " + f[0].toLowerCase(Locale.ITALIAN));
            c.setOnCloseIconClickListener(v -> { sheet.dismiss(); removeFilter(f[0]); applyLibraryFilters(); }); chips.addView(c);
        }
        if (chips.getChildCount() > 0) { gap(p, 8); p.addView(chips); }
        gap(p, 12);
        LinearLayout list = listCard(p);
        listRow(list, R.drawable.ic_tune, "Filtra per genere", filterGenre.isEmpty() ? "Tutti i generi" : filterGenre, () -> { sheet.dismiss(); chooseGenre(); });
        listRow(list, R.drawable.ic_book, "Filtra per autore", filterAuthor.isEmpty() ? "Tutti gli autori" : filterAuthor, () -> { sheet.dismiss(); chooseAuthor(); });
        listRow(list, R.drawable.ic_list, "Altri filtri e ordine", (moreFilterCount() > 0 ? moreFilterCount() + (moreFilterCount() == 1 ? " filtro attivo" : " filtri attivi") : "Saga, universo, lingua, anni, formato") + " · ordine: " + sortOrder.toLowerCase(Locale.ROOT), () -> { sheet.dismiss(); libraryFilters(); });
        ScrollView scroll = new ScrollView(this); scroll.addView(p); scroll.setBackgroundColor(WHITE);
        sheet.setContentView(scroll); sheet.show();
    }
    private int moreFilterCount() { int n = 0; for (String f : new String[]{filterSaga, filterUniverse, filterLanguage, filterDecade, filterFormat}) if (!f.isEmpty()) n++; return n; }
    private void removeFilter(String name) {
        switch (name) { case "Genere": filterGenre = ""; break; case "Autore": filterAuthor = ""; break; case "Saga": filterSaga = ""; break; case "Universo": filterUniverse = ""; break;
            case "Lingua": filterLanguage = ""; break; case "Anni": filterDecade = ""; break; default: filterFormat = ""; }
    }
    /** Live search with the single suggestion and the catalog strip, shared by both library headers. */
    private void watchSearch(EditText search, Chip suggestion) {
        Runnable suggest = () -> {
            String[] s = suggestionFor(query);
            if (s == null) { suggestion.setVisibility(View.GONE); return; }
            suggestion.setText(s[0] + ": " + s[1]); suggestion.setContentDescription("Filtra per " + s[0].toLowerCase(Locale.ITALIAN) + " " + s[1]);
            suggestion.setVisibility(View.VISIBLE);
            suggestion.setOnClickListener(v -> {
                if (s[0].equals("Autore")) filterAuthor = s[1]; else if (s[0].equals("Saga")) filterSaga = s[1]; else filterGenre = s[1];
                query = ""; hideKeyboard(v); applyLibraryFilters();
            });
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { query = s.toString(); updateBack();
                if (filterTask != null) uiHandler.removeCallbacks(filterTask);
                filterTask = () -> { if (reading == null) { renderContent(); suggest.run(); if (catalogStrip != null) catalogStrip.update(query); } }; uiHandler.postDelayed(filterTask, 180); }
            public void afterTextChanged(Editable e) { }
        });
        search.setOnEditorActionListener((v, action, event) -> { hideKeyboard(v); return true; });
        suggest.run();
    }

    private void libraryHeader(LinearLayout header) {
        LinearLayout line = row(); line.setGravity(Gravity.CENTER_VERTICAL); header.addView(line);
        TextInputLayout box = new TextInputLayout(new ContextThemeWrapper(this, R.style.Atlante_Field));
        box.setHintEnabled(false); box.setStartIconDrawable(R.drawable.ic_search);
        box.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        float round = dp(28); box.setBoxCornerRadii(round, round, round, round);
        TextInputEditText search = new TextInputEditText(box.getContext()); search.setSingleLine(true);
        search.setHint("Cerca titolo, autore, saga, ISBN"); search.setContentDescription("Cerca nella biblioteca");
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH); search.setText(query);
        box.addView(search, new LinearLayout.LayoutParams(-1, -2)); line.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
        Chip genreChip = quickFilter(filterGenre.isEmpty() ? "Genere" : filterGenre, !filterGenre.isEmpty(), "Filtra per genere", this::chooseGenre);
        Chip authorChip = quickFilter(filterAuthor.isEmpty() ? "Autore" : surnameOf(filterAuthor), !filterAuthor.isEmpty(), "Filtra per autore", this::chooseAuthor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMarginStart(dp(6));
        line.addView(genreChip, lp); line.addView(authorChip, new LinearLayout.LayoutParams(lp));
        Chip suggestion = new Chip(this); suggestion.setVisibility(View.GONE); suggestion.setChipIconResource(R.drawable.ic_tune); suggestion.setChipIconVisible(true);
        header.addView(suggestion, new LinearLayout.LayoutParams(-2, -2));
        // The offline catalog in the same search: works not in the library yet (one bar for everything).
        app.atlante.library.ui.compose.CatalogStripView.Handle strip0 = app.atlante.library.ui.compose.CatalogStripView.create(this, palette, sagaHost);
        header.addView(strip0.getView(), new LinearLayout.LayoutParams(-1, -2));
        catalogStrip = strip0; strip0.update(query);
        Runnable suggest = () -> {
            String[] s = suggestionFor(query);
            if (s == null) { suggestion.setVisibility(View.GONE); return; }
            suggestion.setText(s[0] + ": " + s[1]); suggestion.setContentDescription("Filtra per " + s[0].toLowerCase(Locale.ITALIAN) + " " + s[1]);
            suggestion.setVisibility(View.VISIBLE);
            suggestion.setOnClickListener(v -> {
                if (s[0].equals("Autore")) filterAuthor = s[1]; else if (s[0].equals("Saga")) filterSaga = s[1]; else filterGenre = s[1];
                query = ""; hideKeyboard(v); applyLibraryFilters();
            });
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { query = s.toString(); updateBack();
                if (filterTask != null) uiHandler.removeCallbacks(filterTask);
                filterTask = () -> { if (reading == null) { renderContent(); suggest.run(); if (catalogStrip != null) catalogStrip.update(query); } }; uiHandler.postDelayed(filterTask, 180); }
            public void afterTextChanged(Editable e) { }
        });
        search.setOnEditorActionListener((v, action, event) -> { hideKeyboard(v); return true; });
        suggest.run();
        HorizontalScrollView strip = new HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false);
        ChipGroup chips = new ChipGroup(this); chips.setSingleLine(true); chips.setSingleSelection(true); chips.setSelectionRequired(true);
        strip.addView(chips); header.addView(strip);
        for (String name : STATUS_TABS) {
            Chip chip = new Chip(this); chip.setText(name.equals("Tutti i libri") ? "Tutti" : name);
            chip.setCheckable(true); chip.setId(View.generateViewId());
            chips.addView(chip); if (name.equals(tab) || (name.equals("Tutti i libri") && !Arrays.asList(STATUS_TABS).contains(tab))) chips.check(chip.getId());
            chip.setOnClickListener(v -> { if (!tab.equals(name)) { tab = name; updateBack(); renderContent(); } });
        }
        Chip filters = new Chip(this); filters.setCheckable(false); filters.setChipIconResource(R.drawable.ic_tune); filters.setChipIconVisible(true);
        filters.setText(hasMoreFilters() ? "Altri filtri attivi" : "Altri filtri e ordine"); filters.setOnClickListener(v -> libraryFilters());
        chips.addView(filters);
        if (hasFilters()) { Chip reset = new Chip(this); reset.setText("Azzera filtri"); reset.setOnClickListener(v -> { clearFilters(); applyLibraryFilters(); }); chips.addView(reset); }
    }

    private Chip quickFilter(String label, boolean active, String description, Runnable open) {
        Chip c = new Chip(this); c.setText(label); c.setCheckable(true); c.setChecked(active); c.setCheckedIconVisible(false);
        c.setMaxWidth(dp(110)); c.setEllipsize(TextUtils.TruncateAt.END); c.setContentDescription(description + (active ? ": " + label : ""));
        c.setOnClickListener(v -> { c.setChecked(active); open.run(); });
        return c;
    }

    private static String surnameOf(String author) { String[] p = author.trim().split("\\s+"); return p[p.length - 1]; }

    /** The one suggestion for the typed text: an author first, then a saga, then a genre; null when none fits. */
    private String[] suggestionFor(String text) {
        List<String> words = app.atlante.library.model.SearchIndex.queryWords(text);
        if (words.isEmpty() || text.trim().length() < 3) return null;
        Map<String, Integer> authors = new HashMap<>(), sagas = new HashMap<>(), genres = new HashMap<>();
        for (Book b : store.snapshot()) {
            for (String a : app.atlante.library.model.SearchIndex.authors(b)) authors.merge(a, 1, Integer::sum);
            if (!b.series.isEmpty()) sagas.merge(b.series, 1, Integer::sum);
            for (String g : app.atlante.library.saga.Genres.of(b)) genres.merge(g, 1, Integer::sum);
        }
        for (Object[] kind : new Object[][]{{"Autore", authors, filterAuthor}, {"Saga", sagas, filterSaga}, {"Genere", genres, filterGenre}}) {
            @SuppressWarnings("unchecked") Map<String, Integer> names = (Map<String, Integer>) kind[1];
            String best = null; int most = 0;
            for (Map.Entry<String, Integer> e : names.entrySet()) {
                List<String> nameWords = app.atlante.library.model.SearchIndex.words(e.getKey());
                boolean all = true;
                for (String w : words) { boolean found = false; for (String n : nameWords) if (n.startsWith(w)) { found = true; break; } if (!found) { all = false; break; } }
                if (all && e.getValue() > most && !e.getKey().equals(kind[2])) { most = e.getValue(); best = e.getKey(); }
            }
            if (best != null && most >= 2) return new String[]{(String) kind[0], best};
        }
        return null;
    }

    /** Genere: the normalized main genres of the library, with the number of books. */
    /**
     * Genere: a sheet with every main genre of the library and, under each, its sub-genres as written in the
     * sheets ("Epic Fantasy"), as chips. No numbers: a filter is a choice, not a statistic.
     */
    private void chooseGenre() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        if (sheet.getWindow() != null) app.atlante.library.ui.L.watch(sheet.getWindow().getDecorView());
        ScrollView scroll = new ScrollView(this); scroll.addView(genrePanel(sheet::dismiss)); scroll.setBackgroundColor(WHITE);
        sheet.setContentView(scroll); sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED); sheet.show();
    }

    LinearLayout genrePanel(Runnable close) {
        java.util.Set<String> present = new java.util.HashSet<>(); boolean other = false;
        Map<String, java.util.TreeSet<String>> subs = new HashMap<>();
        for (Book b : store.snapshot()) {
            java.util.List<String> gs = app.atlante.library.saga.Genres.of(b); present.addAll(gs); if (gs.isEmpty() && !b.genre.isEmpty()) other = true;
            String g = app.atlante.library.saga.Genres.mainOf(b);
            if (g != null && !b.genre.trim().equalsIgnoreCase(g) && !b.genre.matches(".*[,;/|].*") && b.genre.trim().length() <= 40)
                subs.computeIfAbsent(g, k -> new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(b.genre.trim());
        }
        LinearLayout p = column(); p.setPadding(dp(20), dp(12), dp(20), dp(28));
        p.addView(text("Genere", 22, INK, true));
        TextView hint = text("Tocca un genere: la biblioteca mostra solo quei libri.", 14, MUTED, false); hint.setPadding(0, dp(4), 0, dp(14)); p.addView(hint);
        java.util.function.BiFunction<String, Boolean, Chip> chip = (value, main) -> {
            boolean on = value.equals(filterGenre);
            Chip c = new Chip(this); c.setText(value.isEmpty() ? "Tutti i generi" : value); c.setCheckable(true); c.setChecked(on);
            c.setCheckedIconVisible(true); c.setEnsureMinTouchTargetSize(true);
            if (main) { c.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); c.setTextSize(15); }
            c.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(on ? blend(WHITE, SAGE, .18f) : WHITE));
            c.setChipStrokeColor(android.content.res.ColorStateList.valueOf(on ? SAGE : blend(LINE, SAGE, .2f))); c.setChipStrokeWidth(dp(1));
            c.setTextColor(on ? SAGE : main ? INK : blend(INK, MUTED, .35f)); if (!main) c.setTextSize(14); c.setCheckedIconTint(android.content.res.ColorStateList.valueOf(SAGE));
            c.setContentDescription((value.isEmpty() ? "Tutti i generi" : value) + (on ? ", selezionato" : ""));
            c.setOnClickListener(v -> { filterGenre = value; close.run(); applyLibraryFilters(); });
            return c;
        };
        com.google.android.material.chip.ChipGroup all = new com.google.android.material.chip.ChipGroup(this); all.addView(chip.apply("", true)); p.addView(all);
        for (String g : app.atlante.library.saga.Genres.INSTANCE.getMain()) {
            if (!present.contains(g)) continue;
            rule(p); gap(p, 10);
            com.google.android.material.chip.ChipGroup group = new com.google.android.material.chip.ChipGroup(this); group.setChipSpacingHorizontal(dp(8));
            group.addView(chip.apply(g, true));
            java.util.TreeSet<String> under = subs.get(g);
            if (under != null) for (String sub : under) group.addView(chip.apply(sub, false));
            p.addView(group); gap(p, 6);
        }
        if (other) { rule(p); gap(p, 10); com.google.android.material.chip.ChipGroup g = new com.google.android.material.chip.ChipGroup(this); g.addView(chip.apply(LibraryQuery.OTHER_GENRE, true)); p.addView(g); }
        return p;
    }

    /** Autore: one entry per person ("A & B" is two people), with a search field for long lists. */
    private void chooseAuthor() {
        TreeMap<String, Integer> count = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Book b : store.snapshot()) for (String a : app.atlante.library.model.SearchIndex.authors(b)) count.merge(a, 1, Integer::sum);
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(8), dp(20), 0);
        TextInputLayout box = new TextInputLayout(new ContextThemeWrapper(this, R.style.Atlante_Field)); box.setHint("Cerca un autore");
        TextInputEditText field = new TextInputEditText(box.getContext()); field.setSingleLine(true); box.addView(field); panel.addView(box);
        android.widget.ListView list = new android.widget.ListView(this);
        panel.addView(list, new LinearLayout.LayoutParams(-1, dp(360)));
        ArrayList<String> shown = new ArrayList<>();
        android.widget.ArrayAdapter<String> items = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(items);
        Runnable fill = () -> {
            List<String> words = app.atlante.library.model.SearchIndex.queryWords(field.getText() == null ? "" : field.getText().toString());
            shown.clear(); items.clear();
            if (words.isEmpty()) { shown.add(""); items.add("Tutti gli autori"); }
            for (Map.Entry<String, Integer> e : count.entrySet()) {
                List<String> nameWords = app.atlante.library.model.SearchIndex.words(e.getKey()); boolean all = true;
                for (String w : words) { boolean found = false; for (String n : nameWords) if (n.startsWith(w)) { found = true; break; } if (!found) { all = false; break; } }
                if (all) { shown.add(e.getKey()); items.add(e.getKey() + " · " + e.getValue() + (e.getKey().equals(filterAuthor) ? "  ✓" : "")); }
            }
            items.notifyDataSetChanged();
        };
        fill.run();
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { fill.run(); }
            public void afterTextChanged(Editable e) { }
        });
        androidx.appcompat.app.AlertDialog dialog = dialogBuilder().setTitle("Autore").setView(panel).setNegativeButton("Chiudi", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> { filterAuthor = shown.get(position); dialog.dismiss(); applyLibraryFilters(); });
        dialog.show();
    }

    private ArrayList<Book> filtered() {
        return LibraryQuery.select(store.snapshot(), destination.equals("Biblioteca") && tab.equals("Mappa") ? "" : query, destination.equals("Cerca") ? "Tutti i libri" : tab, filterGenre, filterFormat, sortOrder, filterSaga, filterAuthor, filterLanguage, filterDecade, filterUniverse);
    }

    private boolean hasFilters() { return !filterGenre.isEmpty() || !filterFormat.isEmpty() || !filterSaga.isEmpty() || !filterAuthor.isEmpty() || !filterLanguage.isEmpty() || !filterDecade.isEmpty() || !filterUniverse.isEmpty(); }
    private boolean hasMoreFilters() { return !filterFormat.isEmpty() || !filterSaga.isEmpty() || !filterLanguage.isEmpty() || !filterDecade.isEmpty() || !filterUniverse.isEmpty(); }
    private void clearFilters() { filterGenre = ""; filterFormat = ""; filterSaga = ""; filterAuthor = ""; filterLanguage = ""; filterDecade = ""; filterUniverse = ""; }

    private void libraryFilters() {
        LinearLayout panel = column(); panel.setPadding(dp(24), dp(12), dp(24), dp(4));
        // Normalized genres: "Epic Fantasy" and "Fantasy epico" are both Fantasy.
        LinkedHashSet<String> genres = new LinkedHashSet<>(); boolean other = false;
        TreeSet<String> sagas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Book book : store.snapshot()) {
            String main = app.atlante.library.saga.Genres.mainOf(book);
            if (main != null) genres.add(main); else if (!book.genre.isEmpty()) other = true;
            if (!book.series.isEmpty()) sagas.add(book.series);
            java.util.Set<String> known = LibraryQuery.graphSagas.get(book.id); if (known != null) sagas.addAll(known);   // sub-series and parent sagas too
        }
        TreeSet<String> universes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER); universes.addAll(LibraryQuery.graphUniverses.values());
        TreeSet<String> languageSet = new TreeSet<>(), decadeSet = new TreeSet<>();
        for (Book book : store.snapshot()) {
            String l = LibraryQuery.languageOf(book); if (!l.isEmpty()) languageSet.add(l);
            String d = LibraryQuery.decadeOf(book); if (!d.isEmpty()) decadeSet.add(d);
        }
        ArrayList<String> sagaOptions = new ArrayList<>(Arrays.asList("Tutte le saghe", LibraryQuery.IN_SAGA, LibraryQuery.SINGLE)); sagaOptions.addAll(sagas);
        Picker saga = new Picker(panel, "Saga o sotto-serie", sagaOptions, filterSaga);
        ArrayList<String> universeOptions = new ArrayList<>(); universeOptions.add("Tutti gli universi"); universeOptions.addAll(universes);
        Picker universe = new Picker(panel, "Universo narrativo", universeOptions, filterUniverse);
        ArrayList<String> languageOptions = new ArrayList<>(); languageOptions.add("Tutte le lingue"); languageOptions.addAll(languageSet);
        Picker language = new Picker(panel, "Lingua", languageOptions, filterLanguage);
        ArrayList<String> decadeOptions = new ArrayList<>(); decadeOptions.add("Tutti gli anni"); decadeOptions.addAll(decadeSet);
        Picker decade = new Picker(panel, "Anno di pubblicazione", decadeOptions, filterDecade);
        Picker format = new Picker(panel, "Formato ed edizioni", Arrays.asList("Tutti i formati", "EPUB", "TXT", "CBZ", "AZW3", "CATALOGO", LibraryQuery.MANY_EDITIONS), filterFormat);
        Picker order = new Picker(panel, "Ordina per", Arrays.asList("Titolo", "Autore", "Saga"), sortOrder);
        dialogBuilder().setTitle("Altri filtri e ordine").setView(scrolled(panel))   // scrolls: "Ordina per" is never squeezed
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Azzera", (d, w) -> { clearFilters(); applyLibraryFilters(); })
            .setPositiveButton("Applica", (d, w) -> {
                filterLanguage = language.position() == 0 ? "" : language.value();
                filterDecade = decade.position() == 0 ? "" : decade.value();
                filterFormat = format.position() == 0 ? "" : format.value();
                filterSaga = saga.position() == 0 ? "" : saga.value();
                filterUniverse = universe.position() == 0 ? "" : universe.value();
                sortOrder = order.value(); applyLibraryFilters();
            }).show();
    }

    private void applyLibraryFilters() {
        getPreferences(MODE_PRIVATE).edit().putString("filterGenre", filterGenre).putString("filterAuthor", filterAuthor)
            .putString("filterLanguage", filterLanguage).putString("filterDecade", filterDecade).putString("filterUniverse", filterUniverse)
            .putString("filterFormat", filterFormat).putString("filterSaga", filterSaga).putString("sortOrder", sortOrder).apply();
        showLibrary();
    }

    private void renderContent() {
        if (body == null || (!destination.equals("Biblioteca") && !destination.equals("Cerca")) || detailId != null) return;
        ArrayList<Book> list = filtered();
        notice.setText(busy ? "Importazione in corso…" : list.size() + " di " + store.snapshot().size() + " libri · Sul dispositivo · Lettura offline");
        if (!list.isEmpty() && (destination.equals("Cerca") || (!tab.equals("Mappa") && !tab.equals("Autori") && !tab.equals("Saghe") && !tab.equals("Universi")))) { showGrid(list); return; }
        // Empty states, map and indexes are short pages: a plain scrolling column.
        body.removeAllViews(); grid = null; adapter = null;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        content = column(); content.setPadding(dp(14), dp(16), dp(14), dp(28)); scroll.addView(content);
        if (list.isEmpty()) {
            gap(content, 32);
            content.addView(text(store.snapshot().isEmpty() ? "Ogni libro è un punto di partenza." : "Nessun libro trovato.", 30, INK, true));
            gap(content, 16);
            content.addView(text(store.snapshot().isEmpty() ? "Apri il menu ☰ per cercare nuovi titoli online o importare EPUB, TXT e CBZ. Tutte le tue letture, anche su carta, trovano posto qui." : "Prova un altro titolo, autore o nome di saga.", 16, MUTED, false));
            gap(content, 24);
            if (hasFilters()) content.addView(button("Azzera filtri", false, () -> { clearFilters(); applyLibraryFilters(); }));
            if (store.snapshot().isEmpty()) content.addView(button("Cerca il tuo primo libro", true, this::onlineSearch));
            return;
        }
        if (destination.equals("Biblioteca") && tab.equals("Mappa")) { showMap(list); return; }
        if (destination.equals("Biblioteca") && tab.equals("Saghe")) { if (openSaga.isEmpty()) showSagas(list); else showSaga(list, openSaga); return; }
        if (destination.equals("Biblioteca") && tab.equals("Autori")) { showIndex(list, true); return; }
        if (destination.equals("Biblioteca") && tab.equals("Universi")) { content.addView(app.atlante.library.ui.compose.UniverseIndexView.create(this, palette, query, sagaHost)); return; }
    }

    private int columns() {
        int width = getResources().getConfiguration().screenWidthDp;
        if (listView) return 1;
        int density = density();
        return width >= 1100 ? density + 3 : width >= 760 ? density + 2 : width >= 550 ? density + 1 : density;
    }
    /** Covers per row on a phone: 3, 4 (default) or 5, chosen above the grid. */
    private int density() { int n = getPreferences(MODE_PRIVATE).getInt("gridColumns", 4); return n < 3 || n > 5 ? 4 : n; }

    /** Book grid: a RecyclerView keeps only the visible cards in memory, however large the library. */
    private void showGrid(List<Book> list) {
        int count = list.size();
        if (shelfView) {
            // Bookcase: spines on shelves (Compose); same filtered and sorted list as grid and list views.
            body.removeAllViews(); grid = null; adapter = null; content = null; shownCount = count;
            int total = store.snapshot().size();
            String header = (count == total ? total + (total == 1 ? " libro" : " libri") : count + " di " + total + " libri") + " · ordinati per " + sortOrder.toLowerCase(Locale.ROOT) + " · tieni premuto un dorso per le azioni";
            body.addView(app.atlante.library.ui.compose.ShelfView.create(this, palette, list, header, getPreferences(MODE_PRIVATE), motion(), new app.atlante.library.ui.compose.ShelfView.Host() {
                @Override public void open(String id) { Book b = store.find(id); if (b != null) details(b); }
                @Override public void actions(String id) { Book b = store.find(id); if (b != null) bookActions(b); }
            }), new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        if (grid == null) {
            body.removeAllViews(); content = null;
            grid = new RecyclerView(this); grid.setClipToPadding(false);
            grid.setPadding(dp(8), dp(2), dp(8), dp(28));
            adapter = new BookAdapter(new BookAdapter.Cards() {
                @Override public View header() { return gridHeader(); }
                @Override public View card(Book book) { return bookCard(book); }
                @Override public View rebind(View card, Book book) { return !listView && card.getTag() instanceof CoverCell && ((CoverCell) card.getTag()).columns == density() ? bindCoverCell((CoverCell) card.getTag(), book) : null; }
            });
            GridLayoutManager manager = new GridLayoutManager(this, columns());
            manager.setSpanSizeLookup(adapter.spans(manager));
            grid.setLayoutManager(manager); grid.setAdapter(adapter);
            body.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        }
        shownCount = count;
        // A new tab, search or filter starts from the top; edits to a book keep the scroll position.
        String key = tab + "\n" + query + "\n" + filterGenre + "\n" + filterFormat + "\n" + filterSaga + "\n" + sortOrder + "\n" + filterAuthor + "\n" + filterLanguage + "\n" + filterDecade + "\n" + filterUniverse;
        boolean changed = !key.equals(gridKey); gridKey = key;
        adapter.submit(list);
        if (changed) grid.scrollToPosition(0);
        android.os.Parcelable restored = libraryPositions.remove(destination);
        if (restored != null) grid.getLayoutManager().onRestoreInstanceState(restored);
    }
    private int shownCount;

    /** A plain bar, as in the designs: no gap between done and to do, no dot at the end. */
    static void solid(LinearProgressIndicator bar) { bar.setIndicatorTrackGapSize(0); bar.setTrackStopIndicatorSize(0); }
    private LinearProgressIndicator progressBar(Book b) {
        LinearProgressIndicator bar = new LinearProgressIndicator(this);
        if (b.totalPages > 0) { bar.setMax(b.totalPages); bar.setProgressCompat(Math.min(b.currentPage, b.totalPages), false); }
        else { bar.setMax(100); bar.setProgressCompat(b.percent, false); }
        bar.setTrackCornerRadius(dp(4)); bar.setTrackThickness(dp(6)); solid(bar);
        return bar;
    }

    private View gridHeader() {
        LinearLayout header = row(); header.setPadding(dp(6), 0, dp(2), dp(10));
        int total = store.snapshot().size();
        header.addView(text(busy ? "Importazione in corso…" : (shownCount == total ? total + (total == 1 ? " libro" : " libri")
            : shownCount + " di " + total + " libri") + " · per " + sortOrder.toLowerCase(Locale.ROOT), 13, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        if (!listView) {
            // Density: 3 · 4 · 5 covers per row, in a small segmented control.
            LinearLayout seg = row(); seg.setPadding(dp(2), dp(2), dp(2), dp(2)); GradientDrawable g = background(Color.TRANSPARENT, LINE); g.setCornerRadius(dp(16)); seg.setBackground(g);
            ImageView icon = new ImageView(this); icon.setImageResource(R.drawable.ic_grid); icon.setColorFilter(SAGE); LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(16), dp(16)); ip.setMargins(dp(4), 0, dp(4), 0); seg.addView(icon, ip);
            for (int n = 3; n <= 5; n++) { int value = n; boolean on = density() == n;
                TextView t = text(Integer.toString(n), 12, on ? PAPER : SAGE, false); t.setGravity(Gravity.CENTER); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                GradientDrawable b = background(on ? INK : Color.TRANSPARENT, Color.TRANSPARENT); b.setCornerRadius(dp(13)); t.setBackground(b);
                t.setContentDescription(n + " copertine per riga" + (on ? ", selezionato" : ""));
                t.setOnClickListener(v -> { getPreferences(MODE_PRIVATE).edit().putInt("gridColumns", value).apply(); grid = null; renderContent(); });
                seg.addView(t, new LinearLayout.LayoutParams(dp(30), dp(26))); }
            header.addView(seg);
        }
        return header;
    }
    private void chooseView() {
        String[] modes = {"Scaffale", "Griglia", "Elenco"};
        dialogBuilder().setTitle("Vista biblioteca").setSingleChoiceItems(modes, shelfView ? 0 : listView ? 2 : 1, (dialog, which) -> {
            shelfView = which == 0; listView = which == 2;
            getPreferences(MODE_PRIVATE).edit().putBoolean("listView", listView).putBoolean("shelfView", shelfView).apply();
            dialog.dismiss(); showLibrary();
        }).setNegativeButton("Chiudi", null).show();
    }

    private View bookCard(Book b) {
        if (!listView) return coverCard(b);
        return listRow(b);
    }
    /** List view (as in the library proposals): one compact row per book, cover, title, author, state and format. */
    private View listRow(Book b) {
        LinearLayout line = row(); line.setGravity(Gravity.CENTER_VERTICAL); line.setPadding(dp(8), dp(8), dp(8), dp(8));
        line.addView(coverView(b, dp(48), dp(72)), new LinearLayout.LayoutParams(dp(48), dp(72)));
        LinearLayout info = column(); info.setPadding(dp(14), 0, dp(8), 0); line.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView t = text(b.title, 16, INK, true); t.setMaxLines(1); t.setEllipsize(TextUtils.TruncateAt.END); info.addView(t);
        TextView a = text(b.author, 13, MUTED, false); a.setMaxLines(1); a.setEllipsize(TextUtils.TruncateAt.END); info.addView(a);
        boolean going = b.status.equals(Book.READING) || b.status.equals(Book.PAUSED);
        String state = going ? b.status + " · " + progress(b) + "%" : b.status.equals(Book.READ) ? "Letto" : !b.series.isEmpty() ? b.series + order(b) : "Da leggere";
        TextView st = text(state, 12, b.status.equals(Book.PAUSED) ? COPPER : going || b.status.equals(Book.READ) ? SAGE : MUTED, false); st.setMaxLines(1); st.setEllipsize(TextUtils.TruncateAt.END); st.setPadding(0, dp(3), 0, 0); info.addView(st);
        String fmt = b.format.equals("CATALOGO") ? (b.medium.isEmpty() ? "" : b.medium.toUpperCase(Locale.ROOT)) : b.format;
        if (!fmt.isEmpty()) { TextView f = text(fmt, 11, MUTED, false); f.setPadding(dp(6), dp(2), dp(6), dp(2)); GradientDrawable g = background(Color.TRANSPARENT, LINE); g.setCornerRadius(dp(6)); f.setBackground(g); line.addView(f); }
        View rule = new View(this); rule.setBackgroundColor(LINE);
        LinearLayout cell = column(); cell.addView(line, new LinearLayout.LayoutParams(-1, -2)); cell.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
        cell.setContentDescription(b.title + ", " + b.author + ", " + state); cell.setOnClickListener(v -> details(b)); cell.setOnLongClickListener(v -> { bookActions(b); return true; });
        return cell;
    }
    /** The views of a grid cell, kept so a recycled cell is refreshed instead of rebuilt while scrolling. */
    private static final class CoverCell { LinearLayout cell; FrameLayout art; ImageView image; TextView fallback, number, tick, title, author; LinearProgressIndicator bar; int columns, width, height; }
    private View coverCard(Book b) {
        CoverCell c = new CoverCell(); c.columns = density();
        int cols = c.columns, screen = getResources().getConfiguration().screenWidthDp, gutter = cols == 5 ? 6 : cols == 4 ? 8 : 12;
        int shown = columns(); c.width = Math.max(48, (screen - 16 - shown * gutter) / shown); c.height = c.width * 3 / 2;
        c.cell = column(); c.cell.setPadding(dp(gutter / 2f), 0, dp(gutter / 2f), dp(cols == 5 ? 10 : 14)); c.cell.setTag(c);
        c.art = new FrameLayout(this); c.art.setClipToOutline(true); GradientDrawable shape = background(LINE, Color.TRANSPARENT); shape.setCornerRadius(dp(6)); c.art.setBackground(shape); c.art.setElevation(dp(3));
        c.cell.addView(c.art, new LinearLayout.LayoutParams(-1, dp(c.height)));
        c.fallback = text("", 11, SAGE, true); c.fallback.setPadding(dp(6), dp(26), dp(6), dp(8));   // below the saga number and the tick c.art.addView(c.fallback, new FrameLayout.LayoutParams(-1, -1));
        c.image = new ImageView(this); c.image.setScaleType(ImageView.ScaleType.CENTER_CROP); c.art.addView(c.image, new FrameLayout.LayoutParams(-1, -1));
        c.number = text("", 10, PAPER, false); c.number.setGravity(Gravity.CENTER); c.number.setPadding(dp(4), 0, dp(4), 0); c.number.setMinWidth(dp(18));
        GradientDrawable ng = background(INK, Color.TRANSPARENT); ng.setCornerRadius(dp(9)); c.number.setBackground(ng);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, dp(18), Gravity.TOP | Gravity.START); nl.setMargins(dp(4), dp(4), 0, 0); c.art.addView(c.number, nl);
        c.tick = text("✓", 11, ON_ACCENT, false); c.tick.setGravity(Gravity.CENTER); GradientDrawable tg = background(SAGE, Color.TRANSPARENT); tg.setCornerRadius(dp(9)); c.tick.setBackground(tg);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.END); tl.setMargins(0, dp(4), dp(4), 0); c.art.addView(c.tick, tl);
        c.bar = new LinearProgressIndicator(this); solid(c.bar); c.bar.setTrackThickness(dp(4)); c.bar.setTrackCornerRadius(0); c.bar.setTrackColor(0x33000000 | (INK & 0x00FFFFFF));
        c.art.addView(c.bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        if (cols < 5) { gap(c.cell, 5); c.title = text("", cols == 3 ? 14 : 12, INK, true); c.title.setMaxLines(cols == 3 ? 2 : 1); c.title.setEllipsize(TextUtils.TruncateAt.END); c.cell.addView(c.title); }
        if (cols == 3) { c.author = text("", 11, MUTED, false); c.author.setMaxLines(1); c.author.setEllipsize(TextUtils.TruncateAt.END); c.cell.addView(c.author); }
        return bindCoverCell(c, b);
    }
    /**
     * Grid cell (design 3a): the cover is the card. Saga number top left, a tick when read, a thin progress bar
     * (accent while reading, copper when paused); the title under it at 3 and 4 columns, the author at 3.
     */
    private View bindCoverCell(CoverCell c, Book b) {
        c.fallback.setText(b.title); c.image.setContentDescription("Copertina di " + b.title);
        covers.thumbnail(b.id, store.cover(b), Math.max(dp(c.width), dp(c.height)), c.image, c.fallback);
        c.number.setVisibility(b.number > 0 ? View.VISIBLE : View.GONE); if (b.number > 0) c.number.setText(number(b.number));
        c.tick.setVisibility(b.status.equals(Book.READ) ? View.VISIBLE : View.GONE);
        boolean going = b.status.equals(Book.READING) || b.status.equals(Book.PAUSED);
        c.bar.setVisibility(going ? View.VISIBLE : View.GONE);
        if (going) { c.bar.setIndicatorColor(b.status.equals(Book.PAUSED) ? COPPER : SAGE);
            if (b.totalPages > 0) { c.bar.setMax(b.totalPages); c.bar.setProgressCompat(Math.min(b.currentPage, b.totalPages), false); } else { c.bar.setMax(100); c.bar.setProgressCompat(b.percent, false); } }
        if (c.title != null) c.title.setText(b.title);
        if (c.author != null) c.author.setText(b.author);
        c.cell.setContentDescription(b.title + ", " + b.author + ", " + b.status + (b.status.equals(Book.READING) ? " " + progress(b) + "%" : ""));
        c.cell.setOnClickListener(v -> details(b)); c.cell.setOnLongClickListener(v -> { bookActions(b); return true; });
        return c.cell;
    }

    static String order(Book b) { return b.number > 0 ? " · n. " + number(b.number) : ""; }
    public static String number(double number) { return number == (long)number ? Long.toString((long)number) : Double.toString(number); }
    private void forgetCover(String id) { covers.forget(id); app.atlante.library.ui.compose.CoverImages.forgetBook(id); }
    private Bitmap cover(Book b) { return covers.large(b.id, store.cover(b)); }
    static Bitmap decode(String file, byte[] bytes, int max) { return Covers.decode(file, bytes, max); }

    private String openSaga = "", sagaFilter = "Tutte";
    /** The owned books of one saga, in reading order (books without a number at the end). */
    private static ArrayList<Book> sagaBooks(List<Book> list, String name) {
        ArrayList<Book> books = new ArrayList<>(); for (Book b : list) if (b.series.equalsIgnoreCase(name)) books.add(b);
        books.sort((a, b) -> Double.compare(a.number > 0 ? a.number : 9999, b.number > 0 ? b.number : 9999)); return books;
    }
    /** Positions of a saga: owned books by number, the gaps up to the highest number as missing (null). */
    private static ArrayList<Book> sagaSlots(ArrayList<Book> books) {
        int max = 0; for (Book b : books) if (b.number > 0 && b.number == Math.floor(b.number)) max = Math.max(max, (int) b.number);
        ArrayList<Book> slots = new ArrayList<>(); for (int n = 1; n <= Math.min(max, 60); n++) { Book at = null; for (Book b : books) if (b.number == n) { at = b; break; } slots.add(at); }
        for (Book b : books) if (!slots.contains(b)) slots.add(b);
        return slots;
    }
    private String sagaState(ArrayList<Book> books) {
        int read = 0; boolean started = false; for (Book b : books) { if (b.status.equals(Book.READ)) read++; if (b.status.equals(Book.READING) || b.status.equals(Book.PAUSED)) started = true; }
        return read == books.size() && !sagaSlots(books).contains(null) ? "Complete" : read == 0 && !started ? "Da iniziare" : "In corso";
    }
    private int stateColor(Book b) { return b == null ? Color.TRANSPARENT : b.status.equals(Book.READ) ? SAGE : b.status.equals(Book.READING) || b.status.equals(Book.PAUSED) ? COPPER : blend(SAGE, WHITE, .45f); }
    /** Design 7b: each saga as a card with a bar per volume (read, reading, owned, missing) and what comes next. */
    private void showSagas(List<Book> list) {
        content.setPadding(dp(16), dp(4), dp(16), dp(28));
        TreeMap<String, ArrayList<Book>> sagas = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Book b : list) if (!b.series.isEmpty()) sagas.computeIfAbsent(b.series, k -> new ArrayList<>());
        for (String name : new ArrayList<>(sagas.keySet())) sagas.put(name, sagaBooks(list, name));
        HorizontalScrollView strip = new HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false); strip.setPadding(0, 0, 0, dp(14));
        LinearLayout chips = row(); strip.addView(chips); content.addView(strip);
        for (String f : new String[]{"Tutte", "In corso", "Da iniziare", "Complete"}) {
            int n = 0; for (ArrayList<Book> books : sagas.values()) if (f.equals("Tutte") || sagaState(books).equals(f)) n++;
            TextView chip = pillChip(f, Integer.toString(n), f.equals(sagaFilter)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36)); lp.setMargins(chips.getChildCount() == 0 ? 0 : dp(8), 0, 0, 0);
            chips.addView(chip, lp); chip.setOnClickListener(v -> { sagaFilter = f; renderContent(); });
        }
        if (sagas.isEmpty()) { content.addView(text("Nessuna saga ancora. Indica saga e numero nella scheda di un libro, o usa «Riconosci saghe e ordine».", 15, MUTED, false)); content.addView(button("Riconosci saghe e ordine", false, this::recognizeSeries)); return; }
        for (Map.Entry<String, ArrayList<Book>> e : sagas.entrySet()) {
            ArrayList<Book> books = e.getValue(); if (!sagaFilter.equals("Tutte") && !sagaState(books).equals(sagaFilter)) continue;
            ArrayList<Book> slots = sagaSlots(books); int read = 0; for (Book b : books) if (b.status.equals(Book.READ)) read++;
            LinearLayout box = column(); box.setPadding(dp(16), dp(16), dp(16), dp(16)); MaterialCardView c = card(box); c.setRadius(dp(20));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.setMargins(0, 0, 0, dp(12)); content.addView(c, cl);
            LinearLayout top = row(); top.setGravity(Gravity.TOP); box.addView(top, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout names = column(); top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            names.addView(text(e.getKey(), 18, INK, true)); names.addView(text(books.get(0).author, 13, MUTED, false));
            top.addView(text(read + "/" + slots.size(), 20, SAGE, true));
            gap(box, 12); box.addView(segments(slots, dp(8)), new LinearLayout.LayoutParams(-1, dp(8)));
            Book current = null, next = null; int missing = 0;
            for (Book b : books) { if (current == null && (b.status.equals(Book.READING) || b.status.equals(Book.PAUSED))) current = b; if (next == null && b.status.equals(Book.TO_READ)) next = b; }
            for (int i = 0; i < slots.size(); i++) if (slots.get(i) == null) { missing = i + 1; break; }
            LinearLayout nextLine = row(); nextLine.setPadding(0, dp(12), 0, 0); box.addView(nextLine, new LinearLayout.LayoutParams(-1, -2));
            String label = current != null ? (current.status.equals(Book.PAUSED) ? "In pausa: " : "Stai leggendo: ") : next != null ? "Prossimo: " : missing > 0 ? "Manca: " : "";
            nextLine.addView(text(label, 13, MUTED, false));
            TextView what = text(current != null ? current.title : next != null ? next.title : missing > 0 ? "n. " + missing : "Saga completa", 13, current != null ? COPPER : next != null ? SAGE : MUTED, false);
            what.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); what.setMaxLines(1); what.setEllipsize(TextUtils.TruncateAt.END); nextLine.addView(what, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView chevron = new ImageView(this); chevron.setImageResource(R.drawable.ic_chevron_right); chevron.setColorFilter(MUTED); nextLine.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
            String name = e.getKey(); box.setOnClickListener(v -> { openSaga = name; showLibrary(); });
            box.setContentDescription(name + ", " + read + " letti su " + slots.size() + ". " + label + what.getText());
        }
    }
    /** One bar per position: read, reading, owned, missing (dashed outline). */
    private View segments(List<Book> slots, int height) {
        LinearLayout bar = row();
        for (int i = 0; i < slots.size(); i++) { View seg = new View(this); Book b = slots.get(i);
            GradientDrawable g = background(stateColor(b), Color.TRANSPARENT); g.setCornerRadius(height / 2f); if (b == null) g.setStroke(dp(1.5f), LINE, dp(4), dp(3)); seg.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1); if (i > 0) lp.setMargins(dp(3), 0, 0, 0); bar.addView(seg, lp); }
        return bar;
    }
    /** Order of the saga page: by the saga's numbers, or by year of publication. */
    private String sagaOrder = "Saga";
    /** The narrative universe of a saga, from the saga graph ("" when none is known). */
    private String sagaUniverse(String saga) {
        for (Book b : store.snapshot()) if (b.series.equalsIgnoreCase(saga)) { String u = LibraryQuery.graphUniverses.get(b.id); if (u != null && !u.isEmpty()) return u; }
        return "";
    }
    private static boolean bothRead(Book a, Book b) { return a != null && b != null && a.status.equals(Book.READ) && b.status.equals(Book.READ); }
    private static int yearOf(Book b) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{4}").matcher(b == null ? "" : b.year); return m.find() ? Integer.parseInt(m.group()) : Integer.MAX_VALUE; }
    /**
     * Design 7b: the saga as a vertical map. Number, cover, title and state for each volume, joined by a line (accent
     * between two volumes read); the one being read outlined in copper; missing ones can be searched. Order by the
     * saga or by publication; the other sagas of the same universe at the end.
     */
    private void showSaga(List<Book> list, String name) {
        content.setPadding(dp(16), dp(4), dp(16), dp(28));
        ArrayList<Book> books = sagaBooks(store.snapshot(), name); ArrayList<Book> slots = sagaSlots(books);
        if (books.isEmpty()) { openSaga = ""; showSagas(list); return; }
        int read = 0; for (Book b : books) if (b.status.equals(Book.READ)) read++;
        TextView sub = text(books.get(0).author + " · " + read + " letti, " + books.size() + " in biblioteca, " + slots.size() + " in tutto", 13, MUTED, false); sub.setPadding(dp(4), 0, dp(4), dp(12)); content.addView(sub);
        // Saga · Pubblicazione, as a small segmented control.
        LinearLayout seg = row(); seg.setPadding(dp(3), dp(3), dp(3), dp(3)); GradientDrawable sg = background(LINE, Color.TRANSPARENT); sg.setCornerRadius(dp(20)); seg.setBackground(sg);
        LinearLayout.LayoutParams segSize = new LinearLayout.LayoutParams(-1, dp(40)); segSize.setMargins(0, 0, 0, dp(10)); content.addView(seg, segSize);
        for (String o : new String[]{"Saga", "Pubblicazione"}) { boolean on = o.equals(sagaOrder);
            TextView t = text(o, 13, on ? INK : MUTED, false); t.setGravity(Gravity.CENTER); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            GradientDrawable g = background(on ? WHITE : Color.TRANSPARENT, Color.TRANSPARENT); g.setCornerRadius(dp(17)); t.setBackground(g); if (on) t.setElevation(dp(1));
            t.setContentDescription("Ordina per " + o.toLowerCase(Locale.ITALIAN) + (on ? ", selezionato" : "")); t.setOnClickListener(v -> { sagaOrder = o; renderContent(); });
            seg.addView(t, new LinearLayout.LayoutParams(0, -1, 1)); }
        LinearLayout legend = row(); legend.setPadding(dp(4), 0, dp(4), dp(12)); content.addView(legend);
        String[] names = {"Letto", "In lettura", "In biblioteca", "Mancante"}; int[] colors = {SAGE, COPPER, blend(SAGE, WHITE, .45f), Color.TRANSPARENT};
        for (int i = 0; i < 4; i++) { View dot = new View(this); GradientDrawable g = background(colors[i], Color.TRANSPARENT); g.setCornerRadius(dp(5)); if (i == 3) g.setStroke(dp(1.5f), MUTED, dp(3), dp(2)); dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(10), dp(10)); lp.setMargins(i == 0 ? 0 : dp(12), 0, dp(5), 0); legend.addView(dot, lp); legend.addView(text(names[i], 13, MUTED, false)); }
        // Each position keeps its saga number; by publication the volumes with a year come first, oldest first.
        ArrayList<Integer> order = new ArrayList<>(); for (int i = 0; i < slots.size(); i++) order.add(i);
        if (sagaOrder.equals("Pubblicazione")) order.sort((x, y) -> Integer.compare(yearOf(slots.get(x)), yearOf(slots.get(y))));
        LinearLayout box = column(); box.setPadding(dp(14), dp(4), dp(14), dp(4)); MaterialCardView c = card(box); c.setRadius(dp(20)); content.addView(c, new LinearLayout.LayoutParams(-1, -2));
        for (int k = 0; k < order.size(); k++) { int i = order.get(k); Book b = slots.get(i); int n = i + 1;
            Book before = k > 0 ? slots.get(order.get(k - 1)) : null, after = k + 1 < order.size() ? slots.get(order.get(k + 1)) : null;
            LinearLayout line = row(); line.setGravity(Gravity.TOP); line.setMinimumHeight(dp(96)); box.addView(line, new LinearLayout.LayoutParams(-1, -2));
            FrameLayout art = new FrameLayout(this); line.addView(art, new LinearLayout.LayoutParams(dp(54), -1));
            // The line through the volumes, cover to cover: accent between two read volumes, hairline otherwise.
            if (k > 0) { View up = new View(this); up.setBackgroundColor(bothRead(before, b) ? SAGE : LINE); FrameLayout.LayoutParams ul = new FrameLayout.LayoutParams(dp(2), dp(45), Gravity.START); ul.setMargins(dp(27), 0, 0, 0); art.addView(up, ul); }
            if (after != null || k + 1 < order.size()) { View down = new View(this); down.setBackgroundColor(bothRead(b, after) ? SAGE : LINE); FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(dp(2), -1, Gravity.START); dl.setMargins(dp(27), dp(45), 0, 0); art.addView(down, dl); }
            FrameLayout.LayoutParams coverAt = new FrameLayout.LayoutParams(dp(44), dp(66), Gravity.TOP | Gravity.END); coverAt.setMargins(0, dp(12), dp(4), dp(12));
            if (b != null) art.addView(coverView(b, dp(44), dp(66)), coverAt);
            else { View empty = new View(this); GradientDrawable g = background(WHITE, Color.TRANSPARENT); g.setCornerRadius(dp(5)); g.setStroke(dp(1.5f), MUTED, dp(4), dp(3)); empty.setBackground(g); art.addView(empty, coverAt); }
            if (b != null && (b.status.equals(Book.READING) || b.status.equals(Book.PAUSED))) {
                View ring = new View(this); GradientDrawable g = background(Color.TRANSPARENT, Color.TRANSPARENT); g.setCornerRadius(dp(7)); g.setStroke(dp(2.5f), COPPER); ring.setBackground(g); ring.setElevation(dp(4));
                FrameLayout.LayoutParams ra = new FrameLayout.LayoutParams(dp(50), dp(72), Gravity.TOP | Gravity.END); ra.setMargins(0, dp(9), dp(1), 0); art.addView(ring, ra); }
            String num = b != null && b.number > 0 ? number(b.number) : b == null ? Integer.toString(n) : "–";
            TextView badge = text(num, 11, b == null ? MUTED : ON_ACCENT, false); badge.setGravity(Gravity.CENTER); badge.setTypeface(Typeface.DEFAULT_BOLD); badge.setPadding(dp(5), 0, dp(5), 0); badge.setMinWidth(dp(20)); badge.setElevation(dp(5));
            GradientDrawable bg = background(b == null ? WHITE : stateColor(b), b == null ? MUTED : Color.TRANSPARENT); bg.setCornerRadius(dp(10)); badge.setBackground(bg);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, dp(20), Gravity.TOP | Gravity.START); bl.setMargins(0, dp(6), 0, 0); art.addView(badge, bl);
            LinearLayout info = column(); info.setPadding(dp(14), dp(22), dp(8), dp(12)); line.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            String year = b == null || b.year.isEmpty() ? "" : " · " + b.year;
            if (b != null) {
                TextView t = text(b.title, 15, INK, false); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); info.addView(t);
                String state = b.status.equals(Book.READ) ? "Letto" : b.status.equals(Book.TO_READ) ? "In biblioteca · da leggere" : b.status + " · " + progress(b) + "%";
                info.addView(text(state + year, 12, b.status.equals(Book.READ) ? SAGE : b.status.equals(Book.TO_READ) ? MUTED : COPPER, false));
                if (b.status.equals(Book.READING) || b.status.equals(Book.PAUSED)) { LinearProgressIndicator p = progressBar(b); p.setIndicatorColor(COPPER); p.setTrackThickness(dp(4)); LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(160), -2); pl.setMargins(0, dp(8), 0, 0); info.addView(p, pl); }
                line.setOnClickListener(v -> details(b));
                line.setContentDescription(num + ". " + b.title + ", " + state + year);
            } else {
                info.addView(text("Volume " + n, 15, MUTED, false)); info.addView(text("Non in biblioteca", 12, MUTED, false));
                MaterialButton find = button("Cerca", R.style.Atlante_Button_Text, () -> { lastOnlineQuery = name + " " + n; prefillOnline = true; onlineSearch(); }); find.setContentDescription("Cerca il volume " + n + " di " + name);
                LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-2, -2); fl.gravity = Gravity.CENTER_VERTICAL; line.addView(find, fl);
            }
        }
        // The other sagas of the same narrative universe (a tap opens their map).
        String universe = sagaUniverse(name);
        if (!universe.isEmpty()) {
            TreeMap<String, ArrayList<Book>> others = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Book b : store.snapshot()) if (!b.series.isEmpty() && !b.series.equalsIgnoreCase(name) && universe.equalsIgnoreCase(LibraryQuery.graphUniverses.get(b.id))) others.computeIfAbsent(b.series, x -> new ArrayList<>());
            for (String o : new ArrayList<>(others.keySet())) others.put(o, sagaBooks(store.snapshot(), o));
            if (!others.isEmpty()) {
                LinearLayout uni = column(); uni.setPadding(dp(16), dp(14), dp(16), dp(8)); MaterialCardView uc = card(uni); uc.setRadius(dp(20));
                LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-1, -2); ul.setMargins(0, dp(12), 0, 0); content.addView(uc, ul);
                TextView k = sectionLabel("STESSO UNIVERSO · " + universe.toUpperCase(Locale.ITALIAN)); k.setTextSize(11); k.setTextColor(COPPER); uni.addView(k);
                for (Map.Entry<String, ArrayList<Book>> e : others.entrySet()) { String other = e.getKey();
                    LinearLayout r = row(); r.setMinimumHeight(dp(44)); uni.addView(r, new LinearLayout.LayoutParams(-1, -2));
                    TextView arrow = text("- - ▸", 13, COPPER, false); r.addView(arrow);
                    TextView t = text(other, 15, INK, false); t.setPadding(dp(10), 0, dp(8), 0); t.setMaxLines(1); t.setEllipsize(TextUtils.TruncateAt.END); r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
                    r.addView(text(e.getValue().size() + "/" + sagaSlots(e.getValue()).size(), 13, MUTED, false));
                    r.setContentDescription(other + ", stesso universo: apri la saga"); r.setOnClickListener(v -> { openSaga = other; showLibrary(); }); }
            }
        }
        TextView note = text("Nessun numero inventato: i volumi mancanti vengono dai numeri dei tuoi libri. Ordine e saga si correggono dalla scheda.", 13, MUTED, false); note.setPadding(dp(6), dp(12), dp(6), 0); content.addView(note);
        content.addView(button("Riconosci saghe e ordine", R.style.Atlante_Button_Text, this::recognizeSeries));
    }

    private void showIndex(List<Book> list, boolean authors) {
        content.addView(text(authors ? "Ogni autore, un mondo." : "Una storia dopo l’altra.", 28, INK, true)); gap(content, 20);
        TreeMap<String, ArrayList<Book>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Book b : list) {
            String name = authors ? b.author : b.series.isEmpty() ? "Libri singoli" : b.series;
            groups.computeIfAbsent(name, key -> new ArrayList<>()).add(b);
        }
        for (Map.Entry<String, ArrayList<Book>> entry : groups.entrySet()) {
            LinearLayout box = column(); box.setPadding(dp(18), dp(18), dp(18), dp(18));
            box.setBackground(background(WHITE, LINE)); box.addView(text(entry.getKey(), 23, INK, true));
            gap(box, 5); box.addView(text(entry.getValue().size() + " titoli  ↗", 13, SAGE, false));
            box.setOnClickListener(v -> {
                LinearLayout panel = column(); panel.setPadding(dp(16), dp(16), dp(16), dp(16));
                ArrayList<Book> sorted = entry.getValue(); sorted.sort(Comparator.comparingDouble(b -> b.number));
                for (Book b : sorted) { panel.addView(button((b.number > 0 ? number(b.number) + "  " : "") + b.title, false, () -> details(b))); gap(panel, 8); }
                ScrollView scroll = new ScrollView(this); scroll.addView(panel);
                dialogBuilder().setTitle(entry.getKey()).setView(scroll).setNegativeButton("Chiudi", null).show();
            });
            content.addView(box); gap(content, 12);
        }
    }

    /** Authors shown on the map, honouring the library filters (but not the text search). */
    private ArrayList<String> mapAuthors() {
        // One entry per person ("Asimov, Isaac" = "Isaac Asimov"); co-authors each get their own entry.
        return new ArrayList<>(app.atlante.library.ui.MapGroups.authorNames(filtered()));
    }

    /** Design 7c: the authors as chips (initial + surname); the search button opens the full list. */
    private void mapHeader(LinearLayout header) {
        ArrayList<String> authors = mapAuthors();
        if (!authors.isEmpty() && !authors.contains(mapAuthor)) {
            String key = app.atlante.library.ui.MapGroups.authorKey(mapAuthor); mapAuthor = authors.get(0);
            for (String a : authors) if (app.atlante.library.ui.MapGroups.authorKey(a).equals(key)) { mapAuthor = a; break; }
        }
        HorizontalScrollView strip = new HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false); strip.setPadding(0, dp(4), 0, dp(4));
        LinearLayout chips = row(); strip.addView(chips); header.addView(strip, new LinearLayout.LayoutParams(-1, -2));
        ArrayList<String> shown = new ArrayList<>(); if (!mapAuthor.isEmpty()) shown.add(mapAuthor);
        for (String a : authors) { if (shown.size() >= 40) break; if (!shown.contains(a)) shown.add(a); }
        for (String a : shown) { boolean on = a.equals(mapAuthor);
            LinearLayout chip = row(); chip.setPadding(dp(4), 0, dp(14), 0); GradientDrawable g = background(on ? INK : Color.TRANSPARENT, on ? INK : LINE); g.setCornerRadius(dp(20)); chip.setBackground(g);
            TextView ini = text(initial(a), 13, INK, true); ini.setGravity(Gravity.CENTER); GradientDrawable ig = background(on ? PAPER : LINE, Color.TRANSPARENT); ig.setCornerRadius(dp(15)); ini.setBackground(ig); chip.addView(ini, new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView n = text(surnameOf(a), 14, on ? PAPER : INK, false); n.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); n.setPadding(dp(8), 0, 0, 0); chip.addView(n);
            chip.setContentDescription(a + (on ? ", selezionato" : "")); chip.setOnClickListener(v -> { mapAuthor = a; showLibrary(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40)); lp.setMargins(chips.getChildCount() == 0 ? 0 : dp(8), 0, 0, 0); chips.addView(chip, lp); }
    }
    private static String initial(String name) { String s = surnameOf(name); return s.isEmpty() ? "?" : s.substring(0, 1).toUpperCase(Locale.ITALIAN); }
    private void chooseMapAuthor() {
        ArrayList<String> authors = mapAuthors(); if (authors.isEmpty()) return;
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(8), dp(20), 0);
        TextInputLayout box = new TextInputLayout(new ContextThemeWrapper(this, R.style.Atlante_Field)); box.setHint("Cerca un autore");
        TextInputEditText field = new TextInputEditText(box.getContext()); field.setSingleLine(true); box.addView(field); panel.addView(box);
        android.widget.ListView list = new android.widget.ListView(this); panel.addView(list, new LinearLayout.LayoutParams(-1, dp(360)));
        ArrayList<String> shown = new ArrayList<>(); ArrayAdapter<String> items = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>()); list.setAdapter(items);
        Runnable fill = () -> { String q = field.getText() == null ? "" : field.getText().toString().trim().toLowerCase(Locale.ITALIAN); shown.clear(); items.clear();
            for (String a : authors) if (q.isEmpty() || a.toLowerCase(Locale.ITALIAN).contains(q)) { shown.add(a); items.add(a); } items.notifyDataSetChanged(); };
        fill.run();
        field.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence c, int a, int b, int d) { } public void onTextChanged(CharSequence c, int a, int b, int d) { fill.run(); } public void afterTextChanged(Editable e) { } });
        androidx.appcompat.app.AlertDialog dialog = dialogBuilder().setTitle("Autore della mappa").setView(panel).setNegativeButton("Chiudi", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> { mapAuthor = shown.get(position); dialog.dismiss(); showLibrary(); });
        dialog.show();
    }

    private void hideKeyboard(View view) {
        android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void showMap(ArrayList<Book> list) {
        content.setPadding(0, 0, 0, dp(24));
        ArrayList<Book> selected = app.atlante.library.ui.MapGroups.byAuthor(list, mapAuthor, 250);
        // Sagas recognized by the saga engine join the map; when they change the map is drawn again.
        app.atlante.library.saga.SagaIndex.refresh(this, () -> {
            if (!isFinishing() && !isDestroyed() && reading == null && detailId == null && destination.equals("Biblioteca") && tab.equals("Mappa")) renderContent();
        });
        if (selected.isEmpty()) {
            content.setPadding(dp(20), dp(24), dp(20), dp(24));
            content.addView(text("Scegli un autore dalla ricerca in alto.", 18, MUTED, false)); return;
        }
        content.addView(new ReadingMap(this, mapAuthor, selected, app.atlante.library.saga.SagaIndex.known(), this::details), new LinearLayout.LayoutParams(-1, -2));
        TextView hint = text("Autore → saga → libri nell’ordine di lettura. Tocca un libro per aprire la scheda."
            + (selected.size() == 250 ? " Sono mostrate le prime 250 opere." : ""), 13, MUTED, false);
        hint.setPadding(dp(20), dp(4), dp(20), 0); content.addView(hint);
        content.addView(button("Riconosci saghe e ordine",R.style.Atlante_Button_Text,this::recognizeSeries));
    }

    /** Saga section of the book page: opens owned books, searches the others, writes a saga the user picked. */
    private final app.atlante.library.ui.compose.SagaHost sagaHost = new app.atlante.library.ui.compose.SagaHost() {
        @Override public void openBook(String id) { Book x = store.find(id); if (x != null) details(x); }
        @Override public void searchOnline(String q) { searchFor(q); }
        @Override public void openWork(String title, String author) { openDiscovered(title, author, null); }
        @Override public void openWork(String title, String author, String workKey) { openDiscovered(title, author, workKey); }
        @Override public void applySeries(String id, String name, String ordinal, boolean chosen) {
            Book x = store.find(id); if (x == null || (!chosen && !x.series.isEmpty())) return;
            double n = 0; try { if (ordinal != null) n = Double.parseDouble(ordinal.replace(',', '.')); } catch (NumberFormatException ignored) { }
            if (x.series.equals(name) && (n == 0 || x.number == n)) return;
            String oldSeries = x.series; double oldNumber = x.number;
            x.series = name; if (n > 0 || chosen) x.number = n;
            try { store.replace(x); } catch (Exception e) { x.series = oldSeries; x.number = oldNumber; error(e); }
        }
        @Override public String coverFile(String id) { Book x = store.find(id); if (x == null) return null; File f = store.cover(x); return f.exists() ? f.getPath() : null; }
        @Override public java.util.List<Book> library() { return store.snapshot(); }
        @Override public boolean inLibrary(String title, String author) { return discoverMatch(title, author) != null; }
        @Override public boolean setToRead(String title, String author, String coverUrl, boolean on) {
            String key = title + "|" + author;
            if (!on) {
                String created = discoverSaved.remove(key);
                if (created == null) { boolean kept = discoverMatch(title, author) != null; if (kept) app.atlante.library.ui.L.toast(MainActivity.this, "Il libro era già nella tua biblioteca: resta lì", Toast.LENGTH_SHORT).show(); return kept; }
                Book b = store.find(created); try { if (b != null) store.remove(b); } catch (Exception e) { error(e); return true; } return false;
            }
            Book existing = discoverMatch(title, author);
            if (existing != null) { if (!discoverSaved.containsKey(key)) app.atlante.library.ui.L.toast(MainActivity.this, "«" + existing.title + "» è già nella tua biblioteca", Toast.LENGTH_SHORT).show(); return true; }
            Book b = new Book(); b.id = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            b.format = "CATALOGO"; b.title = title; b.author = author.isEmpty() ? "Autore sconosciuto" : author; b.status = Book.TO_READ; b.addedAt = System.currentTimeMillis();
            try { store.addCatalog(b); discoverSaved.put(key, b.id); } catch (Exception e) { error(e); return false; }
            if (coverUrl != null) fetchMissingCovers(new ArrayList<>(Arrays.asList(b)), false);
            return true;
        }
    };
    /** Records the Scopri bookmark created in this session (title|author → id): the only ones it may take back. */
    private final Map<String, String> discoverSaved = new HashMap<>();
    private Book discoverMatch(String title, String author) {
        Book probe = new Book(); probe.title = title; probe.author = author.isEmpty() ? "Autore sconosciuto" : author;
        String t = app.atlante.library.model.SearchIndex.normalize(title);
        for (Book b : store.snapshot()) if (app.atlante.library.model.SearchIndex.normalize(b.title).equals(t) && surnameOf(b.author).equalsIgnoreCase(surnameOf(probe.author))) return b;
        return null;
    }

    /** Book window: a Material bottom sheet with reading state, plot, chapters and the book's details. */
    private void details(Book b) {
        app.atlante.library.diagnostics.CrashLog.step("scheda del libro");
        if (detailId == null) {
            if (grid != null && grid.getLayoutManager() != null) libraryPositions.put(destination,grid.getLayoutManager().onSaveInstanceState());
            if (companionScroll != null) pagePositions.put(destination,companionScroll.getScrollY());
        }
        editingPage = false; onlineBook = null; sheetRequest++;
        detailId = b.id; reading = null; pages = null; timerLabel = null; uiHandler.removeCallbacks(timerTick);
        base(PAPER);
        androidx.coordinatorlayout.widget.CoordinatorLayout stage = new androidx.coordinatorlayout.widget.CoordinatorLayout(this);
        root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        com.google.android.material.appbar.AppBarLayout appbar = new com.google.android.material.appbar.AppBarLayout(this);
        appbar.setBackgroundColor(PAPER); appbar.setElevation(0);
        androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams appbarSize = new androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(-1,-2);
        stage.addView(appbar,appbarSize);
        com.google.android.material.appbar.CollapsingToolbarLayout collapse = new com.google.android.material.appbar.CollapsingToolbarLayout(this);
        // The title is already under the cover: the bar shows it only once the page is scrolled.
        collapse.setTitle(b.title); collapse.setExpandedTitleColor(Color.TRANSPARENT); collapse.setCollapsedTitleTextColor(INK);
        collapse.setExpandedTitleTypeface(Typeface.create("serif",Typeface.NORMAL)); collapse.setExpandedTitleMargin(dp(24),dp(20),dp(24),dp(20));
        collapse.setContentScrimColor(PAPER); collapse.setMinimumHeight(dp(56));
        com.google.android.material.appbar.AppBarLayout.LayoutParams collapseSize = new com.google.android.material.appbar.AppBarLayout.LayoutParams(-1,dp(72));
        collapseSize.setScrollFlags(com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL | com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED);
        appbar.addView(collapse,collapseSize);
        com.google.android.material.appbar.MaterialToolbar toolbar = new com.google.android.material.appbar.MaterialToolbar(this);
        toolbar.setNavigationIcon(R.drawable.ic_back); toolbar.setNavigationIconTint(INK); toolbar.setNavigationContentDescription("Torna alla biblioteca"); toolbar.setNavigationOnClickListener(v->showLibrary());
        android.graphics.drawable.Drawable more=androidx.core.content.ContextCompat.getDrawable(this,R.drawable.ic_more).mutate(); more.setTint(INK);
        toolbar.getMenu().add("Azioni del libro").setIcon(more).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        toolbar.setOnMenuItemClickListener(item->{bookActions(b);return true;});
        com.google.android.material.appbar.CollapsingToolbarLayout.LayoutParams toolbarSize = new com.google.android.material.appbar.CollapsingToolbarLayout.LayoutParams(-1,dp(56));
        toolbarSize.setCollapseMode(com.google.android.material.appbar.CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PIN); collapse.addView(toolbar,toolbarSize);
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(12), dp(20), dp(24));
        NestedScrollView scroll = new NestedScrollView(this); scroll.setFillViewport(true); scroll.addView(panel);
        androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams scrollSize = new androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(-1,-1);
        scrollSize.setBehavior(new com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()); stage.addView(scroll,scrollSize);
        appbar.bringToFront();
        // Compatibility adapter for existing progress/edit actions; no modal is shown.
        Dialog sheet = new Dialog(this);
        Bitmap art = cover(b);
        LinearLayout identity = row(); identity.setGravity(Gravity.TOP); panel.addView(identity);
        if (art != null) {
            ImageView image = new ImageView(this); image.setImageBitmap(art); image.setScaleType(ImageView.ScaleType.FIT_START);
            image.setContentDescription("Copertina di " + b.title);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(128), dp(192)); size.setMargins(0, 0, dp(16), 0);
            identity.addView(image, size);
        }
        LinearLayout names = column(); identity.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        // Preferito: a heart beside the cover, toggled at once (no menu, no reload).
        boolean[] fav = { b.journal.optBoolean("favorite") };
        MaterialButton heart = iconButton(fav[0] ? R.drawable.ic_favorite : R.drawable.ic_favorite_border, fav[0] ? "Togli dai preferiti" : "Aggiungi ai preferiti", () -> { });
        heart.setIconTint(android.content.res.ColorStateList.valueOf(fav[0] ? COPPER : MUTED));
        heart.setOnClickListener(v -> {
            try { Book c = Book.from(store.find(b.id).json()); ReadingJournal.put(c, "favorite", !fav[0]); store.replace(c); b.journal = c.journal; fav[0] = !fav[0]; }
            catch (Exception e) { error(e); return; }
            heart.setIconResource(fav[0] ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            heart.setIconTint(android.content.res.ColorStateList.valueOf(fav[0] ? COPPER : MUTED));
            heart.setContentDescription(fav[0] ? "Togli dai preferiti" : "Aggiungi ai preferiti");
            heart.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        });
        identity.addView(heart, new LinearLayout.LayoutParams(dp(48), dp(48)));
        names.addView(text(b.title, 24, INK, true));
        if (!b.subtitle.isEmpty()) names.addView(text(b.subtitle, 16, MUTED, true));
        gap(names, 4); names.addView(text(b.author, 15, SAGE, false));
        if (!b.series.isEmpty()) names.addView(text(b.series + order(b), 13, MUTED, false));
        gap(panel, 16);
        if (!b.format.equals("CATALOGO")) {
            panel.addView(button(b.page > 0 ? "Riprendi la lettura" : "Leggi", 0, () -> { sheet.dismiss(); openReader(b); }), new LinearLayout.LayoutParams(-1, -2));
            gap(panel, 12);
        }
        panel.addView(companionReadingCard(b)); gap(panel, 12);
        MaterialButton timer = button(timerBook != null && timerBook.equals(b.id) ? "Riprendi la sessione" : "Avvia il timer di lettura", R.style.Atlante_Button_Tonal, () -> startTimer(b));
        timer.setIconResource(R.drawable.ic_timer); panel.addView(timer, new LinearLayout.LayoutParams(-1, -2));
        addPersonalSections(panel, b);

        if (!b.description.isEmpty()) {
            TextView plot = text(b.description, 15, INK, false); plot.setPadding(0, 0, 0, dp(8));
            panel.addView(expander("Trama", plot, false));
        }
        panel.addView(app.atlante.library.ui.compose.BookSagaView.create(this, palette, b, sagaHost));

        // Other editions of the same work (merged duplicates, EPUBs of another edition): one record, many editions.
        org.json.JSONArray eds = b.meta.optJSONArray("editions");
        if (eds != null && eds.length() > 0) {
            LinearLayout list = column();
            for (int i = 0; i < eds.length(); i++) {
                org.json.JSONObject e = eds.optJSONObject(i); if (e == null) continue;
                String line = e.optString("title") + joinNonEmpty(" · ", e.optString("isbn").isEmpty() ? "" : "ISBN " + e.optString("isbn"), e.optString("publisher"), e.optString("year"),
                    e.optString("language"), e.optString("format").equals("CATALOGO") ? "" : e.optString("format"), e.optString("file").isEmpty() ? "" : "file salvato");
                list.addView(text(line, 14, INK, false));
            }
            panel.addView(expander("Edizioni di quest’opera · " + eds.length(), list, false));
        }
        gap(panel, 12);

        panel.addView(button("Completa i dati online", R.style.Atlante_Button_Tonal, () -> completeMetadata(b)), new LinearLayout.LayoutParams(-1, -2));
        panel.addView(button("Modifica scheda", R.style.Atlante_Button_Tonal, () -> { sheet.dismiss(); edit(b); }), new LinearLayout.LayoutParams(-1, -2));
        panel.addView(button("Cambia copertina", R.style.Atlante_Button_Tonal, () -> chooseOnlineCover(b)), new LinearLayout.LayoutParams(-1, -2));
        panel.addView(button("Scegli copertina dalla galleria", R.style.Atlante_Button_Tonal, () -> {
            coverBookId = b.id; sheet.dismiss();
            coverLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"));
        }), new LinearLayout.LayoutParams(-1, -2));
        if (!b.format.equals("CATALOGO")) panel.addView(button("Esporta il file originale", R.style.Atlante_Button_Tonal, () -> {
            exportId = b.id;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime(b.format))
                .putExtra(Intent.EXTRA_TITLE, b.title.replaceAll("[\\/:*?\"<>|]", "_") + "." + b.format.toLowerCase(Locale.ROOT));
            exportLauncher.launch(intent);
        }), new LinearLayout.LayoutParams(-1, -2));
        MaterialButton remove = button("Rimuovi dalla libreria", R.style.Atlante_Button_Text, () -> dialogBuilder().setTitle("Rimuovere il libro?")
            .setMessage("La copia in Atlante e il punto di lettura verranno rimossi. Il file da cui hai importato il libro resta al suo posto.")
            .setNegativeButton("Annulla", null).setPositiveButton("Rimuovi", (d, w) -> {
                try { store.remove(b); if(b.id.equals(timerBook)) clearTimer(); forgetCover(b.id); sheet.dismiss(); showLibrary(); } catch (Exception e) { error(e); }
            }).show());
        remove.setTextColor(attr(getTheme(), androidx.appcompat.R.attr.colorError)); panel.addView(remove, new LinearLayout.LayoutParams(-1, -2));
        animateIn(panel); updateBack();
    }

    private static String mime(String format) {
        switch (format) {
            case "EPUB": return "application/epub+zip";
            case "TXT": return "text/plain";
            case "AZW3": return "application/vnd.amazon.ebook";
            default: return "application/vnd.comicbook+zip";
        }
    }

    /** A section title that shows or hides its content when tapped (closed by default). */
    private View expander(String title, View body, boolean open) {
        LinearLayout box = column();
        MaterialButton header = button(title, R.style.Atlante_Button_Text, () -> { });
        header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); header.setTextColor(INK); header.setTextSize(16);
        header.setPadding(dp(18), 0, dp(14), 0); header.setIconGravity(MaterialButton.ICON_GRAVITY_END);   // inside its outline
        header.setIconTint(android.content.res.ColorStateList.valueOf(MUTED));
        Runnable update = () -> {
            boolean shown = body.getVisibility() == View.VISIBLE;
            header.setIconResource(shown ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
            header.setContentDescription(title + (shown ? ", aperta. Tocca per chiudere" : ", chiusa. Tocca per aprire"));
        };
        body.setVisibility(open ? View.VISIBLE : View.GONE); update.run();
        header.setOnClickListener(v -> { body.setVisibility(body.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); update.run(); });
        box.addView(header, new LinearLayout.LayoutParams(-1, -2)); box.addView(body);
        return box;
    }

    private int outValue(int attribute) {
        android.util.TypedValue value = new android.util.TypedValue(); getTheme().resolveAttribute(attribute, value, true); return value.resourceId;
    }




    private NumberPicker wheel(int min, int max, int value) {
        NumberPicker picker = new NumberPicker(this); picker.setMinValue(min); picker.setMaxValue(max);
        picker.setValue(Math.max(min, Math.min(max, value))); picker.setWrapSelectorWheel(false);
        picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (android.os.Build.VERSION.SDK_INT >= 29) picker.setTextColor(INK);
        return picker;
    }
    private View scrolled(View content) { ScrollView scroll = new ScrollView(this); scroll.addView(content); return scroll; }

    private MaterialButtonToggleGroup statusToggle(String selected) {
        MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(this);
        group.setSingleSelection(true); group.setSelectionRequired(true);
        for (String state : Book.STATES) {
            MaterialButton option = button(state, R.style.Atlante_Button_Outlined, () -> { });
            option.setId(View.generateViewId()); option.setTextSize(12); option.setPadding(dp(2), 0, dp(2), 0);
            option.setMaxLines(1); option.setInsetTop(0); option.setInsetBottom(0); option.setMinHeight(dp(44));
            group.addView(option, new LinearLayout.LayoutParams(0, -2, 1));
            if (state.equals(selected)) group.check(option.getId());
        }
        return group;
    }

    /** Changes the state with the obvious side effects on dates and pages. */
    private void changeStatus(Book b, String status) {
        Book.changeStatus(b, status, java.time.LocalDate.now().toString()); save();
    }
    /** Stars with an optional half: 3.5 → ★★★½☆. */
    static String stars(double rating) {
        int full = (int) Math.floor(rating); boolean half = rating - full >= .5;
        StringBuilder s = new StringBuilder();
        for (int i = 1; i <= 5; i++) s.append(i <= full ? "★" : i == full + 1 && half ? "½" : "☆");
        return s.toString();
    }
    static String stars(int rating) { StringBuilder s = new StringBuilder(); for (int i = 1; i <= 5; i++) s.append(i <= rating ? '★' : '☆'); return s.toString(); }
    static String italianDate(String iso) {
        try { return java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", app.atlante.library.ui.L.locale())); }
        catch (Exception e) { return iso; }
    }

    /** One window for everything about reading: state, pages, dates, rating and notes. Saved only with "Salva". */
    private void readingDialog(Book original) {
        final Book b;
        try { b = Book.from(original.json()); } catch (Exception e) { error(e); return; }
        LinearLayout panel = column(); panel.setPadding(dp(24), dp(8), dp(24), dp(4));
        panel.addView(sectionLabel("STATO")); gap(panel, 6);
        MaterialButtonToggleGroup states = statusToggle(b.status); panel.addView(states, new LinearLayout.LayoutParams(-1, -2)); gap(panel, 14);
        EditText started = dateField(panel, "Iniziato il", b.started), finished = dateField(panel, "Finito il", b.finished);
        TextView ratingLabel = sectionLabel("VALUTAZIONE · " + (ReadingJournal.rating(b) == 0 ? "nessuna" : stars(ReadingJournal.rating(b)))); panel.addView(ratingLabel);
        Slider rating = new Slider(this); rating.setValueFrom(0); rating.setValueTo(5); rating.setStepSize(.5f); rating.setValue((float) ReadingJournal.rating(b));
        rating.setContentDescription("Valutazione da 0 a 5, mezze stelle comprese"); rating.setLabelFormatter(v -> v == 0 ? "Nessuna" : stars((double) v));
        rating.addOnChangeListener((slider, value, fromUser) -> ratingLabel.setText("VALUTAZIONE · " + (value == 0 ? "nessuna" : stars((double) value))));
        panel.addView(rating); gap(panel, 6);
        EditText notes = field(panel, "Note personali", b.notes); notes.setSingleLine(false); notes.setMinLines(3);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = dialogBuilder().setTitle("Date, voto e note").setView(scroll)
            .setNegativeButton("Annulla", null).setPositiveButton("Salva", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String start = started.getText().toString().trim(), end = finished.getText().toString().trim();
            if (!start.isEmpty() && !end.isEmpty() && start.compareTo(end) > 0) { finished.setError("La fine precede l’inizio"); return; }
            String chosen = Book.STATES[states.indexOfChild(states.findViewById(states.getCheckedButtonId()))];
            b.started = start; b.finished = end;
            if (!chosen.equals(b.status)) Book.changeStatus(b, chosen, java.time.LocalDate.now().toString());
            b.rating = Math.round(rating.getValue()); ReadingJournal.put(b, "rating", (double) rating.getValue()); b.notes = notes.getText().toString();
            Book.applyStatusEffects(b, java.time.LocalDate.now().toString());
            try { store.replace(b); } catch (Exception e) { error(e); return; }
            dialog.dismiss(); renderContent(); details(b);
        })); dialog.show();
    }

    private void bibliographic(LinearLayout panel, Book b) {
        panel.addView(text("Editore: " + available(b.publisher) + "\nAnno: " + available(b.year)
            + "\nLingua: " + available(b.language) + "\nISBN / identificatore: " + available(b.isbn)
            + "\nGenere: " + available(b.genre), 13, MUTED, false));
        // Extended data from the bibliographic engine, each with its source.
        String[][] extra = {{"originalTitle","Titolo originale"},{"translators","Traduzione"},{"editors","Curatela"},{"illustrators","Illustrazioni"},
            {"collection","Collana"},{"firstPublished","Prima pubblicazione"},{"format","Formato"},{"dewey","Classificazione Dewey"},{"subjects","Argomenti"}};
        org.json.JSONObject sources = b.meta.optJSONObject("sources");
        StringBuilder more = new StringBuilder();
        for (String[] e : extra) { String v = b.meta.optString(e[0]); if (v.isEmpty()) continue;
            more.append(more.length() > 0 ? "\n" : "").append(e[1]).append(": ").append(v).append(sources != null && !sources.optString(e[0]).isEmpty() ? "  · " + sources.optString(e[0]) : ""); }
        if (more.length() > 0) { gap(panel, 4); panel.addView(text(more.toString(), 13, MUTED, false)); }
    }
    private static String available(String value) { return value.isEmpty() ? "Non disponibile" : value; }

    private void previewImports(ArrayList<Book> candidates, int index) {
        if (index >= candidates.size()) { renderContent(); return; }
        preview(candidates.get(index), () -> previewImports(candidates, index + 1));
    }

    private void importMetadata(Book b, Runnable next, app.atlante.library.online.Candidate edition) {
        if(busy)return;busy=true;AlertDialog waiting=loading("Metadati online","Consulto Open Library, Wikidata e i cataloghi disponibili…");
        worker.execute(()->{
            app.atlante.library.online.MetadataEngine.Settings settings=metadataSettings();
            try {
                app.atlante.library.online.MetadataEngine.Result result=app.atlante.library.online.MetadataEngine.lookup(b,settings,app.atlante.library.online.MetadataEngine.live(settings,m->{}),edition);
                runOnUiThread(()->{busy=false;waiting.dismiss();if(isFinishing()||isDestroyed())return;
                    if(!result.ambiguous.isEmpty()) {
                        String[] names=new String[result.ambiguous.size()];for(int i=0;i<names.length;i++){app.atlante.library.online.Candidate c=result.ambiguous.get(i);names[i]=c.title+" · "+c.publisher+" · "+c.year+" · "+c.source;}
                        dialogBuilder().setTitle("Scegli l’edizione").setItems(names,(d,w)->importMetadata(b,next,result.ambiguous.get(w))).setNegativeButton("Annulla",(d,w)->preview(b,next)).setOnCancelListener(d->preview(b,next)).show();return;
                    }
                    String[] labels=new String[result.fields.size()];boolean[] checks=new boolean[labels.length];
                    for(int i=0;i<labels.length;i++){app.atlante.library.online.MetadataEngine.Field f=result.fields.get(i);labels[i]=f.label+": "+shortText(f.proposed)+" · "+f.source;checks[i]=f.accept;}
                    dialogBuilder().setTitle(labels.length==0?"Nessun dato aggiuntivo trovato":"Verifica i metadati")
                        .setMultiChoiceItems(labels,checks,(d,w,checked)->result.fields.get(w).accept=checked)
                        .setPositiveButton("Usa nella scheda",(d,w)->{try{app.atlante.library.online.MetadataEngine.apply(b,result.fields);}catch(Exception e){error(e);}preview(b,next);})
                        .setNegativeButton("Annulla",(d,w)->preview(b,next)).setOnCancelListener(d->preview(b,next)).show();
                });
            }catch(Exception e){runOnUiThread(()->{busy=false;waiting.dismiss();error(e);preview(b,next);});}
        });
    }

    private void preview(Book b, Runnable next) {
        LinearLayout panel = column(); panel.setPadding(dp(24), dp(16), dp(24), dp(20));
        Bitmap image = b.onlineCover == null ? cover(b) : decode(null, b.onlineCover, 700);
        ImageView previewCover = new ImageView(this); previewCover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewCover.setContentDescription("Copertina di " + b.title);
        // Always the same place and size for the cover: one found later appears there, and nothing below moves.
        if (image != null) previewCover.setImageBitmap(image); else previewCover.setBackground(background(PAPER,LINE));
        panel.addView(previewCover, new LinearLayout.LayoutParams(-1, dp(220)));
        // The main action right under the cover (not in the dialog's bar, which moved while the sheet loaded).
        MaterialButton primary = (MaterialButton) button("Aggiungi alla mia libreria", true, () -> { });
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(-1, -2); primaryParams.topMargin = dp(12);
        panel.addView(primary, primaryParams);
        // An imported file: title and author can be corrected before adding (a TXT has no metadata at all).
        boolean file = !b.format.equals("CATALOGO") && store.original(b).isFile();
        EditText titleField = null, authorField = null;
        if (file) {
            titleField = field(panel, "Titolo", b.title);
            authorField = field(panel, "Autore", b.author.equals("Autore sconosciuto") ? "" : b.author);
            panel.addView(text("Formato " + b.format + (b.chapters.isEmpty() ? "" : " · " + b.chapters.size() + " voci nell’indice")
                + (b.meta.optBoolean("readable", true) ? "" : "\nQuesto file Kindle usa una compressione che Atlante non sa leggere: puoi catalogarlo e aprirlo con un’altra app."), 13, MUTED, false));
        } else { panel.addView(text(b.title, 28, INK, true)); panel.addView(text(b.author, 18, SAGE, false)); }
        gap(panel, 12);
        // Already in the library? Same ISBN, or same title and author.
        ArrayList<Book> others = new ArrayList<>(); for (Book x : store.snapshot()) if (!x.id.equals(b.id)) others.add(x);
        // One work = one record: the same work already here (same file, ISBN, catalog work, title and author) is never added twice.
        Book duplicate = app.atlante.library.catalog.BibliographicEngine.sameWorkIn(others, b, catalogDao());
        boolean canAttach = file && duplicate != null && (duplicate.format.equals("CATALOGO") || !store.original(duplicate).exists());
        if (duplicate != null) {
            LinearLayout warn = surface(panel, "GIÀ NELLA TUA LIBRERIA");
            warn.addView(text("«" + duplicate.title + "» di " + duplicate.author + (canAttach ? " · scheda senza file" : duplicate.format.equals("CATALOGO") ? "" : " · formato " + duplicate.format), 15, INK, false));
            warn.addView(text(canAttach ? "Il file viene collegato alla scheda esistente: voti, note e avanzamento restano."
                : file ? "La scheda ha già un file: questo diventa un’altra edizione della stessa scheda, senza crearne una nuova."
                : "Non viene creata una seconda scheda: puoi aggiungere questa edizione (ISBN, editore) alla scheda che hai già.", 13, MUTED, false));
            gap(panel, 12);
        }
        bibliographic(panel, b); gap(panel, 14); panel.addView(text("Trama", 20, INK, true));
        panel.addView(text(available(b.description), 16, INK, false)); gap(panel, 12);
        panel.addView(text(b.source.isEmpty() ? "Dati estratti dal file scelto. Puoi correggerli dalla scheda dopo l’aggiunta." : (b.source.startsWith("sbn:") ? "Fonte: catalogo SBN delle biblioteche italiane · " + b.source.substring(4) + "\nCopertina e trama da Open Library, se disponibili."
            : "Fonte: Open Library · " + b.source + "\nEdizione di riferimento: " + available(b.edition)) + " · I dati possono variare tra edizioni. La scheda non include il file del libro.", 13, MUTED, false));
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        final EditText editedTitle = titleField, editedAuthor = authorField;
        AlertDialog.Builder builder = dialogBuilder().setTitle(file ? "Importa eBook" : "Anteprima del libro").setView(scroll).setNegativeButton(duplicate != null ? "Annulla" : "Non aggiungere", null);
        String action = duplicate == null ? "Aggiungi alla mia libreria" : canAttach ? "Collega il file" : "Aggiungi come edizione";
        builder.setPositiveButton(action, null); primary.setText(action);
        if(file)builder.setNeutralButton("Cerca metadati online",null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            if(file)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                b.title=editedTitle.getText().toString().trim();b.author=editedAuthor.getText().toString().trim();
                if(b.title.isEmpty()){editedTitle.setError("Inserisci il titolo");return;}
                dialog.dismiss();importMetadata(b,next,null);
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> { store.discardCandidate(b); dialog.dismiss(); next.run(); });
            // The bar keeps only "Non aggiungere"; the button under the cover does what the bar's button did.
            Button barAction = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (barAction != null) { barAction.setVisibility(View.GONE); primary.setOnClickListener(v -> barAction.performClick()); }
            if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    if (editedTitle != null) {
                        String t = editedTitle.getText().toString().trim(), a = editedAuthor.getText().toString().trim();
                        if (t.isEmpty()) { editedTitle.setError("Inserisci il titolo"); return; }
                        b.title = t; b.author = a.isEmpty() ? "Autore sconosciuto" : a;
                    }
                    if(duplicate==null && app.atlante.library.catalog.BibliographicEngine.sameWorkIn(store.snapshot(),b,catalogDao())!=null){dialog.dismiss();preview(b,next);return;}
                    if (duplicate != null && canAttach) { Book linked = store.attachFile(b, duplicate); forgetCover(linked.id); app.atlante.library.ui.L.toast(this, "File collegato a «" + linked.title + "»", Toast.LENGTH_SHORT).show(); }
                    else if (duplicate != null) { Book linked = store.addEdition(b, duplicate); forgetCover(linked.id); app.atlante.library.ui.L.toast(this, "Edizione aggiunta a «" + linked.title + "»: nessuna scheda doppia", Toast.LENGTH_SHORT).show(); }
                    else store.addCatalog(b);
                    dialog.dismiss(); next.run();
                }
                catch (Exception e) { error(e); }
            });
        });
        dialog.setOnCancelListener(d -> { store.discardCandidate(b); next.run(); }); dialog.show();
        // No cover in the file or the record: the cover engine looks for one (same ISBN, or same title and author).
        if (image == null && duplicate == null) coverWorker.execute(() -> {
            byte[] bytes = b.coverId > 0 ? OnlineCatalog.loadCover(b) : OnlineCatalog.findCover(b);
            if (bytes == null) return;
            b.onlineCover=bytes;
            try { store.saveDownloadedCover(b,bytes); } catch (Exception ignored) { }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                forgetCover(b.id);
                if (dialog.isShowing()) previewCover.setImageBitmap(decode(null,bytes,700));
                else if (reading == null && store.find(b.id) != null) renderContent();
            });
        });
    }

    static String progressLabel(Book b) {
        if (b.totalPages > 0) return "Pagina " + b.currentPage + " di " + b.totalPages + " · " + Math.round(100.0 * b.currentPage / b.totalPages) + "%";
        if (b.percent > 0) return "Al " + b.percent + "%";
        return b.currentPage > 0 ? "Pagina " + b.currentPage : "Posizione da impostare";
    }

    /** Opens the online search with this text and runs it at once; an empty text opens an empty search. */
    private void searchFor(String query) {
        lastOnlineQuery = query == null ? "" : query.trim(); autoSearch = !lastOnlineQuery.isEmpty(); prefillOnline = autoSearch;
        onlineSearch();
    }
    /** The search opens empty; only a caller that asks for a text (a saga volume, "Cerca anche online") fills it. */
    private boolean autoSearch, prefillOnline;
    /**
     * A book seen in Scopri, a saga or the catalog: its sheet opens at once. Already in the library → your record.
     * Otherwise the Italian catalog (SBN) and Open Library are asked in the background and the edition with the same
     * title and author is shown (plot, cover, "Aggiungi alla mia libreria"); only when none is sure, the search page.
     */
    /**
     * A book not in the library (Scopri, saga carousels and map, catalog, online search) opens the same full-page
     * sheet at once: title and author first, then edition, cover and plot when found. A late answer for another
     * book is ignored (each opening has its own number). The same work already in the library opens its own page.
     */
    private Book onlineBook; private int sheetRequest;
    private void openDiscovered(String title, String author) { openDiscovered(title, author, null); }
    private void openDiscovered(String title, String author, String workKey) {
        Book wanted = new Book(); wanted.title = title; wanted.author = author.isEmpty() ? "Autore sconosciuto" : author;
        Book mine = app.atlante.library.catalog.BibliographicEngine.sameWorkIn(store.snapshot(), wanted, catalogDao());
        if (mine != null) { details(mine); return; }
        int request = ++sheetRequest; onlineSheet(wanted, true, false, request);
        catalogWorker.execute(() -> {
            Book found = null;
            try {
                String term = (title + " " + author).trim();
                ArrayList<Book> results = new ArrayList<>();
                try { results.addAll(SbnCatalog.search(term)); } catch (Exception ignored) { }
                try { results.addAll(OnlineCatalog.search(term, "ita", false)); } catch (Exception ignored) { }
                int top = 0;
                for (Book c : results) {
                    int score = BookMatcher.score(c, wanted);
                    if (c.language.equals("ita") || c.source.startsWith("sbn:")) score += 5;          // the Italian edition first
                    if (score > top) { top = score; found = c; }
                }
                if (top < BookMatcher.SAME_TITLE_AND_AUTHOR) found = null;
                found = confirmWork(found, results, workKey);
                if (found != null) { if (found.source.startsWith("sbn:")) SbnCatalog.enrich(found); else OnlineCatalog.enrich(found, "ita"); app.atlante.library.online.PlotFinder.fill(found, catalogDao()); }
            } catch (Exception ignored) { found = null; }
            final Book book = found;
            runOnUiThread(() -> {
                if (!sheetCurrent(request)) return;
                if (book != null) onlineSheet(book, false, true, request); else onlineSheet(wanted, false, false, request);
            });
        });
    }
    /**
     * With the work's Open Library id: an Open Library record of another work is refused, and the record of this
     * very work is taken when the best match was not sure. Italian library records (SBN) have no work id: they
     * keep the title-and-author check. Without an id, the choice stays as it was.
     */
    static Book confirmWork(Book found, List<Book> results, String workKey) {
        if (workKey == null) return found;
        String key = workKey.replaceFirst("^/works/", "");
        if (!key.matches("OL[0-9]+W")) return found;
        Book same = null; for (Book c : results) if (c.source.equals("/works/" + key)) { same = c; break; }
        if (found != null && found.source.matches("/works/OL[0-9]+W") && !found.source.equals("/works/" + key)) return same;
        return found != null ? found : same;
    }
    /** Online search result: the same sheet, filled with the edition's data and plot. */
    private void openOnline(Book candidate, String language) {
        int request = ++sheetRequest; onlineSheet(candidate, true, true, request);
        catalogWorker.execute(() -> {
            try {
                if (candidate.source.startsWith("sbn:")) SbnCatalog.enrich(candidate); else OnlineCatalog.enrich(candidate, language);
                app.atlante.library.online.PlotFinder.fill(candidate, catalogDao());
            } catch (Exception ignored) { }
            runOnUiThread(() -> { if (sheetCurrent(request)) onlineSheet(candidate, false, true, request); });
        });
    }
    /** The answer still belongs to the sheet on screen (not to a book opened before it). */
    private boolean sheetCurrent(int request) { return request == sheetRequest && onlineBook != null && !isFinishing() && !isDestroyed(); }
    private void closeOnlineSheet() { onlineBook = null; sheetRequest++; showLibrary(); }
    private void onlineSheet(Book b, boolean loading, boolean found, int request) {
        reading = null; pages = null; body = null; grid = null; detailId = null; companionScroll = null; timerLabel = null; uiHandler.removeCallbacks(timerTick);
        base(PAPER); onlineBook = b;
        LinearLayout top = row(); top.setPadding(dp(8), dp(4), dp(12), dp(4)); root.addView(top);
        top.addView(iconButton(R.drawable.ic_back, "Indietro", this::closeOnlineSheet));
        top.addView(pageHeading("SCHEDA DEL LIBRO", found ? (b.publisher.isEmpty() ? "Edizione trovata" : b.publisher) : loading ? "Cerco l’edizione…" : "Non in biblioteca"), new LinearLayout.LayoutParams(0, -2, 1));
        ScrollView scroll = new ScrollView(this); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout p = column(); p.setPadding(dp(20), dp(8), dp(20), dp(28)); scroll.addView(p);
        // The cover always has its place: one found later appears there, nothing below moves.
        FrameLayout frame = new FrameLayout(this); p.addView(frame, new LinearLayout.LayoutParams(-1, dp(230)));
        ImageView cover = new ImageView(this); cover.setScaleType(ImageView.ScaleType.FIT_CENTER); cover.setContentDescription("Copertina di " + b.title);
        GradientDrawable placeholder = background(LINE, Color.TRANSPARENT); placeholder.setCornerRadius(dp(8));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(150), -1, Gravity.CENTER_HORIZONTAL); frame.addView(cover, cl);
        Bitmap image = b.onlineCover == null ? null : decode(null, b.onlineCover, 700);
        if (image != null) cover.setImageBitmap(image); else cover.setBackground(placeholder);
        gap(p, 16);
        TextView title = text(b.title, 26, INK, true); title.setGravity(Gravity.CENTER_HORIZONTAL); p.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView author = text(b.author, 16, SAGE, false); author.setGravity(Gravity.CENTER_HORIZONTAL); p.addView(author, new LinearLayout.LayoutParams(-1, -2));
        ArrayList<String> facts = new ArrayList<>(); if (!b.year.isEmpty()) facts.add(b.year); if (b.totalPages > 0) facts.add(b.totalPages + " pagine"); if (!b.language.isEmpty()) facts.add(OnlineCatalog.languageName(b.language));
        if (!facts.isEmpty()) { TextView f = text(TextUtils.join(" · ", facts), 13, MUTED, false); f.setGravity(Gravity.CENTER_HORIZONTAL); p.addView(f, new LinearLayout.LayoutParams(-1, -2)); }
        gap(p, 16);
        MaterialButton primary;
        if (loading) { primary = button("Cerco l’edizione e la trama…", 0, () -> { }); primary.setEnabled(false); }
        else if (found) primary = button("Aggiungi alla mia libreria", 0, () -> addOnline(b));
        else primary = button("Cerca le edizioni online", 0, () -> searchFor((b.title + " " + (b.author.equals("Autore sconosciuto") ? "" : b.author)).trim()));
        primary.setIconResource(found && !loading ? R.drawable.ic_add : R.drawable.ic_search); primary.setIconTint(android.content.res.ColorStateList.valueOf(ON_ACCENT));
        p.addView(primary, new LinearLayout.LayoutParams(-1, -2));
        if (!found && !loading) { TextView none = text("Non ho trovato un’edizione sicura di questo libro: cercala tu tra quelle online.", 13, MUTED, false); none.setPadding(0, dp(6), 0, 0); p.addView(none); }

        sectionHeader(p, "Trama", "", null);
        LinearLayout plot = column(); plot.setPadding(dp(18), dp(16), dp(18), dp(16)); MaterialCardView pc = card(plot); pc.setRadius(dp(20)); p.addView(pc, new LinearLayout.LayoutParams(-1, -2));
        if (loading) plot.addView(text("Cerco la trama…", 14, MUTED, false));
        else if (b.description.trim().isEmpty()) plot.addView(text("Trama non disponibile.", 14, MUTED, false));
        else {
            TextView body = text(b.description.trim(), 15, INK, false); body.setMaxLines(8); body.setEllipsize(TextUtils.TruncateAt.END); plot.addView(body);
            if (b.description.length() > 420) { TextView more = text("Mostra tutto", 14, SAGE, false); more.setMinHeight(dp(44)); more.setGravity(Gravity.CENTER_VERTICAL);
                more.setOnClickListener(v -> { boolean open = body.getMaxLines() == 8; body.setMaxLines(open ? Integer.MAX_VALUE : 8); more.setText(open ? "Mostra meno" : "Mostra tutto"); }); pill(more); gap(plot, 6); plot.addView(more, new LinearLayout.LayoutParams(-2, -2)); }
            String source = b.meta.optString("descriptionSource", ""); if (!source.isEmpty()) { TextView src = text("Fonte: " + source, 13, MUTED, false); src.setPadding(0, dp(8), 0, 0); plot.addView(src); }
        }
        if (found && !loading) {
            ArrayList<String[]> rows = new ArrayList<>();
            if (!b.publisher.isEmpty()) rows.add(new String[]{"Editore", b.publisher}); if (!b.isbn.isEmpty()) rows.add(new String[]{"ISBN", b.isbn});
            if (!b.series.isEmpty()) rows.add(new String[]{"Saga", b.series + order(b)}); if (!b.genre.isEmpty()) rows.add(new String[]{"Genere", b.genre});
            if (!rows.isEmpty()) { sectionHeader(p, "Dettagli", "", null); LinearLayout box = column(); box.setPadding(dp(18), dp(8), dp(18), dp(8)); MaterialCardView dc = card(box); dc.setRadius(dp(20)); p.addView(dc, new LinearLayout.LayoutParams(-1, -2));
                for (String[] r : rows) { if (box.getChildCount() > 0) rule(box); LinearLayout line = row(); line.setPadding(0, dp(10), 0, dp(10)); box.addView(line, new LinearLayout.LayoutParams(-1, -2));
                    line.addView(text(r[0], 13, MUTED, false), new LinearLayout.LayoutParams(dp(90), -2)); TextView v = text(r[1], 14, INK, false); line.addView(v, new LinearLayout.LayoutParams(0, -2, 1)); } }
        }
        TextView note = text("Niente viene aggiunto da solo: il libro entra nella tua biblioteca solo con «Aggiungi».", 13, MUTED, false); note.setPadding(dp(4), dp(16), dp(4), 0); p.addView(note);
        addBottomNav(); updateBack();
        // No cover yet: the cover engine looks for one (same ISBN, or same title and author), for this sheet only.
        if (found && !loading && image == null) coverWorker.execute(() -> {
            byte[] bytes = null; try { bytes = b.coverId > 0 ? OnlineCatalog.loadCover(b) : OnlineCatalog.findCover(b); } catch (Exception ignored) { }
            if (bytes == null) return; byte[] got = bytes;
            runOnUiThread(() -> { if (!sheetCurrent(request) || onlineBook != b) return; b.onlineCover = got; Bitmap bm = decode(null, got, 700); if (bm != null) { cover.setBackground(null); cover.setImageBitmap(bm); } });
        });
    }
    /** «Aggiungi»: the same work already here opens its page; otherwise the record is added and its page opens. */
    private void addOnline(Book b) {
        try {
            Book duplicate = app.atlante.library.catalog.BibliographicEngine.sameWorkIn(store.snapshot(), b, catalogDao());
            onlineBook = null; sheetRequest++;
            if (duplicate != null) { app.atlante.library.ui.L.toast(this, "«" + duplicate.title + "» è già nella tua biblioteca", Toast.LENGTH_SHORT).show(); details(duplicate); return; }
            Book added = store.addCatalog(b); forgetCover(added.id);
            app.atlante.library.ui.L.toast(this, "Aggiunto alla tua biblioteca", Toast.LENGTH_SHORT).show(); details(added);
        } catch (Exception e) { error(e); }
    }


    private void onlineSearch() {
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(12), dp(20), dp(12));
        panel.addView(text("Cerca per titolo, autore o ISBN nel catalogo delle biblioteche italiane (SBN, per l’italiano) e su Open Library. Si aggiunge una scheda personale, anche se non possiedi il file. La ricerca invia al servizio solo il testo inserito.", 14, MUTED, false));
        gap(panel, 8);
        EditText input = field(panel, "Titolo, autore o ISBN", prefillOnline ? lastOnlineQuery : "");
        boolean runNow = autoSearch; autoSearch = false; prefillOnline = false;
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        // Preferred language: its editions come first, with their own title and cover. Remembered.
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        ArrayList<String> languageNames = new ArrayList<>();
        for (String[] l : OnlineCatalog.LANGUAGES) languageNames.add(l[2]);
        Picker language = new Picker(panel, "Lingua preferita", languageNames, OnlineCatalog.languageName(prefs.getString("bookLanguage", app.atlante.library.ui.L.getEnglish() ? "eng" : "ita")));
        MaterialSwitch only = new MaterialSwitch(this); only.setText("Mostra solo libri in questa lingua"); only.setTextSize(14);
        only.setChecked(prefs.getBoolean("onlyLanguage", false)); only.setMinHeight(dp(48)); panel.addView(only);
        LinearLayout results = column();
        Button search = button("Cerca", true, () -> { });
        panel.addView(search); gap(panel, 12); panel.addView(results);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = dialogBuilder().setTitle("Cerca libri online").setView(scroll).setNegativeButton("Chiudi", null).create();
        search.setOnClickListener(v -> {
            String term = input.getText().toString().trim();
            lastOnlineQuery = term; getPreferences(MODE_PRIVATE).edit().putString("onlineQuery",term).apply();
            android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(),0);
            if (term.length() < 2) { input.setError("Inserisci almeno due caratteri"); return; }
            search.setEnabled(false); results.removeAllViews(); results.addView(text("Ricerca in corso…", 14, MUTED, false));
            String code = OnlineCatalog.LANGUAGES[language.position()][0]; boolean onlyLanguage = only.isChecked() && !code.isEmpty();
            prefs.edit().putString("bookLanguage", code).putBoolean("onlyLanguage", only.isChecked()).apply();
            catalogWorker.execute(() -> {
                try {
                    ArrayList<Book> found = new ArrayList<>();
                    // Italian: the national library catalog (SBN) knows far more Italian editions than Open Library.
                    if (code.equals("ita")) { try { found.addAll(SbnCatalog.search(term)); } catch (Exception ignored) { /* Open Library still answers. */ } }
                    ArrayList<Book> openLibrary;
                    try { openLibrary = OnlineCatalog.search(term, code, onlyLanguage); }
                    catch (Exception e) { if (found.isEmpty()) throw e; openLibrary = new ArrayList<>(); }
                    for (Book other : openLibrary) {
                        boolean duplicate = false;
                        for (Book known : found) if (SbnCatalog.sameWork(known, other)) { duplicate = true; break; }
                        if (!duplicate && found.size() < 40) found.add(other);
                    }
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                        search.setEnabled(true); results.removeAllViews();
                        results.addView(text(found.isEmpty() ? (onlyLanguage ? "Nessun risultato in " + OnlineCatalog.languageName(code) + ". Prova a disattivare «solo in questa lingua»." : "Nessun risultato. Prova titolo e autore o un ISBN.")
                            : found.size() + " risultati" + (code.isEmpty() ? "" : " · prima quelli in " + OnlineCatalog.languageName(code)) + " · Fonte: Open Library", 13, MUTED, false));
                        for (Book candidate : found) {
                            gap(results, 12); LinearLayout tile = column(); tile.setPadding(dp(16),dp(16),dp(16),dp(16));
                            results.addView(card(tile));
                            tile.addView(text(candidate.title, 21, INK, true)); gap(tile,4);
                            boolean library = candidate.source.startsWith("sbn:");
                            String origin = library ? "Biblioteche italiane (SBN)" : "Open Library";
                            if (!code.isEmpty() && candidate.language.equals(code)) origin += " · edizione in " + OnlineCatalog.languageName(code);
                            tile.addView(text(origin, 12, COPPER, false)); gap(tile, 2);
                            tile.addView(text(candidate.author + (candidate.publisher.isEmpty() ? "" : " · " + candidate.publisher) + (candidate.year.isEmpty() ? "" : " · " + candidate.year), 14, SAGE, false)); gap(tile,12);
                            Book existing = store.find(candidate.id) != null ? store.find(candidate.id) : BookMatcher.best(candidate, store.snapshot(), x -> x);
                            tile.addView(button(existing == null ? "Vedi la scheda completa" : "Già in libreria · Apri", false, () -> {
                                if (existing != null) { dialog.dismiss(); details(existing); return; }
                                if (candidate.format.equals("CATALOGO") || candidate.format.isEmpty()) { dialog.dismiss(); openOnline(candidate, code.isEmpty() ? "ita" : code); return; }
                                AlertDialog loading = loading("Scheda del libro", "Caricamento trama e metadati…");
                                catalogWorker.execute(() -> {
                                    try {
                                        if (candidate.source.startsWith("sbn:")) SbnCatalog.enrich(candidate);
                                        else OnlineCatalog.enrich(candidate, code.isEmpty() ? "ita" : code);
                                        app.atlante.library.online.PlotFinder.fill(candidate, catalogDao());
                                        runOnUiThread(() -> { loading.dismiss(); if (!isFinishing() && !isDestroyed()) preview(candidate, () -> { dialog.dismiss(); showLibrary(); }); });
                                    } catch (Exception e) { runOnUiThread(() -> { loading.dismiss(); if (!isFinishing() && !isDestroyed()) error(e); }); }
                                });
                            }));
                        }
                    });
                } catch (Exception e) { runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                    search.setEnabled(true); results.removeAllViews();
                    results.addView(text("Ricerca non disponibile. Controlla la connessione e riprova. " + message(e), 14, COPPER, false));
                }); }
            });
        });
        input.setOnEditorActionListener((view,action,event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { if (search.isEnabled()) search.performClick(); return true; } return false;
        }); dialog.show();
        if (runNow) search.performClick();
    }

    /** Outlined Material text field with a floating label. */
    private EditText field(LinearLayout panel, String label, String value) {
        TextInputLayout box = new TextInputLayout(new ContextThemeWrapper(this, R.style.Atlante_Field)); box.setHint(label);
        TextInputEditText input = new TextInputEditText(box.getContext()); input.setText(value); input.setSingleLine(true);
        input.setContentDescription(label);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(box, new LinearLayout.LayoutParams(-1, -2)); gap(panel, 8); return input;
    }
    /** Date field: opens the Material date picker; the end icon clears it. Stored as AAAA-MM-GG. */
    private EditText dateField(LinearLayout panel, String label, String value) {
        TextInputLayout box = new TextInputLayout(new ContextThemeWrapper(this, R.style.Atlante_Field)); box.setHint(label);
        TextInputEditText input = new TextInputEditText(box.getContext()); input.setText(value); input.setSingleLine(true);
        input.setFocusable(false); input.setContentDescription(label);
        input.setOnClickListener(v -> {
            long selection;
            try { selection = java.time.LocalDate.parse(input.getText().toString()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(); }
            catch (Exception e) { selection = MaterialDatePicker.todayInUtcMilliseconds(); }
            MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker().setTitleText(label).setSelection(selection).build();
            picker.addOnPositiveButtonClickListener(day -> input.setText(java.time.Instant.ofEpochMilli(day).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()));
            picker.show(getSupportFragmentManager(), "date");
        });
        box.setEndIconMode(TextInputLayout.END_ICON_CUSTOM); box.setEndIconDrawable(R.drawable.ic_close);
        box.setEndIconContentDescription("Cancella " + label); box.setEndIconOnClickListener(v -> input.setText(""));
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(box, new LinearLayout.LayoutParams(-1, -2)); gap(panel, 8); return input;
    }
    /** Material exposed dropdown menu; keeps the chosen index. */
    private final class Picker {
        final List<String> options; int index;
        Picker(LinearLayout panel, String label, List<String> options, String selected) {
            this.options = options; index = Math.max(0, options.indexOf(selected));
            TextInputLayout box = new TextInputLayout(new ContextThemeWrapper(MainActivity.this, R.style.Atlante_Field_Dropdown)); box.setHint(label);
            MaterialAutoCompleteTextView menu = new MaterialAutoCompleteTextView(box.getContext()); menu.setInputType(android.text.InputType.TYPE_NULL);
            String[] labels = new String[options.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = display(options.get(i));
            menu.setSimpleItems(labels); menu.setText(labels[index], false);
            menu.setOnItemClickListener((parent, view, position, id) -> index = position);
            box.addView(menu, new LinearLayout.LayoutParams(-1, -2));
            panel.addView(box, new LinearLayout.LayoutParams(-1, -2)); gap(panel, 8);
        }
        int position() { return index; }
        String value() { return options.get(index); }
    }
    private static String display(String option) {
        return app.atlante.library.ui.L.t(option.isEmpty() ? "Non indicato" : option.equals("CATALOGO") ? "Senza file (cartaceo o da cercare)" : option);
    }
    private void manualBook() {
        Book b = new Book();
        b.id = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        b.format = "CATALOGO"; b.author = ""; b.addedAt = System.currentTimeMillis();
        edit(b);
    }

    private void edit(Book original) {
        final Book b;
        try { b = Book.from(original.json()); } catch (Exception e) { error(e); return; }
        final boolean creating = store.find(original.id) == null;
        LinearLayout panel = column(); panel.setPadding(dp(24), dp(12), dp(24), dp(12));
        EditText title = field(panel, "Titolo", b.title), author = field(panel, "Autore", b.author);
        EditText totalPages = field(panel, "Numero di pagine (facoltativo)", b.totalPages > 0 ? Integer.toString(b.totalPages) : "");
        totalPages.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText subtitle = field(panel, "Sottotitolo", b.subtitle);
        EditText tags = field(panel, "Tag (separati da virgola)", b.tags);
        EditText location = field(panel, "Posizione nella libreria", b.location);
        Picker medium = new Picker(panel, "Formato del libro", Arrays.asList("", "Cartaceo", "Ebook", "Audiolibro"), b.medium);
        EditText series = field(panel, "Saga", b.series), number = field(panel, "Numero nella saga", b.number > 0 ? number(b.number) : "");
        number.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText genre = field(panel, "Genere", b.genre);
        EditText description = field(panel, "Trama", b.description); description.setSingleLine(false); description.setMinLines(3);
        EditText publisher = field(panel, "Editore", b.publisher), year = field(panel, "Anno", b.year);
        EditText language = field(panel, "Lingua", b.language), isbn = field(panel, "ISBN / identificatore", b.isbn);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = dialogBuilder().setTitle(creating ? "Aggiungi libro manualmente" : "Modifica scheda").setView(scroll)
            .setNegativeButton("Annulla", null).setPositiveButton("Salva", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (title.getText().toString().trim().isEmpty()) { title.setError("Inserisci un titolo"); return; }
            int total;
            try {
                total = totalPages.getText().toString().trim().isEmpty() ? 0 : Integer.parseInt(totalPages.getText().toString().trim());
                if (total < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) { totalPages.setError("Inserisci un numero di pagine valido"); return; }
            b.totalPages = total; if (total > 0 && b.currentPage > total) b.currentPage = total;
            double n = 0;
            try { if (!number.getText().toString().isEmpty()) n = Double.parseDouble(number.getText().toString()); }
            catch (NumberFormatException e) { number.setError("Inserisci un numero"); return; }
            if (!Double.isFinite(n) || n < 0) { number.setError("Inserisci un numero positivo"); return; }
            b.subtitle = subtitle.getText().toString().trim(); b.tags = tags.getText().toString().trim();
            b.location = location.getText().toString().trim(); b.medium = medium.value();
            b.title = title.getText().toString().trim(); b.author = author.getText().toString().trim();
            if (b.author.isEmpty()) b.author = "Autore sconosciuto";
            b.series = series.getText().toString().trim(); b.number = n; b.genre = genre.getText().toString().trim();
            b.description = description.getText().toString().trim(); b.publisher = publisher.getText().toString().trim();
            b.year = year.getText().toString().trim(); b.language = language.getText().toString().trim(); b.isbn = isbn.getText().toString().trim();
            try {
                if (creating) store.addCatalog(b);
                else { app.atlante.library.online.MetadataEngine.markManual(original, b); store.replace(b); }
            } catch (Exception e) { error(e); return; }
            if (creating) { destination = "Biblioteca"; tab = "Tutti i libri"; query = ""; clearFilters(); showLibrary(); }
            dialog.dismiss(); renderContent(); details(b);
        })); dialog.show();
    }

    private void exportBackup() {
        if (busy) return;
        String[] options = {"Con i file dei libri (EPUB, TXT, CBZ)", "Solo catalogo, note e copertine (più leggero)"};
        dialogBuilder().setTitle("Esporta backup").setItems(options, (d, which) -> {
            backupFiles = which == 0;
            String date = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "atlante-backup-" + date + ".zip");
            backupLauncher.launch(intent);
        }).setNegativeButton("Annulla", null).show();
    }

    private void chooseBackup() {
        if (busy) return;
        // Any file: phones and cloud apps label ZIPs with different types and would grey them out.
        // The content is checked anyway (atlante-backup.json) before anything is restored.
        restoreLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"));
    }

    private void writeBackup(Uri dest) {
        final org.json.JSONArray catalog = new org.json.JSONArray();
        try { for (Book b : store.snapshot()) catalog.put(b.json()); } catch (Exception e) { error(e); return; }
        final boolean files = backupFiles;
        busy = true;
        AlertDialog loading = loading("Backup", "Salvataggio di " + catalog.length() + " libri…");
        worker.execute(() -> {
            Exception failure = null;
            try (OutputStream out = getContentResolver().openOutputStream(dest, "wt")) {
                if (out == null) throw new IOException("Destinazione non disponibile");
                BackupArchive.write(out, catalog, new File(getFilesDir(), "books"), files, System.currentTimeMillis());
            } catch (Exception e) { failure = e; }
            final Exception result = failure;
            runOnUiThread(() -> {
                loading.dismiss(); busy = false; if (isFinishing() || isDestroyed()) return;
                if (result != null) { error(result); return; }
                dialogBuilder().setTitle("Backup salvato")
                    .setMessage(catalog.length() + " libri salvati" + (files ? ", con i file." : ", senza i file dei libri: per leggerli dopo un ripristino dovrai reimportarli.")
                        + "\n\nConserva il file fuori dal telefono, per esempio in un servizio cloud o sul computer.")
                    .setPositiveButton("OK", null).show();
            });
        });
    }

    /**
     * Ripristino e importazione (backup ZIP, CSV, JSON, cataloghi): the file is copied into the app's own folder
     * (the permission on the chosen file does not last) and imported in the background, with the notification and
     * the bar; nothing heavy runs here. Only new works are added; the report is in Operazioni in background.
     */
    private void startImport(Uri source) {
        String name = store.name(source);
        app.atlante.library.ui.L.toast(this, "Importazione di «" + name + "» avviata in background: puoi continuare a usare l’app.", Toast.LENGTH_LONG).show();
        worker.execute(() -> {
            File folder = new File(getFilesDir(), "imports"); folder.mkdirs();
            File copy = new File(folder, System.currentTimeMillis() + "-" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
            try (InputStream in = getContentResolver().openInputStream(source); OutputStream out = new FileOutputStream(copy)) {
                if (in == null) throw new IOException("Il file scelto non è leggibile");
                byte[] buffer = new byte[64 * 1024]; long total = 0; int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > 2L * 1024 * 1024 * 1024) throw new IOException("File troppo grande (oltre 2 GB)");
                    out.write(buffer, 0, n);
                }
            } catch (Exception e) { copy.delete(); runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) error(e); }); return; }
            runOnUiThread(() -> {
                if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},9);
                app.atlante.library.catalog.RepairWorker.startImport(this, copy.getPath(), name); watchRepairs();
            });
        });
    }

    private volatile boolean fetchingCovers;

    /**
     * Downloads, one by one in the background, the covers that are missing: by ISBN, otherwise by title and author.
     * Only ISBN, title and author are sent to Open Library. Existing covers are never replaced.
     */
    private void fetchMissingCovers(List<Book> books, boolean announce) { improveCovers(books, announce, false); }

    /** Width of the saved cover in pixels, 0 when there is none. */
    private int coverWidth(Book b) {
        File f = store.cover(b); if (!f.isFile()) return 0;
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getPath(), o);
        return Math.max(0, o.outWidth);
    }

    /** Where the current cover comes from: "gallery" (chosen by you), "online" (chosen by you online), "auto". */
    private void markCover(Book b, String source) { markCover(b, source, ""); }

    /**
     * Where the cover comes from: "online" and "gallery" are your choices (never replaced by the app: not by
     * "Cerca e migliora le copertine", not by Sync or metadata), "auto" one the app found. [origin] tells
     * source, language and publisher ("Hardcover · Inglese · Tor Books").
     */
    private void markCover(Book b, String source, String origin) {
        Book current = store.find(b.id); if (current == null) return;
        try {
            current.meta.put("coverSource", source);
            if (origin.isEmpty()) current.meta.remove("coverOrigin"); else current.meta.put("coverOrigin", origin);
            store.replace(current);
        } catch (Exception ignored) { /* The cover is saved anyway. */ }
    }

    /**
     * Missing covers are downloaded; with {@code improve} also small ones (under 300 px) are replaced when a
     * clearly sharper cover of the same book is found. Covers chosen by the user are never replaced.
     */
    /**
     * Missing covers are downloaded; with {@code improve} also small ones (under 300 px) are replaced when a sharper
     * one exists. In the background, with the notification and the bar (like Sync with Hardcover); the library
     * redraws with the new covers when it ends. [books] says only whether there is anything to do.
     */
    private void improveCovers(List<Book> books, boolean announce, boolean improve) {
        boolean any = false;
        for (Book b : books) { Book current = store.find(b.id); if (current == null) continue;
            String source = current.meta.optString("coverSource", "");
            if (!store.cover(current).isFile() || (improve && !source.equals("gallery") && !source.equals("online") && coverWidth(current) < 300)) { any = true; break; } }
        if (!any) { if (announce) app.atlante.library.ui.L.toast(this, "Tutte le copertine sono già presenti e nitide", Toast.LENGTH_SHORT).show(); return; }
        if (announce) startRepair(app.atlante.library.catalog.RepairWorker.COVERS, improve);
        else app.atlante.library.catalog.RepairWorker.start(this, app.atlante.library.catalog.RepairWorker.COVERS, improve);
        watchRepairs();
    }

    private boolean watchingRepairs;
    /** When a background repair or cover search ends: cached covers are forgotten and the page redraws. */
    private void watchRepairs() {
        if (watchingRepairs) return; watchingRepairs = true;
        androidx.work.WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(app.atlante.library.catalog.RepairWorker.NAME).observe(this, infos -> {
            if (infos == null) return;
            // The first answer lists work finished earlier: only remembered, nothing redrawn.
            if (!repairsPrimed) { repairsPrimed = true; for (androidx.work.WorkInfo w : infos) if (w.getState().isFinished()) seenRepairs.add(w.getId().toString()); return; }
            for (androidx.work.WorkInfo w : infos) if (w.getState().isFinished() && seenRepairs.add(w.getId().toString())) {
                covers.clear(); app.atlante.library.ui.compose.CoverImages.clear(); renderAfterRepair(); break;
            }
        });
    }
    private final java.util.Set<String> seenRepairs = new java.util.HashSet<>(); private boolean repairsPrimed;

    /** Covers found online for one book; the one you tap replaces the current cover. */
    private void chooseOnlineCover(Book b) {
        View[] holder = new View[1];
        java.util.List<File> local = new ArrayList<>(); File[] alt = new File(store.directory(b), "covers").listFiles(); if (alt != null) local.addAll(Arrays.asList(alt));
        holder[0] = app.atlante.library.ui.compose.CoverPickerView.create(this, palette, b, local, (bytes, origin) -> {
            screen.removeView(holder[0]);
            worker.execute(() -> {
                try {
                    store.saveDownloadedCover(b, bytes); markCover(b, "online", origin);
                    runOnUiThread(() -> { forgetCover(b.id); Book current = store.find(b.id); if (current != null) details(current); });
                } catch (Exception e) { runOnUiThread(() -> error(e)); }
            });
        }, () -> screen.removeView(holder[0]));
        screen.addView(holder[0], new FrameLayout.LayoutParams(1, 1));
    }

    // ---- Sync: Hardcover, Calibre, Kindle highlights ----------------------------------------------

    /** CrossPoint: the e-ink reader's files over the home Wi-Fi (ui/compose/CrossPointScreen.kt). */
    private View crossPointView() {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        return app.atlante.library.ui.compose.CrossPointView.create(this, palette, new app.atlante.library.ui.compose.CrossPointHost() {
            @Override public String savedHost() { String h = prefs.getString("crosspointHost", ""); return h.isEmpty() ? null : h; }
            @Override public void saveHost(String host) { prefs.edit().putString("crosspointHost", host == null ? "" : host).apply(); }
            @Override public java.util.List<kotlin.Pair<String, File>> libraryFiles() {
                ArrayList<kotlin.Pair<String, File>> out = new ArrayList<>();
                for (Book b : store.snapshot()) { if (b.format.equals("CATALOGO")) continue; File f = store.original(b); if (f.isFile()) out.add(new kotlin.Pair<>(b.title, f)); }
                out.sort((x, y) -> x.getFirst().compareToIgnoreCase(y.getFirst())); return out;
            }
            @Override public boolean inLibrary(String fileName) {
                String stem = app.atlante.library.model.SearchIndex.normalize(fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName);
                if (stem.length() < 3) return false;
                for (Book b : store.snapshot()) { String t = app.atlante.library.model.SearchIndex.normalize(b.title); if (!t.isEmpty() && (stem.equals(t) || stem.startsWith(t + " ") || stem.contains(" " + t))) return true; }
                return false;
            }
            @Override public void importFiles(java.util.List<? extends File> files) { ArrayList<Uri> uris = new ArrayList<>(); for (File f : files) uris.add(Uri.fromFile(f)); importUris(uris, false); }
            @Override public File cacheDir() { return getCacheDir(); }
            @Override public app.atlante.library.crosspoint.CrossPointClient.Binder binder() { return app.atlante.library.crosspoint.CrossPointWifi.binder(MainActivity.this); }
            @Override public void joinReaderWifi(kotlin.jvm.functions.Function1<? super Boolean, kotlin.Unit> onResult) {
                app.atlante.library.crosspoint.CrossPointWifi.join(MainActivity.this, ok -> { runOnUiThread(() -> onResult.invoke(ok)); return kotlin.Unit.INSTANCE; });
            }
            @Override public void leaveReaderWifi() { app.atlante.library.crosspoint.CrossPointWifi.leave(MainActivity.this); }
            @Override public java.util.List<kotlin.Pair<String, File>> pendingSend() {
                ArrayList<kotlin.Pair<String, File>> out = new ArrayList<>(); Book b = crossPointSend == null ? null : store.find(crossPointSend);
                if (b != null && !b.format.equals("CATALOGO") && store.original(b).isFile()) out.add(new kotlin.Pair<>(b.title, store.original(b)));
                return out;
            }
            @Override public void clearPending() { crossPointSend = null; }
        });
    }
    /** Sync (design 5a), drawn in Compose (ui/compose/SyncScreen.kt); the actions are the same methods as before. */
    private View syncView() {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        boolean hcOn=app.atlante.library.sync.HardcoverAuth.connected(this), calOn=!prefs.getString("calibreUrl","").isEmpty(), koOn=!prefs.getString("koreaderKey","").isEmpty();
        int missing=0; for(Book b:store.snapshot()) if(!store.cover(b).exists()) missing++;
        String last=!prefs.getString("hardcoverLast","").isEmpty()?prefs.getString("hardcoverLast",""):prefs.getString("calibreLast","");
        app.atlante.library.ui.compose.SyncState state=new app.atlante.library.ui.compose.SyncState(hcOn,prefs.getString("hardcoverUser",""),hcOn?hardcoverConflicts().length():0,
            prefs.getString("calibreUrl",""),koOn,prefs.getString("koreaderUser",""),last,store.snapshot().size(),missing,!prefs.getString("isbndbKey","").isEmpty());
        return app.atlante.library.ui.compose.SyncView.create(this,palette,state,new app.atlante.library.ui.compose.SyncHost() {
            @Override public void syncAll() { MainActivity.this.syncAll(hcOn,calOn,koOn); }
            @Override public void resolveConflicts() { resolveHardcoverConflicts(); }
            @Override public void runHardcover() { MainActivity.this.runHardcover(); }
            @Override public void hardcoverSettings() { MainActivity.this.hardcoverSettings(); }
            @Override public void repairHardcover() { repairHardcoverDuplicates(); }
            @Override public void runCalibre() { MainActivity.this.runCalibre(); }
            @Override public void calibreSettings() { MainActivity.this.calibreSettings(); }
            @Override public void calibreHelp() { MainActivity.this.calibreHelp(); }
            @Override public void runKoreader() { MainActivity.this.runKoreader(); }
            @Override public void linkKoreader() { MainActivity.this.linkKoreader(); }
            @Override public void koreaderSettings() { MainActivity.this.koreaderSettings(); }
            @Override public void importClippings() { clippingsLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")); }
            @Override public void importCatalog() { chooseBackup(); }
            @Override public void exportBackup() { MainActivity.this.exportBackup(); }
            @Override public void fetchCovers() { fetchMissingCovers(store.snapshot(),true); }
            @Override public void sourceKeys() { MainActivity.this.sourceKeys(); }
            @Override public void openCrossPoint() { navigate("CrossPoint"); }
        });
    }
    /** Hardcover runs in the background; Calibre (or else KOReader) then runs in the foreground with its progress. */
    private void syncAll(boolean hc,boolean cal,boolean ko) {
        if(hc) runHardcover();
        if(cal) runCalibre(); else if(ko) runKoreader();
    }
    /** A card that holds rows separated by hairlines. */
    LinearLayout listCard(LinearLayout p) {
        LinearLayout list=column(); MaterialCardView c=card(list); c.setRadius(dp(20)); p.addView(c,new LinearLayout.LayoutParams(-1,-2)); return list;
    }
    /** Row with an icon tile, a title, one line of detail and a chevron. */
    LinearLayout listRow(LinearLayout list,int icon,String title,String detail,Runnable action) {
        if(list.getChildCount()>0) rule(list);
        LinearLayout line=row(); line.setPadding(dp(16),dp(8),dp(12),dp(8)); line.setMinimumHeight(dp(64)); list.addView(line,new LinearLayout.LayoutParams(-1,-2));
        if(icon!=0) line.addView(iconTile(icon,SAGE,dp(40),dp(22)));
        LinearLayout info=column(); info.setPadding(icon!=0?dp(14):0,0,dp(8),0); line.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        TextView t=text(title,15,INK,false); t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); info.addView(t);
        if(!detail.isEmpty()) { TextView d=text(detail,13,MUTED,false); d.setMaxLines(2); d.setEllipsize(TextUtils.TruncateAt.END); info.addView(d); }
        line.setTag(info);
        ImageView chevron=new ImageView(this); chevron.setImageResource(R.drawable.ic_chevron_right); chevron.setColorFilter(MUTED); line.addView(chevron,new LinearLayout.LayoutParams(dp(20),dp(20)));
        line.setContentDescription(title+(detail.isEmpty()?"":", "+detail)); line.setOnClickListener(v->action.run());
        return line;
    }
    /** A rounded square with a tinted background and the icon in the given colour. */
    View iconTile(int icon,int color,int size,int iconSize) {
        FrameLayout tile=new FrameLayout(this); GradientDrawable g=background(blend(WHITE,color,.14f),Color.TRANSPARENT); g.setCornerRadius(size/2f>dp(24)?dp(24):dp(12)); if(size>=dp(48)) g.setCornerRadius(size/2f); tile.setBackground(g);
        ImageView i=new ImageView(this); i.setImageResource(icon); i.setColorFilter(color); tile.addView(i,new FrameLayout.LayoutParams(iconSize,iconSize,Gravity.CENTER));
        tile.setLayoutParams(new LinearLayout.LayoutParams(size,size)); return tile;
    }
    static int blend(int from,int to,float t) {
        int r=Math.round(Color.red(from)+(Color.red(to)-Color.red(from))*t), g=Math.round(Color.green(from)+(Color.green(to)-Color.green(from))*t), b=Math.round(Color.blue(from)+(Color.blue(to)-Color.blue(from))*t);
        return Color.rgb(r,g,b);
    }

    private void recognizeSeries() {
        ArrayList<Book> books=new ArrayList<>();ArrayList<SeriesCatalog.Proposal> proposals=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        for(Book b:store.snapshot()){
            SeriesCatalog.Proposal p=null;
            // A saga recognized by the saga engine (Wikidata / Open Library) for a book without one comes first.
            app.atlante.library.ui.MapGroups.Known k=app.atlante.library.saga.SagaIndex.known().get(b.id);
            if(b.series.isEmpty()&&k!=null) p=new SeriesCatalog.Proposal(k.series,app.atlante.library.ui.MapGroups.number(b,k));
            if(p==null) p=SeriesCatalog.suggest(b);
            if(p!=null&&(!p.series.equals(b.series)||p.number!=b.number)){books.add(b);proposals.add(p);labels.add(b.title+"\n"+(b.series.isEmpty()?"Senza saga":b.series+" · "+number(b.number))+" → "+p.series+" · "+number(p.number));}
        }
        if(books.isEmpty()){app.atlante.library.ui.L.toast(this,"Nessuna nuova saga riconosciuta. Puoi indicare saga e numero nella scheda del libro.",Toast.LENGTH_LONG).show();return;}
        boolean[] selected=new boolean[books.size()];Arrays.fill(selected,true);
        dialogBuilder().setTitle("Verifica "+books.size()+" proposte")
            .setMultiChoiceItems(labels.toArray(new String[0]),selected,(d,w,checked)->selected[w]=checked)
            .setNegativeButton("Annulla",null).setPositiveButton("Applica selezionate",(d,w)->{
                for(int i=0;i<books.size();i++)if(selected[i]){Book b=books.get(i);SeriesCatalog.Proposal p=proposals.get(i);b.series=p.series;b.number=p.number;}
                save();showLibrary();
            }).show();
    }

    private void koreaderSettings() {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        LinearLayout p=column(); p.setPadding(dp(20),dp(12),dp(20),dp(12));
        p.addView(text("In KOReader attiva Sincronizzazione avanzamento e usa lo stesso account. Per collegare tramite nome file scegli in KOReader «Metodo di identificazione: nome file», poi invia l’avanzamento. Atlante riceve la percentuale; non invia posizioni EPUB o note.",14,MUTED,false));
        EditText server=field(p,"Server HTTPS",prefs.getString("koreaderUrl","https://sync.koreader.rocks"));
        EditText user=field(p,"Nome utente",prefs.getString("koreaderUser",""));
        EditText password=field(p,"Password (vuota = mantieni quella salvata)",""); password.setInputType(129);
        dialogBuilder().setTitle("KOReader").setView(scrolled(p)).setNegativeButton("Annulla",null)
            .setNeutralButton("Scollega",(d,w)->{prefs.edit().remove("koreaderKey").remove("koreaderUser").apply();})
            .setPositiveButton("Salva",(d,w)->{android.content.SharedPreferences.Editor e=prefs.edit().putString("koreaderUrl",server.getText().toString().trim()).putString("koreaderUser",user.getText().toString().trim()); if(password.length()>0)e.putString("koreaderKey",SyncCore.md5(password.getText().toString())); e.apply();}).show();
    }
    private void linkKoreader() {
        ArrayList<Book> books=store.snapshot();books.sort((a,b)->a.title.compareToIgnoreCase(b.title));
        if(books.isEmpty()){app.atlante.library.ui.L.toast(this,"Aggiungi prima un libro alla biblioteca",Toast.LENGTH_SHORT).show();return;}
        String[] names=new String[books.size()];for(int i=0;i<books.size();i++)names[i]=books.get(i).title+" · "+books.get(i).author;
        dialogBuilder().setTitle("Collega un libro a KOReader").setItems(names,(d,w)->{
            Book b=books.get(w);LinearLayout p=column();p.setPadding(dp(20),dp(12),dp(20),dp(12));
            JSONObject existing=SyncCore.link(b,"koreader");
            p.addView(text("Inserisci il nome esatto del file in KOReader, inclusa l’estensione. In alternativa usa il partial_md5_checksum presente nel file metadata.epub.lua di KOReader.",14,MUTED,false));
            EditText filename=field(p,"Nome file",existing==null?(b.source.endsWith(".epub")?new File(b.source).getName():""):existing.optString("filename"));
            EditText digest=field(p,"Checksum KOReader (facoltativo, 32 caratteri)",existing==null?"":existing.optString("document"));
            dialogBuilder().setTitle(b.title).setView(scrolled(p)).setNegativeButton("Annulla",null).setPositiveButton("Collega",(dd,ww)->{
                String name=filename.getText().toString().trim(), id=digest.getText().toString().trim().toLowerCase(Locale.ROOT);
                if(id.isEmpty()&&!name.isEmpty())id=KoreaderSync.document(name);
                if(!id.matches("[0-9a-f]{32}")){error(new Exception("Inserisci un nome file o un checksum valido"));return;}
                try{JSONObject sync=b.journal.optJSONObject("sync");if(sync==null)sync=new JSONObject();sync.put("koreader",new JSONObject().put("document",id).put("filename",name));ReadingJournal.put(b,"sync",sync);save();}catch(Exception e){error(e);}
            }).show();
        }).show();
    }
    private void runKoreader() {
        if(busy)return;
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        ArrayList<Book> books=store.snapshot();boolean linked=false;for(Book b:books)if(SyncCore.link(b,"koreader")!=null)linked=true;
        if(!linked){linkKoreader();return;}
        String server=prefs.getString("koreaderUrl","https://sync.koreader.rocks"),user=prefs.getString("koreaderUser",""),key=prefs.getString("koreaderKey","");
        busy=true;AlertDialog loading=loading("KOReader","Ricezione dell’avanzamento…");
        worker.execute(()->{try{
            List<KoreaderSync.Progress> changes=KoreaderSync.receive(server,user,key,books);
            runOnUiThread(()->{busy=false;loading.dismiss();if(isFinishing()||isDestroyed())return;
                if(changes.isEmpty()){app.atlante.library.ui.L.toast(this,"Nessun avanzamento trovato. Controlla i collegamenti e invia prima da KOReader.",Toast.LENGTH_LONG).show();return;}
                StringBuilder summary=new StringBuilder();for(KoreaderSync.Progress c:changes)summary.append(c.book.title).append(" → ").append(Math.round(c.percentage*100)).append("%\n");
                dialogBuilder().setTitle("Applica avanzamento KOReader?").setMessage(summary.toString()).setNegativeButton("Annulla",null).setPositiveButton("Applica",(d,w)->{for(KoreaderSync.Progress c:changes)if(store.find(c.book.id)!=null)KoreaderSync.apply(c);save();showLibrary();}).show();
            });
        }catch(Exception e){runOnUiThread(()->{busy=false;loading.dismiss();error(e);});}});
    }

    private void hardcoverSettings() {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        LinearLayout panel=column(); panel.setPadding(dp(24),dp(8),dp(24),dp(4));
        panel.addView(text("Su hardcover.app apri Impostazioni › Hardcover API, crea una chiave con accesso alla libreria e incollala qui.",14,MUTED,false)); gap(panel,8);
        panel.addView(button("Apri la pagina delle chiavi ↗",R.style.Atlante_Button_Text,()->openLink("https://hardcover.app/account/api")));
        EditText token=field(panel,"Chiave personale",prefs.getString("hardcoverToken",""));
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        MaterialSwitch create=new MaterialSwitch(this); create.setText("Crea in Atlante i libri presenti solo su Hardcover"); create.setChecked(prefs.getBoolean("hardcoverCreate",true)); panel.addView(create);
        MaterialSwitch toRead=new MaterialSwitch(this); toRead.setText("Invia anche i libri «Da leggere» non ancora su Hardcover"); toRead.setChecked(prefs.getBoolean("hardcoverToRead",false)); panel.addView(toRead);
        dialogBuilder().setTitle("Hardcover").setView(scrolled(panel)).setNegativeButton("Annulla",null)
            .setNeutralButton("Scollega",(d,w)->disconnectHardcover())
            .setPositiveButton("Salva",(d,w)->{prefs.edit().putBoolean("hardcoverCreate",create.isChecked()).putBoolean("hardcoverToRead",toRead.isChecked()).apply(); saveHardcoverKey(token.getText().toString().trim(),null); showLibrary();}).show();
    }

    private void calibreSettings() {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        LinearLayout panel=column(); panel.setPadding(dp(24),dp(8),dp(24),dp(4));
        EditText url=field(panel,"Indirizzo, es. http://192.168.1.10:8080",prefs.getString("calibreUrl",""));
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        EditText user=field(panel,"Nome utente (se richiesto)",prefs.getString("calibreUser",""));
        EditText password=field(panel,"Password",prefs.getString("calibrePassword",""));
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText library=field(panel,"Biblioteca (vuoto = predefinita)",prefs.getString("calibreLibrary",""));
        panel.addView(sectionLabel("COLONNE PERSONALIZZATE IN CALIBRE")); gap(panel,4);
        EditText status=field(panel,"Stato (testo)",prefs.getString("calibreStatus","#stato"));
        EditText page=field(panel,"Pagina (numero intero)",prefs.getString("calibrePage","#pagina"));
        EditText chapter=field(panel,"Capitolo (testo)",prefs.getString("calibreChapter","#capitolo"));
        MaterialSwitch create=new MaterialSwitch(this); create.setText("Crea in Atlante i libri presenti solo in Calibre"); create.setChecked(prefs.getBoolean("calibreCreate",true)); panel.addView(create);
        dialogBuilder().setTitle("Calibre").setView(scrolled(panel)).setNegativeButton("Annulla",null)
            .setNeutralButton("Scollega",(d,w)->{prefs.edit().remove("calibreUrl").remove("calibreUser").remove("calibrePassword").remove("calibreLast").apply(); showLibrary();})
            .setPositiveButton("Salva",(d,w)->{prefs.edit().putString("calibreUrl",url.getText().toString().trim()).putString("calibreUser",user.getText().toString().trim())
                .putString("calibrePassword",password.getText().toString()).putString("calibreLibrary",library.getText().toString().trim())
                .putString("calibreStatus",column(status)).putString("calibrePage",column(page)).putString("calibreChapter",column(chapter)).putBoolean("calibreCreate",create.isChecked()).apply(); showLibrary();}).show();
    }
    private static String column(EditText field) { String v=field.getText().toString().trim(); return v.isEmpty()?"":v.startsWith("#")?v:"#"+v; }

    private void calibreHelp() {
        dialogBuilder().setTitle("Come preparare Calibre").setMessage(
            "1. In Calibre: Preferenze › Aggiungi colonne personalizzate. Crea tre colonne: «stato» (testo), «pagina» (numeri interi) e «capitolo» (testo). I nomi di ricerca diventano #stato, #pagina e #capitolo.\n\n"
            + "2. Avvia il server: Collega/condividi › Avvia server di contenuti. L’indirizzo è quello del computer, per esempio http://192.168.1.10:8080.\n\n"
            + "3. Per scrivere stato e pagina serve un utente con permesso di modifica: Preferenze › Condivisione in rete › Richiedi nome utente e password, poi Utenti › Aggiungi utente, senza «sola lettura».\n\n"
            + "Il telefono e il computer devono essere sulla stessa rete. Senza le colonne Atlante legge comunque la biblioteca.").setPositiveButton("OK",null).show();
    }

    /** A loading dialog whose message can change while the work goes on. */
    private TextView progressDialog(String title, AlertDialog[] holder) { return progressDialog(title, holder, null); }
    /** With [stop]: an "Interrompi" button; the work stops at the next book and keeps what it already did. */
    private TextView progressDialog(String title, AlertDialog[] holder, Runnable stop) {
        LinearLayout panel=row(); panel.setPadding(dp(24),dp(16),dp(24),dp(16));
        CircularProgressIndicator spinner=new CircularProgressIndicator(this); spinner.setIndeterminate(true); panel.addView(spinner);
        TextView label=text("Un momento…",15,INK,false); label.setPadding(dp(16),0,0,0); panel.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        com.google.android.material.dialog.MaterialAlertDialogBuilder b=dialogBuilder().setTitle(title).setView(panel).setCancelable(false);
        if(stop!=null) b.setNegativeButton("Interrompi",null);
        holder[0]=b.create(); holder[0].show();
        if(stop!=null) holder[0].getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{ stop.run(); label.setText("Interrompo dopo il libro in corso…"); v.setEnabled(false); });
        return label;
    }

    /**
     * Hardcover sync runs in the background (HardcoverSyncWorker): progress and "Interrompi" in a notification,
     * the app can be closed. When it ends with the app open, the usual summary appears.
     */
    private void runHardcover() {
        if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},7);
        app.atlante.library.sync.HardcoverSyncWorker.start(this);
        app.atlante.library.ui.L.toast(this,"Sincronizzazione avviata: segui l’avanzamento nelle notifiche. Puoi usare o chiudere l’app.",Toast.LENGTH_LONG).show();
        watchHardcoverSync();
    }

    /**
     * Sheets made by an earlier sync from an English Hardcover record while the Italian book was already here:
     * found, shown side by side and merged only when the user ticks them. The Italian book stays.
     */
    /** Ripara duplicati Hardcover: in the background (notification, bar in the app); proposals in Operazioni in background. */
    private void repairHardcoverDuplicates() { startRepair(app.atlante.library.catalog.RepairWorker.HARDCOVER); }

    /** Every repair: queued in the background; the app stays usable; the bar and the notification show the progress. */
    void startRepair(String type) { startRepair(type, false); }
    void startRepair(String type, boolean everything) {
        app.atlante.library.diagnostics.CrashLog.step("avviata: " + type);
        if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},8);
        app.atlante.library.catalog.RepairWorker.start(this, type, everything); watchRepairs();
        app.atlante.library.ui.L.toast(this, app.atlante.library.catalog.RepairWorker.title(type) + ": avviata in background. Puoi continuare a usare l’app.", Toast.LENGTH_LONG).show();
    }

    /** Operazioni in background: active, queued and finished repairs, their results and the proposals to decide. */
    private void showOperations() {
        View[] holder = new View[1];
        holder[0] = app.atlante.library.ui.compose.RepairViews.operations(this, palette, operationsHost, () -> { screen.removeView(holder[0]); renderAfterRepair(); });
        screen.addView(holder[0], new FrameLayout.LayoutParams(1, 1));
    }

    /** The book as it is now: after a merge (Ripara duplicati, Sync) the record that took it in; null if it was removed. */
    private Book liveBook(String id) {
        if (id == null) return null; Book x = store.find(id); if (x != null) return x;
        String to = app.atlante.library.storage.MergeHistory.redirect(this, id);
        return to == null || to.equals(id) ? null : store.find(to);
    }

    private void renderAfterRepair() { if (isFinishing() || isDestroyed()) return;
        // The open book was merged into another: show the one that took it in, not a page of a book that is gone.
        if (reading == null && detailId != null && store.find(detailId) == null) { Book to = liveBook(detailId); if (to != null) details(to); else { detailId = null; navigate("Biblioteca"); } return; }
        if (reading == null && detailId == null) { if (destination.equals("Biblioteca") || destination.equals("Cerca")) showLibrary(); else showCompanion(); } }

    private final app.atlante.library.ui.compose.RepairLibraryHost repairHost = new app.atlante.library.ui.compose.RepairLibraryHost() {
        @Override public void start(String type, boolean everything) {
            if (type.equals(app.atlante.library.catalog.RepairWorker.COVERS)) improveCovers(store.snapshot(), true, true); else startRepair(type, everything);
        }
        @Override public void cancel(String id) { app.atlante.library.catalog.RepairWorker.cancel(MainActivity.this, id); }
        @Override public void operations() { showOperations(); }
        @Override public void quality() { catalogQuality(); }
        @Override public void recognize() { recognizeSeries(); }
        @Override public String undoLastMerge() { return operationsHost.undoLastMerge(); }
        @Override public boolean hardcover() { return app.atlante.library.sync.HardcoverAuth.connected(MainActivity.this); }
    };
    private final app.atlante.library.ui.compose.OperationsHost operationsHost = new app.atlante.library.ui.compose.OperationsHost() {
        @Override public void start(String type) { startRepair(type); }
        @Override public void cancel(String id) { app.atlante.library.catalog.RepairWorker.cancel(MainActivity.this, id); }
        @Override public void openBook(String id) { Book x = store.find(app.atlante.library.storage.MergeHistory.redirect(MainActivity.this, id)); if (x != null) details(x); }
        @Override public String merge(String keepId, String otherId, boolean ratingFromOther, boolean reviewFromOther) {
            Book keep = store.find(keepId), other = store.find(otherId);
            if (keep == null || other == null) return "Uno dei due libri non c’è più (forse già unito).";
            try {
                store.merge(app.atlante.library.catalog.BibliographicEngine.INSTANCE.merged(keep, other,
                    new app.atlante.library.catalog.BibliographicEngine.Choices(ratingFromOther, reviewFromOther)), other);
                runOnUiThread(() -> { forgetCover(keepId); });
                return null;
            } catch (Exception e) { return message(e); }
        }
        @Override public void different(String keepId, String otherId) {
            worker.execute(() -> { Book a = store.find(keepId), b = store.find(otherId); if (a == null || b == null) return;
                try { Book copy = Book.from(a.json()); app.atlante.library.catalog.BibliographicEngine.INSTANCE.rememberDifferent(copy, b); store.replace(copy); } catch (Exception ignored) { } });
        }
        @Override public void applyNumber(String bookId, String series, String ordinal) { runOnUiThread(() -> sagaHost.applySeries(bookId, series, ordinal, true)); }
        @Override public void refuseNumber(long seriesId, String bookId, String ordinal) {
            worker.execute(() -> app.atlante.library.saga.SagaRepair.refuseBlocking(MainActivity.this, seriesId, bookId, ordinal));
        }
        @Override public void applyField(String bookId, String field, String value, String series) {
            if (field.equals("number")) { runOnUiThread(() -> sagaHost.applySeries(bookId, series, value, true)); return; }
            worker.execute(() -> { Book x = store.find(bookId); if (x == null) return;
                try { Book copy = Book.from(x.json()); copy.meta.put(field, value); store.replace(copy); } catch (Exception ignored) { } });
        }
        @Override public void keepMine(String bookId, String field) {
            worker.execute(() -> { Book x = store.find(bookId); if (x == null) return;
                try { Book copy = Book.from(x.json()); org.json.JSONArray k = copy.meta.optJSONArray("keepMine"); if (k == null) k = new org.json.JSONArray();
                    k.put(field); copy.meta.put("keepMine", k); store.replace(copy); } catch (Exception ignored) { } });
        }
        @Override public String undoLastMerge() {
            org.json.JSONObject last = app.atlante.library.storage.MergeHistory.removeLast(MainActivity.this);
            if (last == null) return "Nessuna unione da annullare.";
            try { store.undoMerge(last); return "Unione annullata: «" + last.getJSONObject("other").optString("title") + "» è di nuovo una scheda a sé."; }
            catch (Exception e) { return message(e); }
        }
    };


    private org.json.JSONArray hardcoverConflicts() {
        try { return new org.json.JSONArray(getPreferences(MODE_PRIVATE).getString("hardcoverConflicts","[]")); } catch (Exception e) { return new org.json.JSONArray(); }
    }

    private static String snapshotLine(org.json.JSONObject s) {
        String status=s.optString("status"); int page=s.optInt("page"); double rating=s.optDouble("rating",0);
        return status+((status.equals(Book.READING)||status.equals(Book.PAUSED))&&page>0?" · pagina "+page:"")+(rating>0?" · voto "+(rating==Math.floor(rating)?String.valueOf((int)rating):String.valueOf(rating)):"");
    }

    /** One conflict at a time: the book changed here and on Hardcover; you keep one side, or decide later. */
    private void resolveHardcoverConflicts() {
        org.json.JSONArray all=hardcoverConflicts();
        if(all.length()==0){ if(destination.equals("Sync")) showCompanion(); return; }
        org.json.JSONObject c=all.optJSONObject(0);
        Runnable next=()->{ org.json.JSONArray rest=new org.json.JSONArray(); for(int i=1;i<all.length();i++) rest.put(all.opt(i));
            getPreferences(MODE_PRIVATE).edit().putString("hardcoverConflicts",rest.toString()).apply(); resolveHardcoverConflicts(); };
        java.util.function.Consumer<Boolean> keep=here->worker.execute(()->{
            try { app.atlante.library.sync.HardcoverSync.resolve(store,c,here); runOnUiThread(next); } catch(Exception e){ runOnUiThread(()->error(e)); }
        });
        dialogBuilder().setTitle("«"+c.optString("title")+"»")
            .setMessage("Cambiato sia qui sia su Hardcover dall’ultima sincronizzazione.\n\nIn Atlante: "+snapshotLine(c.optJSONObject("here"))
                +"\nSu Hardcover: "+snapshotLine(c.optJSONObject("there"))+"\n\n"+(all.length()>1?(all.length()-1)+" altri conflitti dopo questo.":""))
            .setPositiveButton("Tieni Atlante",(d,w)->keep.accept(true))
            .setNeutralButton("Tieni Hardcover",(d,w)->keep.accept(false))
            .setNegativeButton("Dopo",(d,w)->{ if(destination.equals("Sync")) showCompanion(); }).show();
    }

    private boolean watchingHardcover;
    /** Shows the summary when a background sync ends while the app is open. */
    private void watchHardcoverSync() {
        if(watchingHardcover) return; watchingHardcover=true;
        androidx.work.WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(app.atlante.library.sync.HardcoverSyncWorker.NAME).observe(this,infos->{
            if(infos==null||infos.isEmpty()) return;
            androidx.work.WorkInfo w=infos.get(0);
            if(!w.getState().isFinished()) return;
            String seen=getPreferences(MODE_PRIVATE).getString("hardcoverShown","");
            if(seen.equals(w.getId().toString())) return;
            getPreferences(MODE_PRIVATE).edit().putString("hardcoverShown",w.getId().toString()).apply();
            androidx.work.Data out=w.getOutputData();
            String error=out.getString(app.atlante.library.sync.HardcoverSyncWorker.ERROR);
            if(error!=null){ error(new IOException(error)); return; }
            if(w.getState()!=androidx.work.WorkInfo.State.SUCCEEDED) return;
            String notes=out.getString(app.atlante.library.sync.HardcoverSyncWorker.NOTES);
            renderContent();
            syncReport(out.getBoolean(app.atlante.library.sync.HardcoverSyncWorker.STOPPED,false)?"Sincronizzazione interrotta":"Hardcover sincronizzato",
                out.getString(app.atlante.library.sync.HardcoverSyncWorker.SUMMARY),
                notes==null||notes.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(notes.split("\n"))));
        });
    }

    private void runCalibre() {
        if(busy) return; busy=true;
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        CalibreSync.Settings s=new CalibreSync.Settings(); s.url=prefs.getString("calibreUrl",""); s.user=prefs.getString("calibreUser",""); s.password=prefs.getString("calibrePassword","");
        s.library=prefs.getString("calibreLibrary",""); s.statusColumn=prefs.getString("calibreStatus","#stato"); s.pageColumn=prefs.getString("calibrePage","#pagina");
        s.chapterColumn=prefs.getString("calibreChapter","#capitolo"); s.createMissing=prefs.getBoolean("calibreCreate",true);
        AlertDialog[] dialog=new AlertDialog[1]; TextView label=progressDialog("Calibre",dialog);
        worker.execute(()->{
            Exception failure=null; CalibreSync.Report r=null;
            try { r=CalibreSync.sync(store,s,m->runOnUiThread(()->label.setText(m))); } catch(Exception e){ failure=e; }
            final Exception error=failure; final CalibreSync.Report report=r;
            runOnUiThread(()->{ dialog[0].dismiss(); busy=false; if(isFinishing()||isDestroyed()) return;
                if(error!=null){ error(error); return; }
                String when=java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm",app.atlante.library.ui.L.locale()));
                prefs.edit().putString("calibreLast","Ultima sincronizzazione: "+when+" · "+report.pushed+" inviati, "+report.pulled+" ricevuti, "+report.created+" nuovi").apply();
                syncReport("Calibre sincronizzato", report.remote+" libri in Calibre ("+report.library+") · "+report.linked+" collegati\n"+report.pushed+" aggiornati in Calibre · "+report.pulled+" aggiornati in Atlante\n"
                    +report.created+" nuove schede in Atlante"+(report.recognized>0?"\n"+report.recognized+" libri riconosciuti come già presenti: nessun doppione":""), report.notes); });
        });
    }

    private void syncReport(String title, String summary, List<String> notes) {
        covers.clear(); showLibrary();
        StringBuilder text=new StringBuilder(summary);
        for(int i=0;i<notes.size()&&i<12;i++) text.append(i==0?"\n\n":"\n").append("• ").append(notes.get(i));
        if(notes.size()>12) text.append("\n• … e altre ").append(notes.size()-12).append(" note");
        dialogBuilder().setTitle(title).setMessage(text.toString()).setPositiveButton("OK",null).show();
        fetchMissingCovers(store.snapshot(),false);
    }

    /** Kindle highlights: grouped by book, matched to the library (or new sheets), added to the notebook. */
    private void readClippings(Uri source) {
        busy=true;
        worker.execute(()->{
            try (InputStream in=getContentResolver().openInputStream(source)) {
                if(in==null) throw new IOException("File non disponibile");
                List<KindleClippings.Clip> clips=KindleClippings.parse(new String(EbookParser.read(in,CatalogImport.TEXT_LIMIT),java.nio.charset.StandardCharsets.UTF_8));
                if(clips.isEmpty()) throw new IOException("Nessuna evidenziazione trovata: scegli il file My Clippings.txt del Kindle.");
                LinkedHashMap<String,List<KindleClippings.Clip>> byBook=new LinkedHashMap<>();
                for(KindleClippings.Clip c:clips) byBook.computeIfAbsent(c.title+"\u0000"+c.author,k->new ArrayList<>()).add(c);
                Map<String,Book> matches=new HashMap<>(); List<Book> library=store.snapshot();
                for(Map.Entry<String,List<KindleClippings.Clip>> e:byBook.entrySet()) {
                    KindleClippings.Clip first=e.getValue().get(0); Book probe=new Book(); probe.title=first.title; probe.author=first.author.isEmpty()?"Autore sconosciuto":first.author;
                    for(Book b:library) if(SbnCatalog.sameWork(b,probe)||OnlineCatalog.sameTitle(b.title,probe.title)&&(first.author.isEmpty()||SbnCatalog.sameWork(b,probe))) { matches.put(e.getKey(),b); break; }
                }
                runOnUiThread(()->{ busy=false; if(isFinishing()||isDestroyed()) return;
                    int known=matches.size(), fresh=byBook.size()-known;
                    dialogBuilder().setTitle("Evidenziazioni del Kindle").setMessage(clips.size()+" evidenziazioni e note in "+byBook.size()+" libri\n"+known+" libri già in biblioteca\n"+fresh+" libri nuovi: verranno create le schede (formato Kindle)\n\nLe note già presenti non vengono ripetute.")
                        .setNegativeButton("Annulla",null).setPositiveButton("Importa",(d,w)->applyClippings(byBook,matches)).show(); });
            } catch(Exception e) { runOnUiThread(()->{ busy=false; if(!isFinishing()&&!isDestroyed()) error(e); }); }
        });
    }

    private void applyClippings(LinkedHashMap<String,List<KindleClippings.Clip>> byBook, Map<String,Book> matches) {
        int added=0, sheets=0; String today=java.time.LocalDate.now().toString();
        try {
            for(Map.Entry<String,List<KindleClippings.Clip>> e:byBook.entrySet()) {
                Book target=matches.get(e.getKey());
                if(target==null) {
                    KindleClippings.Clip first=e.getValue().get(0); Map<String,String> f=new HashMap<>(); f.put("title",first.title); f.put("author",first.author);
                    Book b=CatalogImport.fromFields(f); if(b==null) continue; b.medium="Kindle";
                    Book existing=store.find(b.id); if(existing!=null) target=existing; else { added+=KindleClippings.addTo(b,e.getValue(),today); store.addCatalog(b); sheets++; continue; }
                }
                Book copy=Book.from(target.json()); added+=KindleClippings.addTo(copy,e.getValue(),today); store.replace(copy);
            }
        } catch(Exception e) { error(e); }
        final int notes=added, created=sheets; showLibrary();
        dialogBuilder().setTitle("Importazione completata").setMessage(notes+" note aggiunte ai taccuini"+(created>0?"\n"+created+" nuove schede create":"")+".").setPositiveButton("OK",null).show();
        if(created>0) fetchMissingCovers(store.snapshot(),false);
    }

    // ---- multi-source bibliographic engine ------------------------------------------------------

    private void sourceKeys() {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        LinearLayout panel=column(); panel.setPadding(dp(24),dp(8),dp(24),dp(4));
        panel.addView(text("ISBNdb è un servizio a pagamento: usalo solo se hai già un abbonamento.",13,MUTED,false));
        EditText isbndb=field(panel,"Chiave ISBNdb (facoltativa)",prefs.getString("isbndbKey",""));
        isbndb.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        dialogBuilder().setTitle("Chiavi delle fonti").setView(scrolled(panel)).setNegativeButton("Annulla",null)
            .setPositiveButton("Salva",(d,w)->{prefs.edit().remove("googleBooksKey").putString("isbndbKey",isbndb.getText().toString().trim()).apply(); showLibrary();}).show();
    }

    private app.atlante.library.online.MetadataEngine.Settings metadataSettings() {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        app.atlante.library.online.MetadataEngine.Settings s=new app.atlante.library.online.MetadataEngine.Settings();
        s.keys.isbndb=prefs.getString("isbndbKey","");
        String lang=prefs.getString("bookLanguage", app.atlante.library.ui.L.getEnglish() ? "eng" : "ita"); s.language=lang.isEmpty()?"ita":lang;
        return s;
    }

    /** Asks every catalog about the book, then shows a review page: nothing changes until the user applies. */
    private void completeMetadata(Book b) { completeMetadata(b, null); }
    private void completeMetadata(Book b, app.atlante.library.online.Candidate chosen) {
        if(busy) return; busy=true;
        app.atlante.library.online.MetadataEngine.Settings s=metadataSettings();
        AlertDialog[] dialog=new AlertDialog[1]; TextView label=progressDialog("Completa i dati",dialog);
        worker.execute(()->{
            app.atlante.library.online.MetadataEngine.Result r=null; Exception failure=null;
            try { r=app.atlante.library.online.MetadataEngine.lookup(b,s,app.atlante.library.online.MetadataEngine.live(s,m->runOnUiThread(()->label.setText(m))),chosen); } catch(Exception e){ failure=e; }
            final app.atlante.library.online.MetadataEngine.Result result=r; final Exception error=failure;
            runOnUiThread(()->{ dialog[0].dismiss(); busy=false; if(isFinishing()||isDestroyed()) return;
                if(error!=null){ error(error); return; }
                if(!result.ambiguous.isEmpty()) { chooseEdition(b,result.ambiguous); return; }
                metadataReview(b,result); });
        });
    }

    /** Several editions match and the book has no ISBN: the user says which one is theirs. */
    private void chooseEdition(Book b, List<app.atlante.library.online.Candidate> editions) {
        String[] labels=new String[editions.size()];
        for(int i=0;i<labels.length;i++){ app.atlante.library.online.Candidate c=editions.get(i);
            labels[i]=c.title+(c.publisher.isEmpty()?"":" · "+c.publisher)+(c.year.isEmpty()?"":" · "+c.year)+(c.language.isEmpty()?"":" · "+c.language)+(c.isbns.isEmpty()?"":" · ISBN "+c.isbns.iterator().next())+"  ("+c.source+")"; }
        dialogBuilder().setTitle("Quale edizione hai?").setItems(labels,(d,w)->completeMetadata(b,editions.get(w))).setNegativeButton("Annulla",null).show();
    }

    private void metadataReview(Book b, app.atlante.library.online.MetadataEngine.Result r) {
        LinearLayout p=editorPage("Completa i dati",b);
        p.addView(text(b.title,22,INK,true));
        p.addView(text("Fonti consultate: "+(r.sources.isEmpty()?"nessun risultato":String.join(", ",r.sources))+(r.edition==null?"":"\nEdizione: "+r.edition.publisher+" "+r.edition.year+" ("+r.edition.source+")"),13,MUTED,false));
        for(String n:r.notes) p.addView(text("• "+n,12,COPPER,false));
        gap(p,12);
        // Covers of this edition: kept as they are unless one is chosen; the old one stays restorable.
        final byte[][] coverChoice=new byte[1][];
        if(!r.covers.isEmpty()) {
            p.addView(sectionLabel("COPERTINA")); gap(p,6);
            HorizontalScrollView strip=new HorizontalScrollView(this); LinearLayout row=row(); strip.addView(row); p.addView(strip);
            List<View> tiles=new ArrayList<>();
            boolean hasCover=store.cover(b).isFile();
            View keep=coverTile(row,tiles,hasCover?cover(b):null,hasCover?"Attuale":"Nessuna",()->coverChoice[0]=null); keep.setSelected(true); keep.setAlpha(1f);
            for(app.atlante.library.online.Candidate.Cover c:r.covers.subList(0,Math.min(6,r.covers.size()))) {
                View tile=coverTile(row,tiles,null,c.source+(c.width>0?" · "+c.width+"px":""),null);
                coverWorker.execute(()->{ byte[] bytes=null; try { bytes=OnlineCatalog.request(c.url,4*1024*1024); } catch(Exception ignored){}
                    Bitmap bmp=bytes==null?null:decode(null,bytes,400); final byte[] data=bytes;
                    runOnUiThread(()->{ if(bmp==null||bmp.getWidth()<120) { tile.setVisibility(View.GONE); return; }   // placeholders and thumbnails are dropped
                        ((ImageView)((ViewGroup)tile).getChildAt(0)).setImageBitmap(bmp);
                        tile.setOnClickListener(v->{ for(View t:tiles){t.setAlpha(.45f);} tile.setAlpha(1f); coverChoice[0]=data; });
                        if(!hasCover && coverChoice[0]==null) { for(View t:tiles){t.setAlpha(.45f);} tile.setAlpha(1f); coverChoice[0]=data; } }); });
            }
            gap(p,14);
        }
        // Plot alternatives: the engine's choice first; the user can pick another or keep the current one.
        app.atlante.library.online.MetadataEngine.Field plot=null;
        for(app.atlante.library.online.MetadataEngine.Field f:r.fields) if(f.key.equals("description")) plot=f;
        final app.atlante.library.online.MetadataEngine.Field plotField=plot;
        if(plotField!=null && r.descriptions.size()>1) {
            p.addView(sectionLabel("TRAMA")); gap(p,6);
            RadioGroup group=new RadioGroup(this); p.addView(group);
            if(!b.description.isEmpty()) { RadioButton current=new RadioButton(this); current.setText("Mantieni la trama attuale"); current.setId(View.generateViewId()); group.addView(current); current.setOnClickListener(v->plotField.accept=false); if(plotField.manual) current.setChecked(true); }
            int i=0; for(app.atlante.library.online.Candidate.Description d:r.descriptions) { if(i++>=4) break;
                RadioButton rb=new RadioButton(this); rb.setId(View.generateViewId());
                rb.setText(d.source+" · "+(d.language.isEmpty()?"lingua ?":d.language)+" · "+d.text.length()+" caratteri\n"+(d.text.length()>260?d.text.substring(0,260)+"…":d.text));
                group.addView(rb); rb.setOnClickListener(v->{ plotField.accept=true; setProposed(plotField,d.text); });
                if(i==1 && !plotField.manual && (b.description.isEmpty()||plotField.accept)) rb.setChecked(true); }
            gap(p,14);
        }
        p.addView(sectionLabel("DATI DELLA SCHEDA")); gap(p,6);
        if(r.fields.isEmpty()) p.addView(text("Nessun dato nuovo: la scheda è già completa rispetto alle fonti consultate.",14,MUTED,false));
        for(app.atlante.library.online.MetadataEngine.Field f:r.fields) {
            if(f==plotField && r.descriptions.size()>1) continue;
            LinearLayout box=column(); box.setPadding(0,dp(6),0,dp(6));
            MaterialSwitch sw=new MaterialSwitch(this); sw.setText(f.label+"  ·  "+f.source+(f.manual?"  · modificato da te":"")); sw.setChecked(f.accept);
            sw.setOnCheckedChangeListener((v,on)->f.accept=on); box.addView(sw);
            if(!f.current.isEmpty()) box.addView(text("Ora: "+shortText(f.current),13,MUTED,false));
            box.addView(text("Nuovo: "+shortText(f.proposed),14,INK,false));
            p.addView(box);
        }
        gap(p,16);
        p.addView(button("Applica le modifiche scelte",true,()->{
            try {
                Book c=Book.from(b.json()); app.atlante.library.online.MetadataEngine.apply(c,r.fields);
                if(coverChoice[0]!=null) {
                    java.io.File current=store.cover(b), original=new java.io.File(current.getPath()+".orig");
                    if(current.isFile() && !original.exists()) java.nio.file.Files.copy(current.toPath(),original.toPath());   // "Ripristina copertina originale"
                    store.saveDownloadedCover(c,coverChoice[0]); forgetCover(c.id);
                }
                store.replace(c); renderContent(); details(c);
            } catch(Exception e){ error(e); }
        }),new LinearLayout.LayoutParams(-1,-2));
        p.addView(button("Annulla",R.style.Atlante_Button_Text,()->details(b)),new LinearLayout.LayoutParams(-1,-2));
    }

    private static void setProposed(app.atlante.library.online.MetadataEngine.Field f, String text) { f.choose(text); }
    private static String shortText(String v) { return v.length()>220?v.substring(0,220)+"…":v; }

    private View coverTile(LinearLayout row, List<View> tiles, Bitmap image, String label, Runnable onPick) {
        LinearLayout tile=column(); tile.setPadding(dp(4),dp(4),dp(4),dp(4)); tile.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView img=new ImageView(this); img.setScaleType(ImageView.ScaleType.FIT_CENTER); img.setBackground(background(PAPER,LINE)); if(image!=null) img.setImageBitmap(image);
        tile.addView(img,new LinearLayout.LayoutParams(dp(96),dp(144)));
        TextView t=text(label,11,MUTED,false); t.setMaxWidth(dp(100)); tile.addView(t);
        tile.setAlpha(.45f); tiles.add(tile); row.addView(tile);
        if(onPick!=null) tile.setOnClickListener(v->{ for(View x:tiles) x.setAlpha(.45f); tile.setAlpha(1f); onPick.run(); });
        return tile;
    }

    private void chooseBooks() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        importLauncher.launch(intent);
    }

    private void handleResult(int request, int result, Intent data) {
        if (result != RESULT_OK || data == null || store == null) return;
        if (request == COVER && data.getData() != null) {
            Book target = store.find(coverBookId);
            if (target == null) return;
            Uri uri = data.getData();
            worker.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Immagine non disponibile");
                    byte[] bytes = EbookParser.read(in, 12 * 1024 * 1024);
                    Bitmap image = decode(null, bytes, 1000);
                    if (image == null) throw new IOException("Formato immagine non supportato");
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    image.compress(Bitmap.CompressFormat.JPEG, 85, out); image.recycle();
                    store.saveDownloadedCover(target, out.toByteArray());
                    markCover(target, "gallery");
                    runOnUiThread(() -> { forgetCover(target.id); renderContent(); details(target); });
                } catch (Exception e) { runOnUiThread(() -> error(e)); }
            });
            return;
        }
        if (request == BACKUP && data.getData() != null) { writeBackup(data.getData()); return; }
        if (request == RESTORE && data.getData() != null) { startImport(data.getData()); return; }
        if (request == CLIPPINGS && data.getData() != null) { if (!busy) readClippings(data.getData()); return; }
        if (request == IMPORT) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) uris.add(data.getData());
            for (Uri uri : uris) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException ignored) { /* A temporary grant still allows this import. */ }
            }
            importUris(uris, true);
        } else if (request == EXPORT && data.getData() != null && exportId != null) {
            Book b = store.find(exportId); Uri dest = data.getData(); exportId = null;
            if (b == null) return;
            worker.execute(() -> {
                try (InputStream in = new FileInputStream(store.original(b)); OutputStream out = getContentResolver().openOutputStream(dest, "wt")) {
                    if (out == null) throw new IOException("Destinazione non disponibile");
                    byte[] buffer = new byte[32768]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    runOnUiThread(() -> app.atlante.library.ui.L.toast(this, "File esportato", Toast.LENGTH_SHORT).show());
                } catch (Exception e) { runOnUiThread(() -> error(e)); }
            });
        }
    }

    /** Imports files (picked, or downloaded from CrossPoint) with preview, duplicates and existing records. */
    private void importUris(ArrayList<Uri> uris, boolean persisted) {
        busy = true; showLibrary();
        worker.execute(() -> {
            int duplicate = 0, reattached = 0; ArrayList<String> errors = new ArrayList<>();
            LinkedHashMap<String, Book> candidates = new LinkedHashMap<>();
            for (Uri uri : uris) {
                try { Book candidate = store.importBook(uri); if (candidate != null && candidate.reattached) { candidate.reattached = false; reattached++; } else if (candidate == null || candidates.containsKey(candidate.id)) duplicate++; else candidates.put(candidate.id, candidate); }
                catch (Exception e) { errors.add(message(e)); }
                finally {
                    if (persisted) try { getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (SecurityException ignored) { }
                }
            }
            final String report = candidates.size() + " schede da confermare" + (duplicate > 0 ? " · " + duplicate + " già presenti" : "")
                + (reattached > 0 ? " · " + reattached + " file ricollegati a schede esistenti" : "");
            runOnUiThread(() -> {
                busy = false; if (isFinishing() || isDestroyed()) return;
                showLibrary();
                dialogBuilder().setTitle("Importazione completata").setMessage(report + (errors.isEmpty() ? "" : "\n\n" + String.join("\n", errors)))
                    .setPositiveButton("Vedi le schede", (d, w) -> previewImports(new ArrayList<>(candidates.values()), 0)).setCancelable(false).show();
            });
        });
    }

    private void openReader(Book b) {
        if (busy) return;
        busy = true;
        AlertDialog loading = loading("Apertura libro", "Un momento…");
        worker.execute(() -> {
            try {
                List<String> result;
                if (!store.original(b).isFile()) throw new IOException("Il file di questo libro non è sul telefono: reimportalo da ☰ › Importa ebook. Note e avanzamento restano collegati.");
                if (b.format.equals("CBZ")) {
                    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(store.original(b))) { result = EbookParser.comicPages(zip); }
                    if (result.isEmpty()) throw new IOException("Nessuna pagina nel CBZ");
                } else result = EbookParser.textPages(store.original(b), b.format);
                runOnUiThread(() -> {
                    loading.dismiss(); busy = false; if (isFinishing() || isDestroyed()) return;
                    reading = b; pages = result; page = Math.min(b.page, result.size() - 1);
                    if (!b.status.equals("Letto")) b.status = "In lettura";
                    save(); showReader();
                });
            } catch (Exception e) { runOnUiThread(() -> { loading.dismiss(); busy = false; if (!isFinishing() && !isDestroyed()) error(e); }); }
        });
    }

    private void rememberPage() {
        uiHandler.removeCallbacks(savePosition);
        if (reading == null) return;
        reading.page = page; save();
    }
    private void turn(int direction) {
        int next = page + direction;
        if (next < 0 || next >= pages.size()) return;
        page = next; reading.page = page;
        // Write the catalog once the reader pauses, not on every page.
        uiHandler.removeCallbacks(savePosition); uiHandler.postDelayed(savePosition, 1500);
        showReader();
    }
    private void showReader() {
        detailId = null;
        int paper = readerDark ? 0xff101014 : PAPER, ink = readerDark ? 0xffF1EDF5 : INK;
        base(paper); updateBack();
        LinearLayout header = column(); header.setPadding(dp(16), dp(12), dp(16), dp(12)); root.addView(header);
        LinearLayout bar = row(); header.addView(bar);
        bar.addView(button("‹ Libro", false, () -> { Book b = reading; rememberPage(); details(b); }));
        TextView title = text(reading.title, 16, ink, true); title.setMaxLines(2); title.setPadding(dp(14), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); gap(header, 12);
        LinearLayout controls = row(); header.addView(controls);
        if (reading.format.equals("CBZ")) {
            controls.addView(button(manga ? "Manga ←" : "Occidentale →", false, () -> {
                manga = !manga; getPreferences(MODE_PRIVATE).edit().putBoolean("manga", manga).apply(); showReader();
            }));
        } else {
            controls.addView(button("A−", false, () -> changeFont(false)));
            controls.addView(button("A+", false, () -> changeFont(true)));
        }
        controls.addView(button(readerDark ? "Chiaro" : "Scuro", false, () -> {
            readerDark = !readerDark; getPreferences(MODE_PRIVATE).edit().putBoolean("dark", readerDark).apply(); showReader();
        }));
        rule(root);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = column(); body.setPadding(dp(24), dp(24), dp(24), dp(32)); scroll.addView(body);
        if (reading.format.equals("CBZ")) {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(store.original(reading))) {
                byte[] bytes = EbookParser.entry(zip, pages.get(page)); Bitmap bitmap = decode(null, bytes, 2400);
                if (bitmap == null) throw new IOException("Immagine non leggibile");
                ImageView image = new ImageView(this); image.setImageBitmap(bitmap); image.setAdjustViewBounds(true);
                image.setContentDescription("Pagina " + (page + 1)); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                body.addView(image, new LinearLayout.LayoutParams(-1, -2));
            } catch (Exception e) { body.addView(text("Pagina non disponibile: " + message(e), 16, ink, false)); }
        } else {
            TextView bookText = text(pages.get(page), fontSize, ink, true); bookText.setTextIsSelectable(true);
            bookText.setLineSpacing(dp(6), 1.25f); body.addView(bookText);
        }
        LinearLayout footer = column(); footer.setPadding(dp(16), dp(10), dp(16), dp(14)); rule(root); root.addView(footer);
        double fraction;
        if (reading.format.equals("CBZ")) fraction = (page + 1.0) / pages.size();
        else {
            long total = 0, before = 0;
            for (int i = 0; i < pages.size(); i++) { total += pages.get(i).length(); if (i < page) before += pages.get(i).length(); }
            fraction = total == 0 ? 0 : (double)before / total;
        }
        footer.addView(text("Pagina " + (page + 1) + " di " + pages.size() + " · " + Math.round(fraction * 100) + "%", 12, readerDark ? LINE : MUTED, false)); gap(footer, 8);
        LinearLayout turns = row(); footer.addView(turns);
        boolean reverse = manga && reading.format.equals("CBZ");
        Button previous = button(reverse ? "Seguente ←" : "‹ Precedente", false, () -> turn(reverse ? 1 : -1));
        previous.setEnabled(reverse ? page + 1 < pages.size() : page > 0);
        turns.addView(previous, new LinearLayout.LayoutParams(0, -2, 1));
        Button next = button(reverse ? "→ Precedente" : "Seguente ›", true, () -> turn(reverse ? -1 : 1));
        next.setEnabled(reverse ? page > 0 : page + 1 < pages.size()); turns.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        if (page + 1 == pages.size() && !reading.status.equals("Letto")) {
            gap(footer, 6); footer.addView(button("Segna come letto", false, () -> { reading.status = Book.READ; Book.applyStatusEffects(reading, java.time.LocalDate.now().toString()); rememberPage(); showReader(); }));
        }
    }

    private void changeFont(boolean increase) {
        fontSize = Math.max(8, Math.min(80, Math.round(fontSize * (increase ? 1.2f : .8f))));
        getPreferences(MODE_PRIVATE).edit().putFloat("fontSize", fontSize).apply(); showReader();
    }

    private boolean searching() { return !query.isEmpty() && !tab.equals("Mappa"); }
    private boolean canGoBack() { return onlineBook != null || detailId != null || drawer != null || reading != null || !destination.equals("Home"); }
    private void updateBack() { back.setEnabled(store != null && canGoBack()); }
    /**
     * Back: a page opened inside another (a book, the sheet of a book online, a settings page, a saga's map, the
     * reader) goes back to it; every main page goes straight to Home.
     */
    private void goBack() {
        if (drawer != null) closeDrawer();
        else if (reading != null) { Book b = reading; rememberPage(); details(b); }
        else if (onlineBook != null) closeOnlineSheet();
        else if (detailId != null) { Book b=store.find(detailId); if(editingPage && b!=null) details(b); else showLibrary(); }
        else if (destination.equals("Impostazioni") && !settingsSection.isEmpty()) { settingsSection = settingsSection.equals("tema") ? "aspetto" : ""; showCompanion(); }
        else if (destination.equals("Biblioteca") && tab.equals("Saghe") && !openSaga.isEmpty()) { openSaga = ""; showLibrary(); }
        else if (!destination.equals("Home")) { tab = "Tutti i libri"; openSaga = ""; navigate("Home"); }
        updateBack();
    }
    @Override protected void onStop() {
        if (reading != null) rememberPage(); persistTimer(); stoppedAt = android.os.SystemClock.elapsedRealtime();
        if (store != null) stoppedVersion = store.version();
        // Theme, goals or books may have changed: the home screen widgets redraw from the saved data.
        app.atlante.library.widget.Widgets.refresh(this);
        super.onStop();
    }
    private long stoppedVersion = -1;

    // ---- Home screen widgets: they open a book, its progress editor, the statistics or the library -------------

    public static final String OPEN_BOOK = "app.atlante.library.OPEN_BOOK", OPEN_PROGRESS = "app.atlante.library.OPEN_PROGRESS",
        OPEN_STATS = "app.atlante.library.OPEN_STATS", OPEN_LIBRARY = "app.atlante.library.OPEN_LIBRARY", EXTRA_BOOK = "bookId",
        OPEN_OPERATIONS = "app.atlante.library.OPEN_OPERATIONS";
    private Intent widgetIntent;

    /** The widgets reuse the running app (singleTop): the request is handled when the screen is back in front. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (isWidgetIntent(intent)) widgetIntent = intent;
        else if (app.atlante.library.sync.HardcoverAuth.isCallback(intent.getData()) && store != null) finishHardcoverLogin(intent.getData());
    }
    private static boolean isWidgetIntent(Intent i) { return i != null && i.getAction() != null && i.getAction().startsWith("app.atlante.library.OPEN_"); }

    @Override protected void onResume() {
        super.onResume();
        // Other titles of the books (original, Italian, editions) for the search, as the saga engine knows them now.
        if (worker != null) worker.execute(() -> { app.atlante.library.catalog.LibraryIndex.syncBlocking(this); app.atlante.library.saga.SagaFilters.load(this); runOnUiThread(() -> { if (searching() && reading == null && detailId == null) renderContent(); }); });
        if (store == null || widgetIntent == null) return;
        Intent i = widgetIntent; widgetIntent = null;
        String action = i.getAction();
        if (OPEN_STATS.equals(action)) { if (reading == null) navigate("Statistiche"); return; }
        if (OPEN_OPERATIONS.equals(action)) { if (reading == null) showOperations(); return; }
        if (OPEN_LIBRARY.equals(action)) { if (reading == null) navigate("Biblioteca"); return; }
        Book b = liveBook(i.getStringExtra(EXTRA_BOOK));   // a widget made before a merge opens the record that took the book in
        if (b == null) { app.atlante.library.ui.L.toast(this, "Questo libro non è più nella libreria.", Toast.LENGTH_LONG).show(); navigate("Biblioteca"); return; }
        if (reading != null) { rememberPage(); reading = null; }
        details(b);
        if (OPEN_PROGRESS.equals(action)) progressEditor(b, 0, false);
    }

    /** Back after more than 10 minutes away: Home again, unless you are reading or in the middle of an edit. */
    @Override protected void onRestart() {
        super.onRestart();
        if (store == null || reading != null || editingPage || stoppedAt == 0) return;
        if (widgetIntent != null) return;   // a widget asked for a page: onResume opens it
        // A widget changed the books while the app was in the background: draw them again.
        if (stoppedVersion >= 0 && store.version() != stoppedVersion) { Book b = liveBook(detailId); if (b != null) details(b); else showLibrary(); }
        if (android.os.SystemClock.elapsedRealtime() - stoppedAt > 10 * 60 * 1000L && !destination.equals("Home")) {
            destination = "Home"; detailId = null; closeDrawer(); showLibrary();
        }
    }
    private long stoppedAt;

    /** Restart for a theme change, staying on the current page. */
    private void recreateHere() { getPreferences(MODE_PRIVATE).edit().putBoolean("keepPageOnce", true).putString("settingsSectionOnce", settingsSection).commit(); recreate(); }
    @Override protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null); worker.shutdown(); catalogWorker.shutdown(); coverWorker.shutdown(); covers.shutdown(); super.onDestroy();
    }
    private void animateIn(View view) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        view.setAlpha(0); view.setTranslationY(dp(10));
        view.animate().alpha(1).translationY(0).setDuration(200).start();
    }
    private void navigate(String next) {
        app.atlante.library.diagnostics.CrashLog.step("pagina: " + next);
        if (grid != null && grid.getLayoutManager() != null && detailId == null) libraryPositions.put(destination, grid.getLayoutManager().onSaveInstanceState());
        if (companionScroll != null && detailId == null) pagePositions.put(destination, companionScroll.getScrollY());
        companionScroll = null; grid = null; body = null; detailId = null;
        query = "";   // a page always opens with an empty search: the previous text does not come back
        destination = next; getPreferences(MODE_PRIVATE).edit().putString("destination",next).apply(); showLibrary();
    }
    private void addBottomNav() {
        com.google.android.material.bottomnavigation.BottomNavigationView nav = new com.google.android.material.bottomnavigation.BottomNavigationView(this);
        String[] names={"Home","Biblioteca","Scopri","Statistiche","Sync"};
        int[] icons={R.drawable.ic_home,R.drawable.ic_library,R.drawable.ic_discover,R.drawable.ic_stats,R.drawable.ic_sync};
        nav.setLabelVisibilityMode(com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED);
        nav.setBackgroundColor(WHITE);
        int[][] states={{android.R.attr.state_checked},{}};
        android.content.res.ColorStateList tint=new android.content.res.ColorStateList(states,new int[]{INK,MUTED});
        nav.setItemIconTintList(tint); nav.setItemTextColor(tint);
        for(int i=0;i<names.length;i++) { nav.getMenu().add(0,i+1,i,names[i]).setIcon(icons[i]); if(names[i].equals(destination)) nav.setSelectedItemId(i+1); }
        // A page that is none of the five (CrossPoint): no item looks selected.
        if(!Arrays.asList(names).contains(destination)) { nav.getMenu().setGroupCheckable(0,false,true); nav.setItemActiveIndicatorEnabled(false); nav.setItemTextAppearanceActiveBoldEnabled(false);
            android.content.res.ColorStateList muted=android.content.res.ColorStateList.valueOf(MUTED); nav.setItemIconTintList(muted); nav.setItemTextColor(muted); }
        nav.setOnItemSelectedListener(item -> { String next=names[item.getItemId()-1]; if(!destination.equals(next)) navigate(next); return true; });
        // Biblioteca again from Saghe, Mappa, Universi or a search: back to the whole library.
        nav.setOnItemReselectedListener(item -> { if(names[item.getItemId()-1].equals("Biblioteca") && destination.equals("Biblioteca") && (!statusPage() || !query.isEmpty())) { tab="Tutti i libri"; openSaga=""; query=""; showLibrary(); } });
        nav.setOnApplyWindowInsetsListener((v,insets)->insets); // root already pads for the system bars
        nav.setPadding(0,0,0,0);
        // Repairs in the background: a slim bar above the navigation (nothing when no repair runs or waits).
        if (store != null) root.addView(app.atlante.library.ui.compose.RepairViews.bar(this, palette, this::showOperations), new LinearLayout.LayoutParams(-1,-2));
        View hairline=new View(this); hairline.setBackgroundColor(LINE); root.addView(hairline,new LinearLayout.LayoutParams(-1,dp(1)));
        root.addView(nav,new LinearLayout.LayoutParams(-1,-2));
    }
    /** Top bar of a companion page: a small kicker over a serif title (design 1b / 5a), then the scrolling panel. */
    private LinearLayout companionPage(String kicker, String title) {
        reading=null; pages=null; body=null; grid=null; detailId=null; base(PAPER);
        LinearLayout top=row(); top.setPadding(dp(12),dp(6),dp(12),dp(4)); root.addView(top);
        top.addView(iconButton(R.drawable.ic_menu,"Apri menu laterale",this::openDrawer));
        top.addView(pageHeading(kicker,title),new LinearLayout.LayoutParams(0,-2,1));
        top.addView(iconButton(R.drawable.ic_settings,"Impostazioni",this::settings));
        companionScroll=new ScrollView(this); companionScroll.setFillViewport(true); root.addView(companionScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout panel=column(); panel.setPadding(dp(16),dp(8),dp(16),dp(24)); companionScroll.addView(panel);
        return panel;
    }
    /** Kicker (small, accent, spaced capitals) over a serif title: the shared page heading of the redesign. */
    LinearLayout pageHeading(String kicker,String title) {
        LinearLayout heading=column(); heading.setPadding(dp(8),0,0,0);
        if(!kicker.isEmpty()) { TextView k=sectionLabel(kicker); k.setTextSize(11); heading.addView(k); }
        TextView t=text(title,kicker.isEmpty()?22:20,INK,true); t.setMaxLines(1); t.setEllipsize(TextUtils.TruncateAt.END); heading.addView(t);
        return heading;
    }
    /** A destination drawn with Jetpack Compose, between the usual top bar and bottom navigation. */
    private void composePage(View content) {
        reading=null; pages=null; body=null; grid=null; detailId=null; companionScroll=null; base(PAPER);
        LinearLayout top=row(); top.setPadding(dp(12),dp(6),dp(12),dp(8)); root.addView(top);
        top.addView(iconButton(R.drawable.ic_menu,"Apri menu laterale",this::openDrawer));
        top.addView(destination.equals("Scopri")?pageHeading("SCOPRI","La prossima storia."):destination.equals("Sync")?pageHeading("SYNC","Invia e ricevi."):destination.equals("CrossPoint")?pageHeading("CROSSPOINT","Il tuo lettore e-ink"):pageHeading("","Statistiche"),new LinearLayout.LayoutParams(0,-2,1));
        top.addView(iconButton(R.drawable.ic_settings,"Impostazioni",this::settings));
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        addBottomNav(); updateBack();
    }

    private void showCompanion() {
        if(destination.equals("Statistiche")) {
            android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
            app.atlante.library.model.ReadingGoals g=new app.atlante.library.model.ReadingGoals(prefs);
            composePage(app.atlante.library.ui.compose.StatisticsView.create(this,palette,store.snapshot(),g.getYearOn()?g.getYearBooks():0,g.getMonthOn()?g.getMonthBooks():0,motion(),this::goals,g,()->startRepair(app.atlante.library.catalog.RepairWorker.GENRES)));
            return;
        }
        if(destination.equals("Scopri")) {
            composePage(app.atlante.library.ui.compose.DiscoverView.create(this,palette,sagaHost,this::openLink,this::collections));
            return;
        }
        if(destination.equals("Impostazioni")) { LinearLayout p=companionPage("IMPOSTAZIONI",settingsSection.isEmpty()?"Impostazioni":settingsTitle(settingsSection)); settingsPage(p); updateBack(); return; }
        if(destination.equals("Sync")) { composePage(syncView()); return; }
        if(destination.equals("CrossPoint")) { composePage(crossPointView()); return; }
        String day=java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM",app.atlante.library.ui.L.locale())).toUpperCase(app.atlante.library.ui.L.locale());
        LinearLayout panel=companionPage(day,"Buona lettura.");
        home(panel); sessionBar();
        addBottomNav(); updateBack(); animateIn(panel);
        int y=pagePositions.getOrDefault(destination,0); companionScroll.post(() -> { if(companionScroll!=null) companionScroll.scrollTo(0,y); });
    }
    private LinearLayout surface(LinearLayout parent, String label) {
        LinearLayout box=column(); box.setPadding(dp(18),dp(18),dp(18),dp(18));
        MaterialCardView c=card(box); parent.addView(c,new LinearLayout.LayoutParams(-1,-2));
        if(!label.isEmpty()) { box.addView(sectionLabel(label)); gap(box,12); } gap(parent,16); return box;
    }
    private void headline(LinearLayout p,String label) { gap(p,8); p.addView(text(label,24,INK,true)); gap(p,14); }
    private int progress(Book b) { return b.totalPages>0?Math.min(100,Math.round(100f*b.currentPage/b.totalPages)):b.percent; }
    /** Home (design 1b): one "Oggi" card with ring, week and rotta; the nightstand as a carousel with timer and +10. */
    private void home(LinearLayout p) {
        List<Book> all=store.snapshot(); java.time.LocalDate today=java.time.LocalDate.now();
        long[] totals=ReadingJournal.totals(all,today);
        app.atlante.library.model.ReadingGoals goals=new app.atlante.library.model.ReadingGoals(getPreferences(MODE_PRIVATE));
        todayCard(p,all,today,totals,goals);

        List<Book> reading=new ArrayList<>(); for(Book b:all) if(b.status.equals(Book.READING)) reading.add(b);
        sectionHeader(p,"Sul tuo comodino",reading.isEmpty()?"":reading.size()+" in lettura",null);
        if(reading.isEmpty()) { LinearLayout box=surface(p,""); box.addView(text("La prossima pagina ti aspetta.",20,INK,true)); gap(box,8); box.addView(text("Scegli un libro dalla biblioteca e inizia la tua lettura.",14,MUTED,false)); box.addView(button("Apri la biblioteca",true,() -> navigate("Biblioteca"))); }
        else {
            HorizontalScrollView strip=carousel(p); LinearLayout cards=row(); cards.setGravity(Gravity.TOP); strip.addView(cards);
            int width=Math.min(dp(300),getResources().getDisplayMetrics().widthPixels-dp(56));
            for(int i=0;i<reading.size();i++) { LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(reading.size()==1?getResources().getDisplayMetrics().widthPixels-dp(32):width,-2); if(i>0) lp.setMargins(dp(12),0,0,0); cards.addView(nightstandCard(reading.get(i)),lp); }
        }

        sectionHeader(p,"Attività recente","",null);
        recentActivity(p,4);

        List<Book> picks=new ArrayList<>(); Set<String> genres=new HashSet<>(), authors=new HashSet<>();
        for(Book b:all) if(b.status.equals(Book.READ) || ReadingJournal.rating(b)>=4) { if(!b.genre.isEmpty()) genres.add(b.genre); authors.add(b.author); }
        for(Book b:all) if(b.status.equals(Book.TO_READ) && (genres.contains(b.genre)||authors.contains(b.author)) && picks.size()<8) picks.add(b);
        sectionHeader(p,"Scelti dalla tua biblioteca","",null);
        TextView why=text(picks.isEmpty()?"Completa e valuta qualche libro per trovare affinità nel tuo catalogo.":"Per generi, autori e voti delle tue letture.",13,MUTED,false); why.setPadding(dp(4),0,dp(4),dp(12)); p.addView(why);
        if(!picks.isEmpty()) {
            HorizontalScrollView strip=carousel(p); LinearLayout row=row(); row.setGravity(Gravity.TOP); strip.addView(row);
            for(int i=0;i<picks.size();i++) { Book b=picks.get(i); LinearLayout item=column(); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(96),-2); if(i>0) lp.setMargins(dp(12),0,0,0); row.addView(item,lp);
                item.addView(coverView(b,dp(96),dp(144)),new LinearLayout.LayoutParams(dp(96),dp(144))); gap(item,6);
                TextView t=text(b.title,13,INK,true); t.setMaxLines(2); t.setEllipsize(TextUtils.TruncateAt.END); item.addView(t);
                TextView a=text(b.author,11,MUTED,false); a.setMaxLines(1); a.setEllipsize(TextUtils.TruncateAt.END); item.addView(a);
                item.setOnClickListener(v->details(b)); item.setContentDescription(b.title+", "+b.author); }
        }
    }
    /** Section title in serif with an optional accent note (or link) on the right. */
    void sectionHeader(LinearLayout p,String title,String note,Runnable action) {
        LinearLayout line=row(); line.setGravity(Gravity.BOTTOM); line.setPadding(dp(4),dp(26),dp(4),dp(12)); p.addView(line,new LinearLayout.LayoutParams(-1,-2));
        line.addView(text(title,22,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        if(!note.isEmpty()) { TextView n=text(note,13,SAGE,false); n.setTypeface(Typeface.DEFAULT_BOLD); if(action!=null) pill(n); line.addView(n); if(action!=null) { n.setOnClickListener(v->action.run()); n.setMinHeight(dp(48)); n.setGravity(Gravity.CENTER_VERTICAL); } }
    }
    /** A horizontal strip that bleeds to the screen edges of a 16dp-padded panel. */
    private HorizontalScrollView carousel(LinearLayout p) {
        HorizontalScrollView strip=new HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false); strip.setClipToPadding(false);
        strip.setPadding(dp(16),0,dp(16),dp(4)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(-dp(16),0,-dp(16),0); p.addView(strip,lp); return strip;
    }
    /** A cover with the title as fallback, rounded and lightly shadowed. */
    View coverView(Book b,int width,int height) {
        FrameLayout art=new FrameLayout(this); art.setClipToOutline(true); GradientDrawable shape=background(LINE,Color.TRANSPARENT); shape.setCornerRadius(dp(6)); art.setBackground(shape); art.setElevation(dp(3));
        TextView fallback=text(b.title,11,SAGE,true); fallback.setPadding(dp(6),dp(8),dp(6),dp(8)); art.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
        ImageView img=new ImageView(this); img.setScaleType(ImageView.ScaleType.CENTER_CROP); img.setContentDescription("Copertina di "+b.title); art.addView(img,new FrameLayout.LayoutParams(-1,-1));
        covers.thumbnail(b.id,store.cover(b),Math.max(width,height),img,fallback); return art;
    }
    private void todayCard(LinearLayout p,List<Book> all,java.time.LocalDate today,long[] totals,app.atlante.library.model.ReadingGoals goals) {
        LinearLayout box=column(); box.setPadding(dp(18),dp(18),dp(18),dp(18)); MaterialCardView c=card(box); c.setRadius(dp(20)); p.addView(c,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout top=row(); box.addView(top);
        int daily=goals.getDailyPagesOn()?goals.getDailyPages():0;
        FrameLayout ring=new FrameLayout(this); top.addView(ring,new LinearLayout.LayoutParams(dp(92),dp(92)));
        CircularProgressIndicator circle=new CircularProgressIndicator(this); circle.setIndeterminate(false); circle.setIndicatorInset(0); circle.setIndicatorSize(dp(92)); circle.setTrackThickness(dp(9)); circle.setTrackCornerRadius(dp(5));
        circle.setTrackColor(LINE); circle.setIndicatorColor(SAGE); circle.setProgressCompat(daily>0?(int)Math.min(100,100*totals[0]/daily):0,motion());
        ring.addView(circle,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        LinearLayout center=column(); center.setGravity(Gravity.CENTER); ring.addView(center,new FrameLayout.LayoutParams(-1,-1));
        TextView pagesToday=text(Long.toString(totals[0]),26,INK,true); pagesToday.setGravity(Gravity.CENTER); center.addView(pagesToday,new LinearLayout.LayoutParams(-2,-2));
        TextView of=text(daily>0?"di "+daily+" pag.":"pagine",11,MUTED,false); of.setGravity(Gravity.CENTER); center.addView(of,new LinearLayout.LayoutParams(-2,-2));
        ring.setContentDescription(totals[0]+" pagine oggi"+(daily>0?" su "+daily:""));
        LinearLayout stats=column(); stats.setPadding(dp(18),0,0,0); top.addView(stats,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout two=row(); stats.addView(two,new LinearLayout.LayoutParams(-1,-2));
        smallMetric(two,Long.toString(totals[1]/60),"minuti oggi",INK); smallMetric(two,Integer.toString(ReadingJournal.streak(all,today)),"giorni di fila",COPPER);
        gap(stats,12);
        // This week, Monday to Sunday: copper today, accent on days with reading, line otherwise.
        LinearLayout week=row(); stats.addView(week,new LinearLayout.LayoutParams(-1,-2));
        java.time.LocalDate monday=today.minusDays(today.getDayOfWeek().getValue()-1); String[] names=app.atlante.library.ui.L.weekInitials().toArray(new String[0]);
        for(int i=0;i<7;i++) { java.time.LocalDate d=monday.plusDays(i); long[] t=ReadingJournal.totals(all,d); boolean read=t[0]>0||t[1]>0;
            LinearLayout cell=column(); cell.setGravity(Gravity.CENTER_HORIZONTAL); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1); if(i>0) lp.setMargins(dp(5),0,0,0); week.addView(cell,lp);
            View bar=new View(this); GradientDrawable g=background(d.equals(today)&&read?COPPER:read?SAGE:LINE,Color.TRANSPARENT); g.setCornerRadius(dp(3)); bar.setBackground(g); cell.addView(bar,new LinearLayout.LayoutParams(-1,dp(6)));
            TextView l=text(names[i],10,MUTED,false); l.setPadding(0,dp(4),0,0); cell.addView(l); }
        week.setContentDescription("Giorni di lettura di questa settimana");
        gap(box,16); rule(box); gap(box,14);
        // La tua rotta in one line: books finished this year against the yearly goal.
        int yearly=goals.getYearOn()?goals.getYearBooks():0, done=ReadingJournal.completed(all,today,false);
        LinearLayout line=row(); box.addView(line,new LinearLayout.LayoutParams(-1,-2));
        line.addView(text("La tua rotta "+today.getYear()+" · "+(yearly>0?done+" di "+yearly+" libri":done+(done==1?" libro letto":" libri letti")),13,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));
        if(yearly>0) { line.addView(text(Math.min(100,done*100/yearly)+"%",13,SAGE,false)); gap(box,6);
            LinearProgressIndicator bar=new LinearProgressIndicator(this); bar.setIndicatorColor(SAGE); bar.setTrackColor(LINE); bar.setTrackThickness(dp(6)); bar.setTrackCornerRadius(dp(3)); solid(bar); bar.setProgressCompat(Math.min(100,done*100/yearly),motion()); box.addView(bar); }
        box.setOnClickListener(v->goals()); box.setContentDescription("Oggi e la tua rotta. Tocca per gli obiettivi.");
    }
    private void smallMetric(LinearLayout row,String value,String label,int color) {
        LinearLayout item=column(); row.addView(item,new LinearLayout.LayoutParams(0,-2,1));
        item.addView(text(value,22,color,true)); item.addView(text(label,13,MUTED,false));
    }
    /** One book on the nightstand: cover, percentage, a Leggi button that starts or shows the timer, +10 pages. */
    private View nightstandCard(Book b) {
        LinearLayout box=column(); box.setPadding(dp(16),dp(16),dp(16),dp(16)); MaterialCardView c=card(box); c.setRadius(dp(20));
        LinearLayout line=row(); line.setGravity(Gravity.TOP); box.addView(line,new LinearLayout.LayoutParams(-1,-2));
        line.addView(coverView(b,dp(84),dp(126)),new LinearLayout.LayoutParams(dp(84),dp(126)));
        LinearLayout info=column(); info.setPadding(dp(14),0,0,0); line.addView(info,new LinearLayout.LayoutParams(0,dp(126),1));
        TextView title=text(b.title,18,INK,true); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); info.addView(title);
        TextView author=text(b.author,13,MUTED,false); author.setMaxLines(1); author.setEllipsize(TextUtils.TruncateAt.END); info.addView(author);
        info.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        info.addView(text(progress(b)+"%",26,SAGE,true));
        info.addView(text(b.totalPages>0?"pag. "+b.currentPage+" di "+b.totalPages:progressLabel(b),13,MUTED,false));
        line.setOnClickListener(v->details(b));
        gap(box,14); box.addView(progressBar(b)); gap(box,14);
        LinearLayout actions=row(); box.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        boolean running=b.id.equals(timerBook);
        MaterialButton read=button(running?elapsedLabel(timerSeconds()):"Leggi",0,()->{ if(b.id.equals(timerBook)) timerScreen(b); else startTimer(b); });
        read.setIconResource(running?R.drawable.ic_pause:R.drawable.ic_play); read.setIconTint(android.content.res.ColorStateList.valueOf(ON_ACCENT));
        if(running) read.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COPPER));
        read.setContentDescription(running?"Sessione in corso, apri il timer":"Avvia il timer di lettura per "+b.title);
        actions.addView(read,new LinearLayout.LayoutParams(0,-2,1));
        MaterialButton add=button("+10",R.style.Atlante_Button_Tonal,()->addPages(b,10)); add.setContentDescription("Segna 10 pagine lette");
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(72),-2); lp.setMargins(dp(8),0,0,0); actions.addView(add,lp);
        return c;
    }
    /** +10 from Home: moves the page forward and records a session without time, like the progress editor does. */
    private void addPages(Book b,int count) {
        try {
            Book c=Book.from(b.json()); c.currentPage=c.totalPages>0?Math.min(c.totalPages,b.currentPage+count):b.currentPage+count;
            if(c.totalPages>0) c.percent=Math.round(100f*c.currentPage/c.totalPages);
            if(c.totalPages>0&&c.currentPage==c.totalPages) c.status=Book.READ; else if(!c.status.equals(Book.READ)) c.status=Book.READING;
            Book.applyStatusEffects(c,java.time.LocalDate.now().toString());
            if(c.currentPage!=b.currentPage) ReadingJournal.session(c,b.currentPage,c.currentPage,0,java.time.LocalDate.now());
            store.replace(c);
            if(companionScroll!=null) pagePositions.put(destination,companionScroll.getScrollY());
            showCompanion();
        } catch(Exception e) { error(e); }
    }
    /** While a timed session runs, a dark bar above the navigation brings back to it. */
    private void sessionBar() {
        if(timerBook==null) return; Book active=liveBook(timerBook); if(active==null) return;
        if(!active.id.equals(timerBook)){timerBook=active.id;persistTimer();}
        LinearLayout bar=row(); bar.setPadding(dp(16),dp(8),dp(8),dp(8)); bar.setBackground(background(INK,Color.TRANSPARENT));
        ((GradientDrawable)bar.getBackground()).setCornerRadius(dp(16));
        View dot=new View(this); dot.setBackground(background(COPPER,Color.TRANSPARENT)); ((GradientDrawable)dot.getBackground()).setCornerRadius(dp(4)); bar.addView(dot,new LinearLayout.LayoutParams(dp(8),dp(8)));
        LinearLayout info=column(); info.setPadding(dp(12),0,dp(8),0); bar.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        TextView title=text("Sessione · "+active.title,13,PAPER,false); title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END); info.addView(title);
        timerLabel=text(elapsedLabel(timerSeconds()),12,PAPER,false); timerLabel.setTypeface(Typeface.MONOSPACE); timerLabel.setAlpha(.8f); info.addView(timerLabel);
        MaterialButton open=button("Concludi",0,()->timerScreen(active)); open.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PAPER)); open.setTextColor(INK); bar.addView(open);
        bar.setOnClickListener(v->timerScreen(active));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(dp(12),dp(4),dp(12),dp(8)); root.addView(bar,lp);
        uiHandler.removeCallbacks(timerTick); uiHandler.post(timerTick);
    }
    private boolean motion() { return android.os.Build.VERSION.SDK_INT<26 || android.animation.ValueAnimator.areAnimatorsEnabled(); }
    private String estimate(Book b) {
        long pages=0,seconds=0; org.json.JSONArray a=ReadingJournal.array(b,"sessions");
        for(int i=0;i<a.length();i++) { org.json.JSONObject s=a.optJSONObject(i); if(s!=null && s.optLong("seconds")>0 && s.optInt("pages")>0) { pages+=s.optInt("pages"); seconds+=s.optLong("seconds"); } }
        if(pages<10 || seconds<60 || b.totalPages<=b.currentPage) return "";
        long minutes=Math.max(1,(b.totalPages-b.currentPage)*seconds/pages/60); return " · circa "+(minutes>=60?minutes/60+" h "+minutes%60+" min":minutes+" min");
    }
    private void backupMenu() {
        dialogBuilder().setTitle("Backup").setItems(new String[]{"Esporta backup (ZIP)", "Ripristina un backup o importa un catalogo"}, (d, w) -> { if (w == 0) exportBackup(); else chooseBackup(); })
            .setNegativeButton("Chiudi", null).show();
    }

    /** Goals are edited in the settings ("Obiettivi di lettura"); this opens them. */
    private void goals() { settings(); settingsSection = "obiettivi"; showCompanion(); }

    private void openLink(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); } catch(ActivityNotFoundException e) { error(new IOException("Nessuna app disponibile per aprire il collegamento.")); } }
    private void collections() {
        TreeSet<String> names=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for(Book b:store.snapshot()) { org.json.JSONArray a=ReadingJournal.array(b,"collections"); for(int i=0;i<a.length();i++) names.add(a.optString(i)); }
        String raw=getPreferences(MODE_PRIVATE).getString("collections",""); for(String name:raw.split("\n")) if(!name.isEmpty()) names.add(name);
        ArrayList<String> items=new ArrayList<>(names); items.add("+ Nuova collezione");
        dialogBuilder().setTitle("Le tue collezioni").setItems(items.toArray(new String[0]),(d,w)->{
            if(w==items.size()-1) { LinearLayout p=column(); p.setPadding(dp(24),0,dp(24),0); EditText input=field(p,"Nome della collezione","");
                dialogBuilder().setTitle("Nuova collezione").setView(p).setNegativeButton("Annulla",null).setPositiveButton("Crea",(dd,ww)->{String n=input.getText().toString().trim().replace('\n',' '); if(!n.isEmpty()) { names.add(n);getPreferences(MODE_PRIVATE).edit().putString("collections",TextUtils.join("\n",names)).apply(); selectCollection(n); }}).show();
            } else selectCollection(items.get(w));
        }).setNegativeButton("Chiudi",null).show();
    }
    private void selectCollection(String name) { destination="Biblioteca"; tab="Scaffale: "+name; query=""; showLibrary(); }
    private View companionReadingCard(Book b) {
        LinearLayout p=column(); p.setPadding(dp(18),dp(18),dp(18),dp(18)); p.addView(sectionLabel("LA TUA LETTURA · TENTATIVO "+ReadingJournal.attempt(b))); gap(p,12);
        LinearLayout line=row(); p.addView(line);
        CircularProgressIndicator circle=new CircularProgressIndicator(this); circle.setIndeterminate(false); circle.setIndicatorColor(SAGE); circle.setTrackColor(LINE); circle.setIndicatorSize(dp(64)); circle.setProgressCompat(progress(b),motion()); circle.setContentDescription(progress(b)+" per cento"); line.addView(circle);
        LinearLayout info=column(); info.setPadding(dp(16),0,0,0); line.addView(info,new LinearLayout.LayoutParams(0,-2,1)); info.addView(text(progress(b)+"%",28,INK,true)); info.addView(text(progressLabel(b),14,MUTED,false)); if(b.totalPages>0) info.addView(text(Math.max(0,b.totalPages-b.currentPage)+" pagine rimaste"+estimate(b),12,SAGE,false));
        if(!b.journal.optString("chapter").isEmpty()) info.addView(text("Capitolo: "+b.journal.optString("chapter"),13,INK,false));
        gap(p,12); if(!b.started.isEmpty()) p.addView(text("Iniziato il "+italianDate(b.started),13,MUTED,false));
        if(!b.finished.isEmpty()) p.addView(text("Terminato il "+italianDate(b.finished),13,MUTED,false));
        gap(p,12);
        // One tap changes the state (In lettura sets the start date, Letto the end date and last page).
        MaterialButtonToggleGroup states=statusToggle(b.status); p.addView(states,new LinearLayout.LayoutParams(-1,-2));
        states.addOnButtonCheckedListener((group,id,checked)->{ if(!checked) return;
            String status=Book.STATES[group.indexOfChild(group.findViewById(id))];
            if(!status.equals(b.status)) { changeStatus(b,status); renderContent(); details(b); } });
        gap(p,8);
        p.addView(button("Aggiorna avanzamento",true,()->progressEditor(b,0,false)));
        p.addView(button("Date, voto e note",R.style.Atlante_Button_Text,()->readingDialog(b)));
        return card(p);
    }
    /** The book's other actions (⋮): a sheet with icons. Preferito is the heart beside the cover. */
    private void bookActions(Book b) {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        if (sheet.getWindow() != null) app.atlante.library.ui.L.watch(sheet.getWindow().getDecorView());
        LinearLayout p = column(); p.setPadding(dp(16), dp(12), dp(16), dp(24));
        TextView head = text(b.title, 20, INK, true); head.setPadding(dp(4), 0, dp(4), dp(12)); head.setMaxLines(2); head.setEllipsize(TextUtils.TruncateAt.END); p.addView(head);
        LinearLayout list = listCard(p);
        listRow(list, R.drawable.ic_edit, "Modifica scheda", "Titolo, autore, saga, pagine", () -> { sheet.dismiss(); edit(b); });
        listRow(list, R.drawable.ic_cloud, "Completa i dati online", "Copertina, trama, editore, ISBN", () -> { sheet.dismiss(); completeMetadata(b); });
        listRow(list, R.drawable.ic_library, "Collezioni", "Le tue liste", () -> { sheet.dismiss(); editCollections(b); });
        listRow(list, R.drawable.ic_book, "Inizia una rilettura", "Le letture precedenti restano", () -> { sheet.dismiss(); reread(b); });
        if (!b.format.equals("CATALOGO")) listRow(list, R.drawable.ic_open_in_new, "Apri con KOReader", "Il file in un altro lettore", () -> { sheet.dismiss(); openKoreader(b); });
        if (!b.format.equals("CATALOGO") && store.original(b).isFile()) listRow(list, R.drawable.ic_upload, "Invia a CrossPoint", "Al lettore e-ink, via Wi-Fi", () -> { sheet.dismiss(); crossPointSend = b.id; navigate("CrossPoint"); });
        ScrollView scroll = new ScrollView(this); scroll.addView(p); scroll.setBackgroundColor(WHITE);
        sheet.setContentView(scroll); sheet.show();
    }
    private void reread(Book b) {
        if(b.id.equals(timerBook)) { dialogBuilder().setTitle("Sessione in corso").setMessage("Concludi o scarta la sessione prima di iniziare una rilettura.").setPositiveButton("Apri sessione",(dd,ww)->timerScreen(b)).setNegativeButton("Annulla",null).show(); return; }
        else dialogBuilder().setTitle("Iniziare una nuova lettura?").setMessage("Sessioni e recensione precedenti vengono conservate. Il nuovo tentativo riparte da pagina zero.").setNegativeButton("Annulla",null).setPositiveButton("Inizia",(dd,ww)->journalEdit(b,c->{
                org.json.JSONArray a=ReadingJournal.array(c,"attempts"); a.put(new org.json.JSONObject().put("number",ReadingJournal.attempt(c)).put("started",c.started).put("finished",c.finished).put("page",c.currentPage).put("percent",c.percent)); ReadingJournal.put(c,"attempts",a); ReadingJournal.put(c,"attempt",ReadingJournal.attempt(c)+1); c.currentPage=0;c.percent=0;c.page=0;c.started=java.time.LocalDate.now().toString();c.finished="";c.status=Book.READING;
            })).show();
    }
    private interface BookMutation { void apply(Book b) throws Exception; }
    private void journalEdit(Book b,BookMutation edit) {
        try { Book copy=Book.from(b.json()); edit.apply(copy); store.replace(copy); details(copy); } catch(Exception e) {error(e);}
    }
    private void editCollections(Book b) {
        TreeSet<String> names=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for(String n:getPreferences(MODE_PRIVATE).getString("collections","").split("\n")) if(!n.isEmpty()) names.add(n);
        for(Book book:store.snapshot()) { org.json.JSONArray a=ReadingJournal.array(book,"collections"); for(int i=0;i<a.length();i++) names.add(a.optString(i)); }
        if(names.isEmpty()) { collections(); return; }
        String[] labels=names.toArray(new String[0]); boolean[] checked=new boolean[labels.length]; for(int i=0;i<labels.length;i++) checked[i]=ReadingJournal.inCollection(b,labels[i]);
        dialogBuilder().setTitle("Collezioni del libro").setMultiChoiceItems(labels,checked,(d,w,on)->checked[w]=on).setNegativeButton("Annulla",null).setNeutralButton("Nuova",(d,w)->collections()).setPositiveButton("Salva",(d,w)->journalEdit(b,c->{org.json.JSONArray a=new org.json.JSONArray(); for(int i=0;i<labels.length;i++) if(checked[i]) a.put(labels[i]); ReadingJournal.put(c,"collections",a);})).show();
    }
    private void openKoreader(Book b) {
        if(!store.original(b).isFile()) {error(new IOException("Questo libro non ha un file locale. Importa prima un ebook."));return;}
        try { Uri uri=androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".files",store.original(b));
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime(b.format)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setPackage("org.koreader.launcher"); startActivity(intent);
        } catch(ActivityNotFoundException e) {dialogBuilder().setTitle("KOReader non disponibile").setMessage("Installa KOReader e riprova. Se la versione installata non accetta i file tramite selettore Android, esporta il file originale e aprilo da KOReader. Le posizioni dei due lettori restano indipendenti.").setPositiveButton("Chiudi",null).show();} catch(Exception e) {error(e);}
    }
    private void addPersonalSections(LinearLayout p,Book b) {
        gap(p,18); p.addView(sectionLabel("IL TUO VOTO")); gap(p,8);
        RatingBar stars=new RatingBar(this); stars.setNumStars(5); stars.setStepSize(.5f); stars.setRating((float)ReadingJournal.rating(b)); stars.setContentDescription("Il tuo voto, da zero a cinque stelle, mezze stelle disponibili");
        stars.setProgressTintList(android.content.res.ColorStateList.valueOf(COPPER)); p.addView(stars,new LinearLayout.LayoutParams(-2,-2));
        stars.setOnRatingBarChangeListener((rating,value,fromUser)->{if(fromUser) {try {Book c=Book.from(b.json()); ReadingJournal.put(c,"rating",(double)value); c.rating=Math.round(value);store.replace(c); b.journal=c.journal;b.rating=c.rating; if(motion()){rating.setScaleX(.96f);rating.setScaleY(.96f);rating.animate().scaleX(1).scaleY(1).setDuration(160).start();}}catch(Exception e){error(e);}}});
        headline(p,"Il tuo taccuino"); p.addView(button("Aggiungi nota o citazione",false,()->noteEditor(b,-1)));
        EditText search=field(p,"Cerca nelle note",""); LinearLayout list=column(); p.addView(list);
        Runnable refresh=()->{list.removeAllViews();String q=search.getText().toString().toLowerCase(Locale.ROOT); org.json.JSONArray a=ReadingJournal.array(b,"notes");
            for(int i=a.length()-1;i>=0;i--){org.json.JSONObject n=a.optJSONObject(i);if(n==null||!n.toString().toLowerCase(Locale.ROOT).contains(q))continue;int index=i;LinearLayout item=surface(list,n.optString("type")+" · "+n.optString("date"));item.addView(text(n.optString("body"),15,INK,false));item.addView(text("Pagina "+n.optInt("page")+" · "+n.optString("tags"),13,MUTED,false));item.addView(button("Modifica nota",R.style.Atlante_Button_Text,()->noteEditor(b,index)));}
            if(a.length()==0)list.addView(text("Pensieri, citazioni, domande: ritrovali qui, anche offline.",14,MUTED,false));}; refresh.run();
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){refresh.run();}public void afterTextChanged(Editable e){}});
    }
    private LinearLayout editorPage(String title,Book b) {
        base(PAPER); detailId=b.id; editingPage=true; timerLabel=null; uiHandler.removeCallbacks(timerTick); LinearLayout top=row();top.setPadding(dp(8),dp(4),dp(8),dp(4));root.addView(top);top.addView(iconButton(R.drawable.ic_back,"Annulla e torna al libro",()->details(b)));top.addView(text(title,22,INK,true));
        LinearLayout panel=column();panel.setPadding(dp(20),dp(16),dp(20),dp(24));ScrollView scroll=new ScrollView(this);scroll.addView(panel);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));updateBack();return panel;
    }
    private void noteEditor(Book b,int index) {
        LinearLayout p=editorPage(index<0?"Una nuova nota":"Modifica nota",b);org.json.JSONObject old=index<0?new org.json.JSONObject():ReadingJournal.array(b,"notes").optJSONObject(index);if(old==null)old=new org.json.JSONObject();
        Picker kind=new Picker(p,"Tipo",Arrays.asList("Nota","Pensiero","Citazione","Domanda","Riassunto"),old.optString("type","Nota"));
        EditText body=field(p,"Testo",old.optString("body"));body.setSingleLine(false);body.setMinLines(6);EditText page=field(p,"Pagina",Integer.toString(old.optInt("page",b.currentPage)));page.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);EditText tags=field(p,"Tag",old.optString("tags"));
        final org.json.JSONObject previous=old;
        p.addView(button("Salva nota",true,()->{if(body.getText().toString().trim().isEmpty()){body.setError("Scrivi la tua nota");return;}int ref;try{ref=Integer.parseInt(page.getText().toString());if(ref<0)throw new NumberFormatException();}catch(Exception e){page.setError("Inserisci una pagina valida");return;}final int refPage=ref;
            journalEdit(b,c->{org.json.JSONArray a=ReadingJournal.array(c,"notes");org.json.JSONObject n=new org.json.JSONObject(previous.toString()).put("type",kind.value()).put("body",body.getText().toString()).put("page",refPage).put("tags",tags.getText().toString()).put("date",previous.optString("date",java.time.LocalDate.now().toString())).put("at",previous.optLong("at",System.currentTimeMillis())).put("editedAt",System.currentTimeMillis());if(index<0)a.put(n);else a.put(index,n);ReadingJournal.put(c,"notes",a);});}));
        if(index>=0)p.addView(button("Elimina nota",false,()->dialogBuilder().setTitle("Eliminare questa nota?").setNegativeButton("Annulla",null).setPositiveButton("Elimina",(d,w)->journalEdit(b,c->{org.json.JSONArray a=ReadingJournal.array(c,"notes");a.remove(index);ReadingJournal.put(c,"notes",a);})).show()));
    }
    /** Home: the latest events as rows in one card, the day in serif on the left. */
    private void recentActivity(LinearLayout p,int limit) {
        ArrayList<org.json.JSONObject> events=activityEvents(null);
        if(events.isEmpty()) { TextView none=text("Le tue sessioni, note e letture completate appariranno qui.",14,MUTED,false); none.setPadding(dp(4),0,dp(4),0); p.addView(none); return; }
        LinearLayout box=column(); box.setPadding(dp(16),dp(4),dp(16),dp(4)); MaterialCardView c=card(box); c.setRadius(dp(20)); p.addView(c,new LinearLayout.LayoutParams(-1,-2));
        String[] months={"GEN","FEB","MAR","APR","MAG","GIU","LUG","AGO","SET","OTT","NOV","DIC"};
        for(int i=0;i<Math.min(limit,events.size());i++) { org.json.JSONObject e=events.get(i); if(i>0) rule(box);
            LinearLayout line=row(); line.setGravity(Gravity.TOP); line.setPadding(0,dp(14),0,dp(14)); box.addView(line,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout day=column(); day.setGravity(Gravity.CENTER_HORIZONTAL); line.addView(day,new LinearLayout.LayoutParams(dp(40),-2));
            String d="",m=""; try { java.time.LocalDate date=java.time.LocalDate.parse(e.optString("date")); d=Integer.toString(date.getDayOfMonth()); m=months[date.getMonthValue()-1]; } catch(Exception ignored) {}
            day.addView(text(d,20,INK,true)); TextView mon=text(m,10,MUTED,false); mon.setLetterSpacing(.08f); day.addView(mon);
            LinearLayout info=column(); info.setPadding(dp(14),0,0,0); line.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            TextView book=text(e.optString("book"),14,INK,false); book.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); info.addView(book);
            TextView label=text(e.optString("text"),13,MUTED,false); label.setMaxLines(2); label.setEllipsize(TextUtils.TruncateAt.END); info.addView(label); }
    }
    private ArrayList<org.json.JSONObject> activityEvents(Book only) {
        ArrayList<org.json.JSONObject> events=new ArrayList<>();
        for(Book b:only==null?store.snapshot():Arrays.asList(only)) {
            for(String key:new String[]{"sessions","notes"}){org.json.JSONArray a=ReadingJournal.array(b,key);for(int i=0;i<a.length();i++){org.json.JSONObject e=a.optJSONObject(i);if(e==null)continue;try{org.json.JSONObject row=new org.json.JSONObject().put("at",e.optLong("at")).put("date",e.optString("date")).put("book",b.title).put("text",key.equals("sessions")?e.optInt("from")+" → "+e.optInt("to")+" pagine · "+e.optLong("seconds")/60+" min · lettura "+e.optInt("attempt",1):e.optString("type")+": "+e.optString("body"));events.add(row);}catch(Exception ignored){}}}
            for(String date:new String[]{b.started,b.finished})if(!date.isEmpty())try{events.add(new org.json.JSONObject().put("at",java.time.LocalDate.parse(date).toEpochDay()*86400000L).put("date",date).put("book",b.title).put("text",date.equals(b.finished)?"Libro completato":"Lettura iniziata"));}catch(Exception ignored){}
            org.json.JSONArray attempts=ReadingJournal.array(b,"attempts");for(int i=0;i<attempts.length();i++){org.json.JSONObject e=attempts.optJSONObject(i);if(e!=null)try{events.add(new org.json.JSONObject().put("date",e.optString("finished",e.optString("started"))).put("book",b.title).put("text","Lettura precedente · tentativo "+e.optInt("number")+" · "+e.optInt("page")+" pagine").put("at",0));}catch(Exception ignored){}}
        }
        events.sort((a,b)->Long.compare(b.optLong("at"),a.optLong("at")));
        return events;
    }
    /**
     * Where you are, with wheels instead of typing. The small Pagina / % / Pagine lette switch is remembered;
     * minutes come from the timer when the session was timed.
     */
    private void progressEditor(Book b,long seconds,boolean timed) {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        LinearLayout p=editorPage(timed?"Concludi la sessione":"Dove sei arrivato?",b);
        p.addView(text(b.title,24,INK,true)); p.addView(text(progressLabel(b),14,MUTED,false)); gap(p,16);
        // Horizontal wheels (Compose): Pagine, Percentuale, Pagine lette; synced when the total is known.
        app.atlante.library.ui.compose.ProgressWheelState wheels=new app.atlante.library.ui.compose.ProgressWheelState(b.currentPage,b.totalPages,progress(b),b.currentPage,prefs.getInt("progressMode",0),(int)Math.min(1440,(timed?seconds:ReadingJournal.manualSeconds(b,java.time.LocalDate.now()))/60),timed);
        p.addView(app.atlante.library.ui.compose.ProgressWheelsView.create(this,palette,wheels,prefs),new LinearLayout.LayoutParams(-1,-2)); gap(p,16);

        // Chapter reached: a wheel with the book's chapter titles (shared with Calibre).
        NumberPicker chapter=null;
        if(!b.chapters.isEmpty()) {
            p.addView(sectionLabel("CAPITOLO A CUI SEI ARRIVATO"));
            String[] titles=new String[b.chapters.size()+1]; titles[0]="—";
            for(int i=0;i<b.chapters.size();i++){ String t=b.chapters.get(i).title; titles[i+1]=t.length()>38?t.substring(0,37)+"…":t; }
            chapter=wheel(0,titles.length-1,0); chapter.setDisplayedValues(titles);
            String now=b.journal.optString("chapter"); for(int i=0;i<b.chapters.size();i++) if(b.chapters.get(i).title.equals(now)) chapter.setValue(i+1);
            LinearLayout chapterRow=row(); chapterRow.setGravity(Gravity.CENTER); chapterRow.addView(chapter,new LinearLayout.LayoutParams(-1,-2)); p.addView(chapterRow,new LinearLayout.LayoutParams(-1,-2)); gap(p,16);
        }
        final NumberPicker chapterWheel=chapter;

        p.addView(button(timed?"Salva la sessione":"Salva",true,()->{
            try {
                if(timed&&timerBook==null)return;
                // The book as it is now: merged meanwhile (Ripara duplicati), the session goes to the record that took it in.
                Book live=liveBook(b.id); if(live==null) throw new java.io.IOException("Questo libro non è più nella libreria.");
                if(timed&&!live.id.equals(timerBook)){timerBook=live.id;persistTimer();}
                Book c=Book.from(live.json()); c.totalPages=wheels.getTotal(); int m=wheels.getMode();
                if(m==0){ c.totalPages=wheels.getTotal(); c.currentPage=wheels.getPage();
                    // Unknown total: the page is kept, the percentage stays as it was (no invented number of pages).
                    if(c.totalPages>0) c.percent=Math.round(100f*c.currentPage/c.totalPages); }
                else if(m==1){ c.percent=wheels.getPercent(); if(c.totalPages>0) c.currentPage=Math.round(c.percent*c.totalPages/100f); }
                else { c.currentPage=live.currentPage+wheels.getRead(); if(c.totalPages>0){ c.currentPage=Math.min(c.currentPage,c.totalPages); c.percent=Math.round(100f*c.currentPage/c.totalPages);} }
                boolean done=c.totalPages>0?c.currentPage==c.totalPages&&c.currentPage>0:c.percent==100;
                boolean moved=c.currentPage>0||c.percent>0;
                if(done) c.status=Book.READ; else if(moved&&!c.status.equals(Book.READ)) c.status=Book.READING;
                Book.applyStatusEffects(c,java.time.LocalDate.now().toString());
                long duration=timed&&wheels.getMinutes()==seconds/60?seconds:wheels.getMinutes()*60L;
                if(chapterWheel!=null) { if(chapterWheel.getValue()==0) c.journal.remove("chapter"); else ReadingJournal.put(c,"chapter",b.chapters.get(chapterWheel.getValue()-1).title); }
                if(timed) ReadingJournal.sessionOnce(c,timerFrom,c.currentPage,duration,java.time.LocalDate.now(),b.id+":"+timerWall);
                else {
                    // Without the timer the minutes are today's total for this book: only the difference is recorded,
                    // so correcting 30 to 20 takes 10 off instead of adding 20.
                    java.time.LocalDate today=java.time.LocalDate.now(); long before=ReadingJournal.manualSeconds(live,today);
                    long delta=wheels.getMinutes()==before/60?0:wheels.getMinutes()*60L-before;
                    if(delta<0) ReadingJournal.takeSeconds(c,today,-delta);
                    if(delta>0||c.currentPage!=live.currentPage) ReadingJournal.session(c,live.currentPage,c.currentPage,Math.max(0,delta),today);
                }
                store.replace(c); if(timed) clearTimer(); renderContent(); details(c);
            } catch(Exception e){ error(e); }
        }),new LinearLayout.LayoutParams(-1,-2));
        p.addView(button(timed?"Torna al timer":"Annulla",R.style.Atlante_Button_Text,()->{if(timed)timerScreen(b);else details(b);}),new LinearLayout.LayoutParams(-1,-2));
    }
    private long bootCount() { return android.provider.Settings.Global.getInt(getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1); }
    private void restoreTimer() {
        android.content.SharedPreferences p=getPreferences(MODE_PRIVATE);timerBook=p.getString("timerBook",null);timerAccumulated=p.getLong("timerAccumulated",0);timerFrom=p.getInt("timerFrom",0);timerRunning=p.getBoolean("timerRunning",false);timerMark=p.getLong("timerMark",0);timerBoot=p.getLong("timerBoot",-2);timerWall=p.getLong("timerWall",0);
        // Merged by Ripara duplicati while the timer ran: the session goes on with the record that took the book in.
        if(timerBook!=null&&store.find(timerBook)==null){Book to=liveBook(timerBook);if(to==null){clearTimer();return;}timerBook=to.id;persistTimer();}
        if(timerRunning&&(timerBoot!=bootCount()||timerMark>android.os.SystemClock.elapsedRealtime())) {timerRunning=false;persistTimer();}
    }
    private void persistTimer() {getPreferences(MODE_PRIVATE).edit().putString("timerBook",timerBook).putLong("timerAccumulated",timerAccumulated).putInt("timerFrom",timerFrom).putBoolean("timerRunning",timerRunning).putLong("timerMark",timerMark).putLong("timerBoot",timerBoot).putLong("timerWall",timerWall).apply();}
    private long timerSeconds() {return Math.max(0,timerAccumulated+(timerRunning?Math.max(0,android.os.SystemClock.elapsedRealtime()-timerMark):0))/1000;}
    private String elapsedLabel(long s) {return String.format(Locale.ITALIAN,"%02d:%02d:%02d",s/3600,s/60%60,s%60);}
    private void startTimer(Book b) {
        if(timerBook!=null&&!timerBook.equals(b.id)){Book active=store.find(timerBook);dialogBuilder().setTitle("Hai già una sessione").setMessage("Concludi la sessione in corso prima di iniziarne un’altra.").setPositiveButton("Apri sessione",(d,w)->{if(active!=null)timerScreen(active);}).setNegativeButton("Annulla",null).show();return;}
        if(timerBook==null){timerBook=b.id;timerAccumulated=0;timerFrom=b.currentPage;timerRunning=true;timerMark=android.os.SystemClock.elapsedRealtime();timerBoot=bootCount();timerWall=System.currentTimeMillis();persistTimer();}
        timerScreen(b);
    }
    /** Reading timer: large tabular digits, clear state, large Pause/Resume and End buttons; logic unchanged. */
    private void timerScreen(Book b) {
        LinearLayout p=editorPage("Un momento per leggere",b);p.setGravity(Gravity.CENTER_HORIZONTAL);gap(p,20);
        TextView title=text(b.title,26,INK,true); title.setGravity(Gravity.CENTER); p.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView author=text(b.author,16,MUTED,false); author.setGravity(Gravity.CENTER); p.addView(author,new LinearLayout.LayoutParams(-1,-2)); gap(p,28);
        TextView state=text(timerRunning?"● IN CORSO":"❚❚ IN PAUSA",13,timerRunning?SAGE:COPPER,false); state.setLetterSpacing(0.12f); state.setGravity(Gravity.CENTER);
        p.addView(state,new LinearLayout.LayoutParams(-1,-2)); gap(p,6);
        // Tabular figures: the digits do not jump while the seconds change. Smaller on narrow screens.
        float digits=getResources().getConfiguration().screenWidthDp<360?52:64;
        timerLabel=text(elapsedLabel(timerSeconds()),digits,INK,false); timerLabel.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
        timerLabel.setFontFeatureSettings("tnum"); timerLabel.setGravity(Gravity.CENTER); timerLabel.setMaxLines(1);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(timerLabel,28,64,2,android.util.TypedValue.COMPLEX_UNIT_SP);
        timerLabel.setContentDescription("Tempo trascorso"); p.addView(timerLabel,new LinearLayout.LayoutParams(-1,-2));
        TextView hint=text(timerRunning?"Il tempo continua anche se esci dall’app.":"Il tempo è fermo. Riprendi quando vuoi.",14,MUTED,false); hint.setGravity(Gravity.CENTER);
        p.addView(hint,new LinearLayout.LayoutParams(-1,-2)); gap(p,32);
        MaterialButton toggle=button(timerRunning?"Pausa":"Riprendi",0,()->{if(timerRunning){timerAccumulated+=Math.max(0,android.os.SystemClock.elapsedRealtime()-timerMark);timerRunning=false;}else{timerMark=android.os.SystemClock.elapsedRealtime();timerBoot=bootCount();timerRunning=true;}persistTimer();timerScreen(b);});
        toggle.setIconResource(timerRunning?R.drawable.ic_pause:R.drawable.ic_play); toggle.setTextSize(18); toggle.setMinHeight(dp(64)); toggle.setIconSize(dp(28));
        p.addView(toggle,new LinearLayout.LayoutParams(-1,-2)); gap(p,10);
        MaterialButton end=button("Termina",R.style.Atlante_Button_Tonal,()->{if(timerRunning){timerAccumulated+=Math.max(0,android.os.SystemClock.elapsedRealtime()-timerMark);timerRunning=false;persistTimer();}progressEditor(b,timerSeconds(),true);});
        end.setIconResource(R.drawable.ic_stop); end.setTextSize(18); end.setMinHeight(dp(64)); end.setIconSize(dp(26));
        p.addView(end,new LinearLayout.LayoutParams(-1,-2));
        p.addView(text("Termina ferma il tempo e ti chiede fin dove sei arrivato.",13,MUTED,false)); gap(p,16);
        p.addView(button("Scarta sessione",R.style.Atlante_Button_Text,()->dialogBuilder().setTitle("Scartare la sessione?").setMessage("Questo tempo non verrà registrato.").setNegativeButton("Annulla",null).setPositiveButton("Scarta",(d,w)->{clearTimer();details(b);}).show()));
        uiHandler.removeCallbacks(timerTick);uiHandler.post(timerTick);
    }
    private void clearTimer(){timerBook=null;timerRunning=false;timerAccumulated=0;timerLabel=null;uiHandler.removeCallbacks(timerTick);persistTimer();}
}
