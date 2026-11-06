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
    private String filterGenre = "", filterFormat = "", filterSaga = "", sortOrder = "Titolo";
    /** Library tabs shown as chips; each one but the first is a reading state. */
    private static final String[] STATUS_TABS = {"Tutti i libri", "Da leggere", "In lettura", "In pausa", "Letti", "Preferiti"};
    private String coverBookId;
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
        getOnBackPressedDispatcher().addCallback(this, back);
        if (state != null) {
            tab = state.getString("tab", tab); if (tab.equals("Desideri")) tab = "Tutti i libri"; query = state.getString("query", "");
            mapAuthor = state.getString("author", ""); exportId = state.getString("export"); coverBookId = state.getString("coverBook");
            backupFiles = state.getBoolean("backupFiles", true);
        }
        filterGenre = prefs.getString("filterGenre", "");
        filterFormat = prefs.getString("filterFormat", ""); filterSaga = prefs.getString("filterSaga", ""); sortOrder = prefs.getString("sortOrder", "Titolo");
        listView = prefs.getBoolean("listView", false);
        OnlineCatalog.configure(new File(getCacheDir(), "catalog"));
        // Temporary data: expired suggestions and covers over the limit go away; sagas refresh in the background.
        app.atlante.library.saga.CacheManager.cleanupAsync(this);
        app.atlante.library.saga.Hardcover.configure(this);
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
        prefs.edit().remove("keepPageOnce").apply();
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
        if (reading != null && pages != null) showReader(); else if (detailId != null && store.find(detailId) != null) details(store.find(detailId)); else showLibrary();
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
        for (String name : new String[]{"Tutti i libri", "Mappa", "Autori", "Saghe", "Universi"}) {
            boolean selected = name.equals("Tutti i libri") ? libraryTab : tab.equals(name);
            menu.addView(drawerItem(name, selected, () -> { closeDrawer(); destination = "Biblioteca"; tab = name; showLibrary(); }));
        }
        gap(menu, 8); rule(menu); gap(menu, 8);
        menu.addView(drawerItem("Collezioni", false, () -> { closeDrawer(); collections(); }));
        menu.addView(drawerItem("Cerca libri online", false, () -> { closeDrawer(); onlineSearch(); }));
        // Backups stay reachable (they are the only copy of notes and progress outside the phone), outside the settings.
        Button catalog = drawerItem("Backup", false, () -> { closeDrawer(); backupMenu(); }); catalog.setEnabled(!busy); menu.addView(catalog);
        menu.addView(drawerItem("Impostazioni", false, () -> { closeDrawer(); settings(); }));
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        screen.addView(overlay,new FrameLayout.LayoutParams(-1,-1)); close.requestFocus();
        scroll.setTranslationX(-width); scroll.animate().translationX(0).setDuration(220).start();
        updateBack();
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
    private void settings() { if(!destination.equals("Impostazioni"))getPreferences(MODE_PRIVATE).edit().putString("settingsReturn",destination).apply(); navigate("Impostazioni"); }

    private void settingsPage(LinearLayout panel) {
        // Aspetto e personalizzazione: the themes stay in a section that opens and closes.
        panel.addView(sectionLabel("ASPETTO E PERSONALIZZAZIONE")); gap(panel,8);
        LinearLayout themes = column(); themeTiles(themes);
        panel.addView(expander("Temi predefiniti · " + palette.name, themes, false)); gap(panel,20);

        customThemes(panel); gap(panel,20);
        panel.addView(sectionLabel("OBIETTIVI DI LETTURA")); gap(panel,8);
        panel.addView(app.atlante.library.ui.compose.GoalsView.create(this, palette, getPreferences(MODE_PRIVATE), store.snapshot())); gap(panel,20);

        panel.addView(sectionLabel("HARDCOVER · SAGHE, NUMERI E UNIVERSI")); gap(panel,8);
        hardcoverSection(panel); gap(panel,20);

        // Libreria: the only import entry, covers, sagas and temporary data.
        panel.addView(sectionLabel("LIBRERIA")); gap(panel,8);
        MaterialButton importBooks = (MaterialButton) button("Importa eBook", true, this::chooseBooks); importBooks.setIconResource(R.drawable.ic_add); importBooks.setEnabled(!busy);
        panel.addView(importBooks, new LinearLayout.LayoutParams(-1,-2));
        panel.addView(text("EPUB, TXT, CBZ e AZW3 senza DRM, anche più file insieme. Prima di aggiungerli vedi titolo, autore e copertina e puoi correggerli; se il libro è già nella libreria puoi collegare il file alla sua scheda.",12,MUTED,false)); gap(panel,12);
        panel.addView(button("Cerca e migliora le copertine",false,() -> { improveCovers(store.snapshot(), true, true); }));
        panel.addView(text("Scarica le copertine mancanti e sostituisce quelle piccole o sfocate con una più nitida della stessa edizione o dello stesso titolo e autore. Le copertine scelte da te non vengono toccate.",12,MUTED,false)); gap(panel,12);
        LinearLayout sagas = column();
        sagas.addView(app.atlante.library.ui.compose.SagaRepairView.create(this, palette, sagaHost)); gap(sagas,8);
        sagas.addView(text("Proposte rapide dai titoli e dai metadati già presenti, senza connessione:",14,MUTED,false));
        sagas.addView(button("Riconosci saghe e ordine di lettura",false,this::recognizeSeries));
        panel.addView(expander("Ripara saghe", sagas, false));
        panel.addView(expander("Cache e dati temporanei", app.atlante.library.ui.compose.CacheSettingsView.create(this, palette), false)); gap(panel,20);

        panel.addView(sectionLabel("LETTURA")); gap(panel,10);
        MaterialSwitch dark = new MaterialSwitch(this); dark.setText("Lettore scuro"); dark.setTextSize(16); dark.setChecked(readerDark); dark.setMinHeight(dp(48));
        panel.addView(dark);
        dark.setOnCheckedChangeListener((v, checked) -> { readerDark=checked; getPreferences(MODE_PRIVATE).edit().putBoolean("dark",checked).apply(); });
        TextView label = text("Dimensione del testo · " + Math.round(fontSize),15,INK,false); panel.addView(label);
        TextView sample=text("Il piacere di leggere, una pagina alla volta.",fontSize,INK,true); panel.addView(sample);
        Slider font = new Slider(this); font.setValueFrom(8); font.setValueTo(80); font.setStepSize(1); font.setValue(Math.round(fontSize));
        font.setContentDescription("Dimensione del testo del lettore"); panel.addView(font);
        font.addOnChangeListener((slider, value, fromUser) -> { if (fromUser) { fontSize = value; sample.setTextSize(value); label.setText("Dimensione del testo · " + Math.round(fontSize)); getPreferences(MODE_PRIVATE).edit().putFloat("fontSize", fontSize).apply(); } });
        gap(panel,20); panel.addView(sectionLabel("LINGUA DEI LIBRI")); gap(panel,8);
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        ArrayList<String> languages=new ArrayList<>();
        for(String[] l:OnlineCatalog.LANGUAGES) languages.add(l[2]);
        Picker language=new Picker(panel,"Lingua preferita per le ricerche",languages,OnlineCatalog.languageName(prefs.getString("bookLanguage","ita")));
        MaterialSwitch only=new MaterialSwitch(this); only.setText("Cerca solo libri nella lingua scelta"); only.setChecked(prefs.getBoolean("onlyLanguage",false)); panel.addView(only);
        panel.addView(button("Salva lingua",false,()->{ prefs.edit().putString("bookLanguage",OnlineCatalog.LANGUAGES[language.position()][0]).putBoolean("onlyLanguage",only.isChecked()).apply(); Toast.makeText(this,"Lingua di ricerca salvata",Toast.LENGTH_SHORT).show(); }));
        panel.addView(text("Lingua dell’interfaccia: italiano.",12,MUTED,false));
        gap(panel,20); panel.addView(sectionLabel("ATLANTE LIBRARY")); gap(panel,8);
        panel.addView(text("Le ricerche online (SBN, Open Library, Wikidata, Apple Books e Hardcover se hai inserito la chiave) inviano solo titolo, autore o ISBN. Libri e note personali restano sul dispositivo.",12,MUTED,false));
        panel.addView(button("Progetto e aggiornamenti",false,() -> startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/Mancio24x/atlante-android")))));
    }

    /**
     * Each person's own Hardcover key (free, made in their Hardcover account): it stays on this phone and is used
     * both by Sync and by the saga engine, which with it recognizes saga, volume number, sub-series and universe.
     */
    private void hardcoverSection(LinearLayout p) {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        boolean on=app.atlante.library.sync.HardcoverAuth.connected(this);
        boolean oauth=app.atlante.library.sync.HardcoverAuth.usesOauth(this);
        String user=prefs.getString("hardcoverUser","");
        p.addView(text(on?"Chiave inserita"+(user.isEmpty()?"":" · collegato come @"+user):"Nessuna chiave inserita",15,on?INK:MUTED,false));
        p.addView(text("Con la tua chiave personale di Hardcover Atlante riconosce saga, numero del volume, sotto-serie e universo (per esempio il Cosmere) di tutti i libri, anche con il titolo italiano, più in fretta e con più precisione. Senza chiave usa Wikidata e Open Library. Ognuno usa la propria chiave: è gratuita e resta solo su questo telefono.",12,MUTED,false)); gap(p,8);
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
            runOnUiThread(()->{ app.atlante.library.saga.Hardcover.configure(this); Toast.makeText(this,"Hardcover scollegato",Toast.LENGTH_SHORT).show(); showLibrary(); });
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
                    Toast.makeText(this,"Collegato a Hardcover come @"+user+". Riconosco le saghe della libreria in background.",Toast.LENGTH_LONG).show();
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
                    Toast.makeText(this,"Chiave valida: collegato come @"+user+". Riconosco le saghe della libreria in background.",Toast.LENGTH_LONG).show();
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

    private void customThemes(LinearLayout p) {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        Picker mode=new Picker(p,"Modalità",Arrays.asList("Tema scelto","Chiara","Scura","Automatica"),
            Arrays.asList("Tema scelto","Chiara","Scura","Automatica").get(Arrays.asList("theme","light","dark","auto").indexOf(prefs.getString("appearanceMode","theme"))));
        p.addView(button("Applica modalità",false,()->{prefs.edit().putString("appearanceMode",new String[]{"theme","light","dark","auto"}[mode.position()]).commit();recreateHere();}));
        // Three colours chosen by touch (square + hue bar), stored as before ("#RRGGBB" strings in the preferences).
        ArrayList<String> savedNames=new ArrayList<>();
        try { JSONObject saved=new JSONObject(prefs.getString("savedThemes","{}")); Iterator<String> keys=saved.keys(); while(keys.hasNext()) savedNames.add(keys.next()); } catch(Exception ignored) { }
        View custom=app.atlante.library.ui.compose.ThemeEditorView.create(this,palette,PAPER,WHITE,SAGE,savedNames,(name,paper,surface,accent)->{
            try {
                JSONObject colors=new JSONObject().put("paper",String.format(Locale.ROOT,"#%06X",paper&0xffffff)).put("surface",String.format(Locale.ROOT,"#%06X",surface&0xffffff)).put("accent",String.format(Locale.ROOT,"#%06X",accent&0xffffff));
                JSONObject saved=new JSONObject(prefs.getString("savedThemes","{}")); saved.put(name,colors);
                prefs.edit().putString("savedThemes",saved.toString()).putString("customPalette",colors.toString()).putString("theme","custom").commit();recreateHere();
            } catch(Exception e){error(e);}
        },name->{
            try { String colors=new JSONObject(prefs.getString("savedThemes","{}")).getJSONObject(name).toString(); prefs.edit().putString("customPalette",colors).putString("theme","custom").commit(); recreateHere(); }
            catch(Exception e){error(e);}
        });
        p.addView(expander("I miei temi · tre colori",custom,false));
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
        palette = p; PAPER = p.paper; WHITE = p.surface; INK = p.ink; MUTED = p.muted;
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
            tile.setContentDescription(option.name + ", " + option.description); tile.setMinimumHeight(dp(64));
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
        else {b.setTextColor(SAGE);if(overlay==R.style.Atlante_Button_Tonal)b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));}
        b.setText(label); b.setMinHeight(dp(48)); b.setOnClickListener(v -> action.run());
        return b;
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
    void error(Exception e) { dialogBuilder().setTitle("Operazione non completata").setMessage(message(e)).setPositiveButton("Chiudi", null).show(); }
    static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    void save() { try { store.save(); } catch (Exception e) { error(e); } }

    private void showLibrary() {
        detailId = null; editingPage = false; timerLabel = null; uiHandler.removeCallbacks(timerTick);
        if (!destination.equals("Biblioteca") && !destination.equals("Cerca")) { showCompanion(); return; }
        reading = null; pages = null;
        base(PAPER);
        boolean map = destination.equals("Biblioteca") && tab.equals("Mappa");
        LinearLayout header = column(); header.setPadding(dp(8), dp(4), dp(8), dp(8));
        root.addView(header);
        LinearLayout masthead = row(); header.addView(masthead);
        masthead.addView(iconButton(R.drawable.ic_menu, "Apri menu laterale", this::openDrawer));
        TextView brand = text(destination.equals("Cerca") ? "Cerca" : map ? "Mappa delle letture" : tab.equals("Autori") ? "Autori" : tab.equals("Saghe") ? "Saghe" : tab.equals("Universi") ? "Universi" : "Atlante", 22, INK, true);
        brand.setPadding(dp(8), 0, 0, 0); brand.setMaxLines(1); brand.setEllipsize(TextUtils.TruncateAt.END);
        masthead.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        if (!map) masthead.addView(iconButton(listView ? R.drawable.ic_grid : R.drawable.ic_list, listView ? "Griglia" : "Elenco", () -> {
            String[] modes = {"Scaffale", "Griglia", "Elenco"};
            dialogBuilder().setTitle("Vista biblioteca").setSingleChoiceItems(modes, shelfView ? 0 : listView ? 2 : 1, (dialog, which) -> {
                shelfView = which == 0; listView = which == 2;
                getPreferences(MODE_PRIVATE).edit().putBoolean("listView", listView).putBoolean("shelfView", shelfView).apply();
                dialog.dismiss(); showLibrary();
            }).setNegativeButton("Chiudi", null).show();
        }));
        masthead.addView(iconButton(R.drawable.ic_settings, "Impostazioni", this::settings));
        LinearLayout controls = column(); controls.setPadding(dp(8), dp(4), dp(8), 0); header.addView(controls);
        if (map) mapHeader(controls); else libraryHeader(controls);
        if(destination.equals("Cerca")) controls.addView(button("Cerca anche online",R.style.Atlante_Button_Text,()->{lastOnlineQuery=query;onlineSearch();}));
        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        body = new FrameLayout(this); grid = null; adapter = null; content = null;
        stage.addView(body, new FrameLayout.LayoutParams(-1, -1));
        if (!map) {
            ExtendedFloatingActionButton add = new ExtendedFloatingActionButton(this);
            add.setText("Aggiungi libro"); add.setIconResource(R.drawable.ic_add); add.setOnClickListener(v -> manualBook());
            FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            corner.setMargins(dp(16), dp(16), dp(16), dp(16)); stage.addView(add, corner);
        }
        notice = text("", 12, MUTED, false);
        renderContent(); addBottomNav(); updateBack();
    }

    /** Search bar, reading-state chips and the filter chip above the library. */
    private void libraryHeader(LinearLayout header) {
        TextInputLayout box = new TextInputLayout(new ContextThemeWrapper(this, R.style.Atlante_Field));
        box.setHintEnabled(false); box.setStartIconDrawable(R.drawable.ic_search);
        box.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        float round = dp(28); box.setBoxCornerRadii(round, round, round, round);
        TextInputEditText search = new TextInputEditText(box.getContext()); search.setSingleLine(true);
        search.setHint("Cerca titolo, autore, saga, ISBN"); search.setContentDescription("Cerca nella biblioteca");
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH); search.setText(query);
        box.addView(search, new LinearLayout.LayoutParams(-1, -2)); header.addView(box);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { query = s.toString(); updateBack();
                if (filterTask != null) uiHandler.removeCallbacks(filterTask);
                filterTask = () -> { if (reading == null) renderContent(); }; uiHandler.postDelayed(filterTask, 180); }
            public void afterTextChanged(Editable e) { }
        });
        search.setOnEditorActionListener((v, action, event) -> { hideKeyboard(v); return true; });
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
        filters.setText(hasFilters() ? "Filtri attivi" : "Filtri e ordine"); filters.setOnClickListener(v -> libraryFilters());
        chips.addView(filters);
    }

    private ArrayList<Book> filtered() {
        return LibraryQuery.select(store.snapshot(), destination.equals("Biblioteca") && tab.equals("Mappa") ? "" : query, destination.equals("Cerca") ? "Tutti i libri" : tab, filterGenre, filterFormat, sortOrder, filterSaga);
    }

    private boolean hasFilters() { return !filterGenre.isEmpty() || !filterFormat.isEmpty() || !filterSaga.isEmpty(); }

    private void libraryFilters() {
        LinearLayout panel = column(); panel.setPadding(dp(24), dp(12), dp(24), dp(4));
        // Normalized genres: "Epic Fantasy" and "Fantasy epico" are both Fantasy.
        LinkedHashSet<String> genres = new LinkedHashSet<>(); boolean other = false;
        TreeSet<String> sagas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Book book : store.snapshot()) {
            String main = app.atlante.library.saga.Genres.normalize(book.genre);
            if (main != null) genres.add(main); else if (!book.genre.isEmpty()) other = true;
            if (!book.series.isEmpty()) sagas.add(book.series);
        }
        ArrayList<String> genreOptions = new ArrayList<>(); genreOptions.add("Tutti i generi");
        for (String g : app.atlante.library.saga.Genres.INSTANCE.getMain()) if (genres.contains(g)) genreOptions.add(g);
        if (other) genreOptions.add(LibraryQuery.OTHER_GENRE);
        if (!filterGenre.isEmpty() && !genreOptions.contains(filterGenre)) genreOptions.add(filterGenre);
        Picker genre = new Picker(panel, "Genere", genreOptions, filterGenre);
        ArrayList<String> sagaOptions = new ArrayList<>(Arrays.asList("Tutte le saghe", LibraryQuery.IN_SAGA, LibraryQuery.SINGLE)); sagaOptions.addAll(sagas);
        Picker saga = new Picker(panel, "Saga", sagaOptions, filterSaga);
        Picker format = new Picker(panel, "Formato", Arrays.asList("Tutti i formati", "EPUB", "TXT", "CBZ", "AZW3", "CATALOGO"), filterFormat);
        Picker order = new Picker(panel, "Ordina per", Arrays.asList("Titolo", "Autore", "Saga"), sortOrder);
        dialogBuilder().setTitle("Filtri e ordine").setView(panel)
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Azzera", (d, w) -> { filterGenre = ""; filterFormat = ""; filterSaga = ""; applyLibraryFilters(); })
            .setPositiveButton("Applica", (d, w) -> {
                filterGenre = genre.position() == 0 ? "" : genre.value();
                filterFormat = format.position() == 0 ? "" : format.value();
                filterSaga = saga.position() == 0 ? "" : saga.value();
                sortOrder = order.value(); applyLibraryFilters();
            }).show();
    }

    private void applyLibraryFilters() {
        getPreferences(MODE_PRIVATE).edit().putString("filterGenre", filterGenre)
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
            if (hasFilters()) content.addView(button("Azzera filtri", false, () -> { filterGenre = ""; filterFormat = ""; filterSaga = ""; applyLibraryFilters(); }));
            if (store.snapshot().isEmpty()) content.addView(button("Cerca il tuo primo libro", true, this::onlineSearch));
            return;
        }
        if (destination.equals("Biblioteca") && tab.equals("Mappa")) { showMap(list); return; }
        if (destination.equals("Biblioteca") && (tab.equals("Autori") || tab.equals("Saghe"))) { showIndex(list, tab.equals("Autori")); return; }
        if (destination.equals("Biblioteca") && tab.equals("Universi")) { content.addView(app.atlante.library.ui.compose.UniverseIndexView.create(this, palette, query, sagaHost)); return; }
    }

    private int columns() {
        int width = getResources().getConfiguration().screenWidthDp;
        return listView ? 1 : width >= 1100 ? 6 : width >= 760 ? 4 : width >= 550 ? 3 : 2;
    }
    private int coverHeight() {
        int width = getResources().getConfiguration().screenWidthDp, columns = columns();
        return listView ? 150 : Math.min(310, Math.max(170, (width - 40 - columns * 12) / columns * 14 / 10));
    }

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
            grid.setPadding(dp(9), dp(16), dp(9), dp(28));
            adapter = new BookAdapter(new BookAdapter.Cards() {
                @Override public View header() { return gridHeader(); }
                @Override public View card(Book book) { return bookCard(book); }
            });
            GridLayoutManager manager = new GridLayoutManager(this, columns());
            manager.setSpanSizeLookup(adapter.spans(manager));
            grid.setLayoutManager(manager); grid.setAdapter(adapter);
            body.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        }
        shownCount = count;
        // A new tab, search or filter starts from the top; edits to a book keep the scroll position.
        String key = tab + "\n" + query + "\n" + filterGenre + "\n" + filterFormat + "\n" + filterSaga + "\n" + sortOrder;
        boolean changed = !key.equals(gridKey); gridKey = key;
        adapter.submit(list);
        if (changed) grid.scrollToPosition(0);
        android.os.Parcelable restored = libraryPositions.remove(destination);
        if (restored != null) grid.getLayoutManager().onRestoreInstanceState(restored);
    }
    private int shownCount;

    private LinearProgressIndicator progressBar(Book b) {
        LinearProgressIndicator bar = new LinearProgressIndicator(this);
        if (b.totalPages > 0) { bar.setMax(b.totalPages); bar.setProgressCompat(Math.min(b.currentPage, b.totalPages), false); }
        else { bar.setMax(100); bar.setProgressCompat(b.percent, false); }
        bar.setTrackCornerRadius(dp(4)); bar.setTrackThickness(dp(6));
        return bar;
    }

    private View gridHeader() {
        LinearLayout header = column(); header.setPadding(dp(5), 0, dp(5), dp(16));
        int total = store.snapshot().size();
        header.addView(text(busy ? "Importazione in corso…" : (shownCount == total ? total + (total == 1 ? " libro" : " libri")
            : shownCount + " di " + total + " libri") + " · ordinati per " + sortOrder.toLowerCase(Locale.ROOT), 13, MUTED, false));
        return header;
    }

    private View bookCard(Book b) {
        int coverHeight = coverHeight();
        FrameLayout cell = new FrameLayout(this);
        LinearLayout card = column(); card.setPadding(dp(10), dp(10), dp(10), dp(14));
        MaterialCardView frame = card(card);
        FrameLayout.LayoutParams cardSize = new FrameLayout.LayoutParams(-1, -2);
        cardSize.setMargins(dp(5), 0, dp(5), dp(12)); cell.addView(frame, cardSize);
        if (shelfView) { View shelf = new View(this); shelf.setBackground(background(COPPER, GOLD));
            FrameLayout.LayoutParams plank = new FrameLayout.LayoutParams(-1, dp(5), Gravity.BOTTOM); plank.setMargins(dp(3),0,dp(3),dp(5)); cell.addView(shelf,plank); }
        FrameLayout art = new FrameLayout(this); art.setBackground(background(PAPER, Color.TRANSPARENT)); art.setClipToOutline(true);
        art.setPadding(dp(7), dp(7), dp(7), dp(7));
        card.addView(art, new LinearLayout.LayoutParams(-1, dp(coverHeight)));
        TextView fallback = text(b.author + "\n\n" + b.title, 18, SAGE, true);
        fallback.setBackground(background(PAPER, Color.TRANSPARENT)); fallback.setPadding(dp(12), dp(18), dp(12), dp(18));
        art.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this); image.setContentDescription("Copertina di " + b.title);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER); art.addView(image, new FrameLayout.LayoutParams(-1, -1));
        covers.thumbnail(b.id, store.cover(b), dp(coverHeight), image, fallback);
        TextView format = text(b.format.equals("CATALOGO") ? (b.medium.isEmpty() ? "SCHEDA" : b.medium.toUpperCase(Locale.ROOT)) : b.format, 10, INK, false); format.setTypeface(Typeface.DEFAULT_BOLD);
        format.setPadding(dp(5), dp(3), dp(5), dp(3)); format.setBackground(background(WHITE, Color.TRANSPARENT));
        art.addView(format, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        gap(card, 10); TextView bookTitle = text(b.title, 18, INK, true); bookTitle.setMaxLines(2); bookTitle.setEllipsize(TextUtils.TruncateAt.END); card.addView(bookTitle); gap(card, 4);
        TextView author = text(b.author, 12, MUTED, false); author.setMaxLines(2); card.addView(author);
        if (!b.series.isEmpty()) { gap(card, 5); card.addView(text("↳ " + b.series + order(b), 12, SAGE, false)); }
        if (!b.status.equals("Da leggere")) { gap(card, 5); card.addView(text(b.status, 11, COPPER, false)); }
        gap(card, 6); card.addView(text(progressLabel(b), 11, SAGE, false));
        if (b.totalPages > 0 || b.percent > 0) { gap(card, 4); card.addView(progressBar(b), new LinearLayout.LayoutParams(-1, -2)); }
        frame.setOnClickListener(v -> details(b)); art.setOnClickListener(v -> details(b));
        return cell;
    }

    static String order(Book b) { return b.number > 0 ? " · n. " + number(b.number) : ""; }
    public static String number(double number) { return number == (long)number ? Long.toString((long)number) : Double.toString(number); }
    private void forgetCover(String id) { covers.forget(id); app.atlante.library.ui.compose.CoverImages.forgetBook(id); }
    private Bitmap cover(Book b) { return covers.large(b.id, store.cover(b)); }
    static Bitmap decode(String file, byte[] bytes, int max) { return Covers.decode(file, bytes, max); }

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

    private void mapHeader(LinearLayout header) {
        ArrayList<String> authors = mapAuthors();
        if (!authors.isEmpty() && !authors.contains(mapAuthor)) {
            String key = app.atlante.library.ui.MapGroups.authorKey(mapAuthor); mapAuthor = authors.get(0);
            for (String a : authors) if (app.atlante.library.ui.MapGroups.authorKey(a).equals(key)) { mapAuthor = a; break; }
        }
        TextInputLayout box = new TextInputLayout(new ContextThemeWrapper(this, R.style.Atlante_Field));
        box.setHint(authors.size() + (authors.size() == 1 ? " autore" : " autori") + (hasFilters() ? " · filtri attivi" : ""));
        box.setStartIconDrawable(R.drawable.ic_search);
        float round = dp(28); box.setBoxCornerRadii(round, round, round, round);
        MaterialAutoCompleteTextView field = new MaterialAutoCompleteTextView(box.getContext());
        field.setSingleLine(true); field.setThreshold(1); field.setContentDescription("Cerca l’autore della mappa");
        field.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, authors));
        field.setText(mapAuthor, false); field.setSelectAllOnFocus(true);
        // Tapping the field lists every author; typing narrows the list.
        field.setOnClickListener(v -> { if (!authors.isEmpty()) field.showDropDown(); });
        // The first tap only focuses the field: open the list then too.
        field.setOnFocusChangeListener((v, focused) -> { if (focused && !authors.isEmpty()) field.post(field::showDropDown); });
        field.setOnItemClickListener((parent, view, position, id) -> {
            mapAuthor = parent.getItemAtPosition(position).toString();
            field.clearFocus(); hideKeyboard(field); renderContent();
        });
        box.addView(field, new LinearLayout.LayoutParams(-1, -2));
        header.addView(box, new LinearLayout.LayoutParams(-1, -2));
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
        content.addView(button("Riconosci saghe e ordine",false,this::recognizeSeries));
        content.addView(text("I libri senza numero sono in fondo alla saga: assegna l’ordine dalla scheda.",12,MUTED,false));
        content.addView(new ReadingMap(this, mapAuthor, selected, app.atlante.library.saga.SagaIndex.known(), this::details), new LinearLayout.LayoutParams(-1, -2));
        TextView hint = text("Autore → saga → libri nell’ordine di lettura. Tocca un libro per aprire la scheda."
            + (selected.size() == 250 ? " Sono mostrate le prime 250 opere." : ""), 12, MUTED, false);
        hint.setPadding(dp(20), dp(4), dp(20), 0); content.addView(hint);
    }

    /** Saga section of the book page: opens owned books, searches the others, writes a saga the user picked. */
    private final app.atlante.library.ui.compose.SagaHost sagaHost = new app.atlante.library.ui.compose.SagaHost() {
        @Override public void openBook(String id) { Book x = store.find(id); if (x != null) details(x); }
        @Override public void searchOnline(String q) { searchFor(q); }
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
    };

    /** Book window: a Material bottom sheet with reading state, plot, chapters and the book's details. */
    private void details(Book b) {
        if (detailId == null) {
            if (grid != null && grid.getLayoutManager() != null) libraryPositions.put(destination,grid.getLayoutManager().onSaveInstanceState());
            if (companionScroll != null) pagePositions.put(destination,companionScroll.getScrollY());
        }
        editingPage = false;
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
        panel.addView(chaptersSection(b));
        panel.addView(app.atlante.library.ui.compose.BookSagaView.create(this, palette, b, sagaHost));

        LinearLayout info = column();
        if (!b.tags.isEmpty()) info.addView(text("Tag: " + b.tags, 14, INK, false));
        if (!b.location.isEmpty()) info.addView(text("Posizione: " + b.location, 14, INK, false));
        info.addView(text((b.format.equals("CATALOGO") ? "Scheda senza file" : "File " + b.format) + (b.medium.isEmpty() ? "" : " · " + b.medium)
            + (b.totalPages > 0 ? " · " + b.totalPages + " pagine" : ""), 14, INK, false));
        gap(info, 4); bibliographic(info, b);
        if (!b.source.isEmpty()) info.addView(button("Apri su Open Library", R.style.Atlante_Button_Text, () -> {
            if (b.source.matches("/works/OL[0-9]+W")) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://openlibrary.org" + b.source)));
        }));
        panel.addView(expander("Dettagli della scheda", info, false));
        gap(panel, 12);

        panel.addView(button("Completa i dati online", R.style.Atlante_Button_Tonal, () -> completeMetadata(b)), new LinearLayout.LayoutParams(-1, -2));
        panel.addView(button("Modifica scheda", R.style.Atlante_Button_Tonal, () -> { sheet.dismiss(); edit(b); }), new LinearLayout.LayoutParams(-1, -2));
        panel.addView(button("Scegli copertina online", R.style.Atlante_Button_Tonal, () -> chooseOnlineCover(b)), new LinearLayout.LayoutParams(-1, -2));
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
        header.setPadding(0, 0, 0, 0); header.setIconGravity(MaterialButton.ICON_GRAVITY_END);
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

    /** Chapters with the reader's stars and comments. EPUB chapters come from the table of contents. */
    private View chaptersSection(Book b) {
        LinearLayout list = column();
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.removeAllViews();
            if (b.chapters.isEmpty()) {
                boolean loading = b.format.equals("EPUB") && !tocChecked.contains(b.id);
                list.addView(text(loading ? "Lettura dell’indice…" : "Nessun capitolo. Puoi aggiungerli per votarli e commentarli.", 14, MUTED, false));
            }
            int rated = 0, sum = 0;
            for (int i = 0; i < b.chapters.size(); i++) {
                Book.Chapter c = b.chapters.get(i); int index = i;
                if (c.rating > 0) { rated++; sum += c.rating; }
                LinearLayout item = column(); item.setPadding(dp(16 * c.level), dp(10), 0, dp(10)); item.setClickable(true); item.setFocusable(true);
                item.setBackgroundResource(outValue(android.R.attr.selectableItemBackground));
                LinearLayout line = row(); item.addView(line);
                TextView title = text(c.title, 15, INK, false); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
                line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                if (c.rating > 0) line.addView(text(stars(c.rating), 13, COPPER, false));
                if (!c.comment.isEmpty()) { TextView note = text(c.comment, 13, MUTED, false); note.setMaxLines(2); note.setEllipsize(TextUtils.TruncateAt.END); item.addView(note); }
                item.setContentDescription(c.title + (c.rating > 0 ? ", " + c.rating + " stelle" : ", senza voto") + (c.comment.isEmpty() ? "" : ", con commento"));
                item.setOnClickListener(v -> chapterDialog(b, index, refresh[0]));
                list.addView(item);
            }
            MaterialButton add = button(b.chapters.isEmpty() ? "Aggiungi capitoli" : "Aggiungi altri capitoli", R.style.Atlante_Button_Text, () -> addChapters(b, refresh[0]));
            add.setIconResource(R.drawable.ic_add); list.addView(add);
            if (rated > 0) list.addView(text(String.format(Locale.ITALIAN, "%d capitoli votati · media %.1f ★", rated, (double) sum / rated), 13, COPPER, false), 0);
        };
        refresh[0].run();
        if (b.format.equals("EPUB") && b.chapters.isEmpty() && tocChecked.add(b.id)) worker.execute(() -> {
            List<Book.Chapter> titles;
            try { titles = EbookParser.chapters(store.original(b)); } catch (Exception e) { titles = new ArrayList<>(); }
            final List<Book.Chapter> found = titles;
            runOnUiThread(() -> {
                if (!found.isEmpty() && b.chapters.isEmpty()) { b.chapters.addAll(found); save(); }
                refresh[0].run();
            });
        });
        String label = "Capitoli" + (b.chapters.isEmpty() ? "" : " · " + b.chapters.size());
        return expander(label, list, false);
    }

    private void chapterDialog(Book b, int index, Runnable refresh) {
        Book.Chapter c = b.chapters.get(index);
        LinearLayout panel = column(); panel.setPadding(dp(24), dp(8), dp(24), dp(4));
        EditText title = field(panel, "Titolo del capitolo", c.title);
        panel.addView(sectionLabel("IL TUO VOTO")); gap(panel, 4);
        RatingBar rating = new RatingBar(this); rating.setNumStars(5); rating.setStepSize(1); rating.setRating(c.rating);
        rating.setProgressTintList(android.content.res.ColorStateList.valueOf(COPPER));
        rating.setSecondaryProgressTintList(android.content.res.ColorStateList.valueOf(COPPER));
        rating.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));
        rating.setContentDescription("Voto del capitolo da 1 a 5 stelle");
        LinearLayout stars = row(); stars.addView(rating, new LinearLayout.LayoutParams(-2, -2));
        stars.addView(button("Nessun voto", R.style.Atlante_Button_Text, () -> rating.setRating(0)));
        panel.addView(stars); gap(panel, 8);
        EditText comment = field(panel, "Commento", c.comment); comment.setSingleLine(false); comment.setMinLines(3);
        AlertDialog dialog = dialogBuilder().setTitle("Capitolo " + (index + 1)).setView(scrolled(panel))
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Elimina", (d, w) -> dialogBuilder().setTitle("Eliminare il capitolo?").setMessage(c.title)
                .setNegativeButton("Annulla", null).setPositiveButton("Elimina", (d2, w2) -> { b.chapters.remove(c); save(); refresh.run(); }).show())
            .setPositiveButton("Salva", (d, w) -> {
                String name = title.getText().toString().trim(); if (!name.isEmpty()) c.title = name;
                c.rating = Math.round(rating.getRating()); c.comment = comment.getText().toString().trim();
                save(); refresh.run();
            }).create();
        dialog.show();
    }

    /** Adds numbered chapters ("Capitolo N") chosen with a wheel, after the existing ones. */
    private void addChapters(Book b, Runnable refresh) {
        LinearLayout panel = column(); panel.setPadding(dp(24), dp(8), dp(24), dp(4)); panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.addView(text("Quanti capitoli vuoi aggiungere? Potrai rinominarli toccandoli.", 14, MUTED, false));
        NumberPicker count = wheel(1, 200, 10); panel.addView(count);
        dialogBuilder().setTitle("Aggiungi capitoli").setView(panel).setNegativeButton("Annulla", null)
            .setPositiveButton("Aggiungi", (d, w) -> {
                int start = b.chapters.size();
                for (int i = 1; i <= count.getValue() && b.chapters.size() < EbookParser.MAX_CHAPTERS; i++) b.chapters.add(new Book.Chapter("Capitolo " + (start + i)));
                save(); refresh.run();
            }).show();
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
        b.status = status; Book.applyStatusEffects(b, java.time.LocalDate.now().toString()); save();
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
        try { return java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)); }
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
            b.status = Book.STATES[states.indexOfChild(states.findViewById(states.getCheckedButtonId()))];
            b.started = start; b.finished = end;
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
        if (image != null) {
            previewCover.setImageBitmap(image); panel.addView(previewCover, new LinearLayout.LayoutParams(-1, dp(220)));
        } else if (b.coverId > 0 || !b.isbn.isEmpty()) {
            previewCover.setBackground(background(PAPER,LINE)); panel.addView(previewCover,new LinearLayout.LayoutParams(-1,dp(220)));
        }
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
        Book duplicate = file ? app.atlante.library.model.BookMatcher.best(b, others, x -> x) : null;
        boolean canAttach = duplicate != null && (duplicate.format.equals("CATALOGO") || !store.original(duplicate).exists());
        if (duplicate != null) {
            LinearLayout warn = surface(panel, "GIÀ NELLA TUA LIBRERIA");
            warn.addView(text("«" + duplicate.title + "» di " + duplicate.author + (canAttach ? " · scheda senza file" : " · formato " + duplicate.format), 15, INK, false));
            warn.addView(text(canAttach ? "Puoi collegare questo file alla scheda esistente: voti, note e avanzamento restano." : "Il libro ha già un file. Per non creare un doppione, l’importazione viene annullata.", 13, MUTED, false));
            gap(panel, 12);
        }
        bibliographic(panel, b); gap(panel, 14); panel.addView(text("Trama", 20, INK, true));
        panel.addView(text(available(b.description), 16, INK, false)); gap(panel, 12);
        panel.addView(text(b.source.isEmpty() ? "Dati estratti dal file scelto. Puoi correggerli dalla scheda dopo l’aggiunta." : (b.source.startsWith("sbn:") ? "Fonte: catalogo SBN delle biblioteche italiane · " + b.source.substring(4) + "\nCopertina e trama da Open Library, se disponibili."
            : "Fonte: Open Library · " + b.source + "\nEdizione di riferimento: " + available(b.edition)) + " · I dati possono variare tra edizioni. La scheda non include il file del libro.", 13, MUTED, false));
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        final EditText editedTitle = titleField, editedAuthor = authorField;
        AlertDialog.Builder builder = dialogBuilder().setTitle(file ? "Importa eBook" : "Anteprima del libro").setView(scroll).setNegativeButton(duplicate != null ? "Annulla" : "Non aggiungere", null);
        if (duplicate == null) builder.setPositiveButton("Aggiungi alla mia libreria", null);
        else if (canAttach) builder.setPositiveButton("Collega il file", null);
        if(file)builder.setNeutralButton("Cerca metadati online",null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            if(file)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                b.title=editedTitle.getText().toString().trim();b.author=editedAuthor.getText().toString().trim();
                if(b.title.isEmpty()){editedTitle.setError("Inserisci il titolo");return;}
                dialog.dismiss();importMetadata(b,next,null);
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> { store.discardCandidate(b); dialog.dismiss(); next.run(); });
            if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    if (editedTitle != null) {
                        String t = editedTitle.getText().toString().trim(), a = editedAuthor.getText().toString().trim();
                        if (t.isEmpty()) { editedTitle.setError("Inserisci il titolo"); return; }
                        b.title = t; b.author = a.isEmpty() ? "Autore sconosciuto" : a;
                    }
                    if(duplicate==null && file && app.atlante.library.model.BookMatcher.best(b,store.snapshot(),x->x)!=null){dialog.dismiss();preview(b,next);return;}
                    if (duplicate != null) { Book linked = store.attachFile(b, duplicate); forgetCover(linked.id); Toast.makeText(this, "File collegato a «" + linked.title + "»", Toast.LENGTH_SHORT).show(); }
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
        lastOnlineQuery = query == null ? "" : query.trim(); autoSearch = !lastOnlineQuery.isEmpty(); clearSearch = lastOnlineQuery.isEmpty();
        onlineSearch();
    }
    private boolean autoSearch, clearSearch;

    private void onlineSearch() {
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(12), dp(20), dp(12));
        panel.addView(text("Cerca per titolo, autore o ISBN nel catalogo delle biblioteche italiane (SBN, per l’italiano) e su Open Library. Si aggiunge una scheda personale, anche se non possiedi il file. La ricerca invia al servizio solo il testo inserito.", 14, MUTED, false));
        gap(panel, 8);
        EditText input = field(panel, "Titolo, autore o ISBN", clearSearch ? "" : lastOnlineQuery.isEmpty() ? getPreferences(MODE_PRIVATE).getString("onlineQuery", "") : lastOnlineQuery);
        boolean runNow = autoSearch; autoSearch = false; clearSearch = false;
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        // Preferred language: its editions come first, with their own title and cover. Remembered.
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        ArrayList<String> languageNames = new ArrayList<>();
        for (String[] l : OnlineCatalog.LANGUAGES) languageNames.add(l[2]);
        Picker language = new Picker(panel, "Lingua preferita", languageNames, OnlineCatalog.languageName(prefs.getString("bookLanguage", "ita")));
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
                                AlertDialog loading = loading("Scheda del libro", "Caricamento trama e metadati…");
                                catalogWorker.execute(() -> {
                                    try {
                                        if (candidate.source.startsWith("sbn:")) SbnCatalog.enrich(candidate);
                                        else OnlineCatalog.enrich(candidate, code.isEmpty() ? "ita" : code);
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
        return option.isEmpty() ? "Non indicato" : option.equals("CATALOGO") ? "Senza file (cartaceo o da cercare)" : option;
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
            if (creating) { destination = "Biblioteca"; tab = "Tutti i libri"; query = ""; filterGenre = ""; filterFormat = ""; filterSaga = ""; showLibrary(); }
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

    private void readBackup(Uri source) {
        busy = true;
        AlertDialog loading = loading("Ripristino", "Lettura del backup…");
        worker.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(source)) {
                if (in == null) throw new IOException("File non disponibile");
                BackupArchive.Contents contents = CatalogImport.read(in, store.name(source));
                runOnUiThread(() -> { loading.dismiss(); busy = false; if (!isFinishing() && !isDestroyed()) confirmRestore(source, contents); });
            } catch (Exception e) { runOnUiThread(() -> { loading.dismiss(); busy = false; if (!isFinishing() && !isDestroyed()) error(e); }); }
        });
    }

    private void confirmRestore(Uri source, BackupArchive.Contents contents) {
        // Already in the library: same identifier, or (for other apps' lists) same ISBN or same title and author.
        ArrayList<Book> library = store.snapshot();
        LinkedHashMap<String, Book> fresh = new LinkedHashMap<>();
        for (Book b : contents.books) {
            boolean present = store.find(b.id) != null;
            if (!present) for (Book known : library) if (BookMatcher.same(known, b)) { present = true; break; }
            if (!present) fresh.put(b.id, b);
        }
        int kept = contents.books.size() - fresh.size();
        String date = contents.createdAt > 0 ? " del " + java.text.DateFormat.getDateTimeInstance().format(new Date(contents.createdAt)) : "";
        int read = 0, rated = 0, covers = 0;
        for (Book b : fresh.values()) { if (b.status.equals(Book.READ)) read++; if (b.rating > 0) rated++; if (contents.covers.containsKey(b.id)) covers++; }
        String summary = "Formato riconosciuto: " + contents.kind + date + "\n" + contents.books.size() + " libri nel file\n" + fresh.size() + " da aggiungere\n"
            + kept + " già presenti: restano come sono sul telefono"
            + (contents.atlante ? "" : "\n\nVerranno aggiunti come schede, senza file dei libri" + (read > 0 ? " · " + read + " già letti" : "")
                + (rated > 0 ? " · " + rated + " con voto" : "") + (covers > 0 ? " · " + covers + " copertine" : "") + ".");
        if (fresh.isEmpty()) { dialogBuilder().setTitle("Niente da aggiungere").setMessage(summary).setPositiveButton("OK", null).show(); return; }
        dialogBuilder().setTitle(contents.atlante ? "Ripristinare il backup?" : "Importare i libri?").setMessage(summary + "\n\nNessun libro già presente verrà modificato o rimosso.")
            .setNegativeButton("Annulla", null).setPositiveButton(contents.atlante ? "Ripristina" : "Importa", (d, w) -> applyRestore(source, contents, fresh)).show();
    }

    private void applyRestore(Uri source, BackupArchive.Contents contents, LinkedHashMap<String, Book> fresh) {
        busy = true;
        AlertDialog loading = loading(contents.atlante ? "Ripristino" : "Importazione", "Aggiunta di " + fresh.size() + " libri…");
        worker.execute(() -> {
            File staging = store.staging();
            Exception failure = null; int added = 0, missing = 0;
            try {
                LibraryStore.clear(staging);
                Set<String> withFiles = new HashSet<>();
                // Covers and book files of an Atlante backup are inside the ZIP; other formats carry their covers in contents.
                if (contents.atlante && contents.fromZip) {
                    try (InputStream in = getContentResolver().openInputStream(source)) {
                        if (in == null) throw new IOException("File non disponibile");
                        withFiles = BackupArchive.extract(in, fresh, staging, contents.nested, contents.prefix);
                    }
                }
                for (Map.Entry<String, byte[]> cover : contents.covers.entrySet()) {
                    if (!fresh.containsKey(cover.getKey())) continue;
                    File folder = new File(staging, cover.getKey());
                    if (!folder.isDirectory() && !folder.mkdirs()) continue;
                    try (OutputStream out = new FileOutputStream(new File(folder, "cover"))) { out.write(cover.getValue()); }
                }
                for (Book b : fresh.values()) if (!b.format.equals("CATALOGO") && !withFiles.contains(b.id)) missing++;
                added = store.restore(new ArrayList<>(fresh.values()), staging);
            } catch (Exception e) { failure = e; }
            finally { LibraryStore.clear(staging); }
            final Exception result = failure; final int count = added, withoutFile = missing;
            runOnUiThread(() -> {
                loading.dismiss(); busy = false; if (isFinishing() || isDestroyed()) return;
                covers.clear(); app.atlante.library.ui.compose.CoverImages.clear(); showLibrary();
                if (result != null) { error(result); return; }
                int withoutCover = 0; for (Book b : fresh.values()) if (store.find(b.id) != null && !store.cover(b).isFile()) withoutCover++;
                if (withoutCover > 0) fetchMissingCovers(new ArrayList<>(fresh.values()), false);
                dialogBuilder().setTitle(contents.atlante ? "Ripristino completato" : "Importazione completata").setMessage(count + " libri aggiunti alla biblioteca."
                    + (withoutCover > 0 ? "\n\nCerco in background le " + withoutCover + " copertine mancanti su Open Library (per ISBN, oppure titolo e autore). Le vedrai comparire man mano." : "")
                    + (withoutFile > 0 ? "\n\n" + withoutFile + " libri sono senza file: per leggerli reimportali da ☰ › Importa ebook. Note e avanzamento restano collegati." : ""))
                    .setPositiveButton("OK", null).show();
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
    private void markCover(Book b, String source) {
        Book current = store.find(b.id); if (current == null) return;
        try { current.meta.put("coverSource", source); store.replace(current); } catch (Exception ignored) { /* The cover is saved anyway. */ }
    }

    /**
     * Missing covers are downloaded; with {@code improve} also small ones (under 300 px) are replaced when a
     * clearly sharper cover of the same book is found. Covers chosen by the user are never replaced.
     */
    private void improveCovers(List<Book> books, boolean announce, boolean improve) {
        ArrayList<Book> todo = new ArrayList<>();
        for (Book b : books) {
            Book current = store.find(b.id); if (current == null) continue;
            String source = current.meta.optString("coverSource", "");
            if (!store.cover(current).isFile()) todo.add(current);
            else if (improve && !source.equals("gallery") && !source.equals("online") && coverWidth(current) < 300) todo.add(current);
        }
        if (todo.isEmpty()) { if (announce) Toast.makeText(this, "Tutte le copertine sono già presenti e nitide", Toast.LENGTH_SHORT).show(); return; }
        if (fetchingCovers) { if (announce) Toast.makeText(this, "Ricerca delle copertine già in corso", Toast.LENGTH_SHORT).show(); return; }
        fetchingCovers = true;
        if (announce) Toast.makeText(this, "Cerco " + todo.size() + " copertine su Open Library e Apple Books…", Toast.LENGTH_LONG).show();
        coverWorker.execute(() -> {
            int found = 0, better = 0;
            for (Book b : todo) {
                if (isDestroyed()) break;
                int old = coverWidth(b);
                app.atlante.library.online.CoverSearch.Cover c;
                try { c = app.atlante.library.online.CoverSearch.INSTANCE.best(b, (url, limit) -> { try { return OnlineCatalog.request(url, limit); } catch (Exception e) { throw new RuntimeException(e); } }, 8); }
                catch (RuntimeException e) { continue; }
                if (c == null || decode(null, c.getBytes(), 64) == null) continue;
                if (old > 0 && c.getWidth() < old * 3 / 2) continue;      // not clearly sharper: keep what is there
                try { store.saveDownloadedCover(b, c.getBytes()); markCover(b, "auto"); } catch (Exception ignored) { continue; }
                if (old == 0) found++; else better++;
                final boolean refresh = (found + better) % 4 == 0;
                runOnUiThread(() -> { forgetCover(b.id); if (refresh && reading == null && detailId == null) renderContent(); });
            }
            final int added = found, improved = better;
            runOnUiThread(() -> {
                fetchingCovers = false; if (isFinishing() || isDestroyed()) return;
                if (reading == null && detailId == null) renderContent();
                Toast.makeText(this, added + " copertine nuove" + (improve ? ", " + improved + " migliorate" : "") + " su " + todo.size() + " libri", Toast.LENGTH_LONG).show();
            });
        });
    }

    /** Covers found online for one book; the one you tap replaces the current cover. */
    private void chooseOnlineCover(Book b) {
        View[] holder = new View[1];
        holder[0] = app.atlante.library.ui.compose.CoverPickerView.create(this, palette, b, bytes -> {
            screen.removeView(holder[0]);
            worker.execute(() -> {
                try {
                    store.saveDownloadedCover(b, bytes); markCover(b, "online");
                    runOnUiThread(() -> { forgetCover(b.id); Book current = store.find(b.id); if (current != null) details(current); });
                } catch (Exception e) { runOnUiThread(() -> error(e)); }
            });
        }, () -> screen.removeView(holder[0]));
        screen.addView(holder[0], new FrameLayout.LayoutParams(1, 1));
    }

    // ---- Sync: Hardcover, Calibre, Kindle highlights ----------------------------------------------

    private void syncPage(LinearLayout p) {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        LinearLayout ko=surface(p,"KOREADER · AVANZAMENTO DI LETTURA");
        ko.addView(text("Ricevi da KOReader la percentuale letta dei libri collegati. Funziona anche con le sole schede, senza EPUB sul telefono.",14,MUTED,false));
        ko.addView(button("Configura KOReader",false,this::koreaderSettings));
        ko.addView(button("Collega un libro",false,this::linkKoreader));
        ko.addView(button("Ricevi avanzamento da KOReader",true,this::runKoreader));
        LinearLayout hc=surface(p,"HARDCOVER · STATO, PAGINA E VOTO");
        boolean hcOn=app.atlante.library.sync.HardcoverAuth.connected(this);
        hc.addView(text(hcOn?"Collegato"+(prefs.getString("hardcoverUser","").isEmpty()?"":" come @"+prefs.getString("hardcoverUser","")):"Non collegato. Serve una chiave personale creata nel tuo account Hardcover.",14,hcOn?INK:MUTED,false));
        if(!prefs.getString("hardcoverLast","").isEmpty()) hc.addView(text(prefs.getString("hardcoverLast",""),12,MUTED,false));
        gap(hc,8);
        if(hcOn) hc.addView(button("Sincronizza ora",true,this::runHardcover),new LinearLayout.LayoutParams(-1,-2));
        hc.addView(button(hcOn?"Impostazioni di Hardcover":"Collega Hardcover",hcOn?R.style.Atlante_Button_Text:0,this::hardcoverSettings),new LinearLayout.LayoutParams(-1,-2));

        LinearLayout cal=surface(p,"CALIBRE · STATO, PAGINA E CAPITOLO");
        boolean calOn=!prefs.getString("calibreUrl","").isEmpty();
        cal.addView(text(calOn?prefs.getString("calibreUrl",""):"Non collegato. Usa il server di contenuti di Calibre sulla rete di casa.",14,calOn?INK:MUTED,false));
        if(!prefs.getString("calibreLast","").isEmpty()) cal.addView(text(prefs.getString("calibreLast",""),12,MUTED,false));
        gap(cal,8);
        if(calOn) cal.addView(button("Sincronizza ora",true,this::runCalibre),new LinearLayout.LayoutParams(-1,-2));
        cal.addView(button(calOn?"Impostazioni di Calibre":"Collega Calibre",calOn?R.style.Atlante_Button_Text:0,this::calibreSettings),new LinearLayout.LayoutParams(-1,-2));
        cal.addView(button("Come preparare Calibre",R.style.Atlante_Button_Text,this::calibreHelp),new LinearLayout.LayoutParams(-1,-2));

        LinearLayout kin=surface(p,"KINDLE · EVIDENZIAZIONI E NOTE");
        kin.addView(text("Collega il Kindle al computer o al telefono e scegli il file documents/My Clippings.txt. Evidenziazioni e note entrano nel taccuino del libro. Amazon non condivide stato e pagina con altre app.",14,MUTED,false)); gap(kin,8);
        kin.addView(button("Importa My Clippings.txt",false,()->clippingsLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))),new LinearLayout.LayoutParams(-1,-2));

        LinearLayout src=surface(p,"FONTI DEI DATI BIBLIOGRAFICI");
        src.addView(text("Sempre attive: catalogo SBN, Open Library, Internet Archive. Facoltativa con la tua chiave: ISBNdb (a pagamento). Con la tua chiave Hardcover (in Impostazioni) anche saghe, numeri e universi.",14,MUTED,false)); gap(src,8);
        src.addView(text("ISBNdb: "+(prefs.getString("isbndbKey","").isEmpty()?"non configurato":"chiave inserita"),13,INK,false));
        src.addView(button("Chiavi delle fonti",R.style.Atlante_Button_Text,this::sourceKeys),new LinearLayout.LayoutParams(-1,-2));
        LinearLayout files=surface(p,"FILE E ALTRE APP");
        files.addView(text("Backup di Atlante, elenchi CSV (Goodreads, StoryGraph, Bookshelf), cataloghi HTML e librerie Calibre in ZIP.",14,MUTED,false)); gap(files,8);
        files.addView(button("Importa catalogo o backup",false,this::chooseBackup),new LinearLayout.LayoutParams(-1,-2));
        files.addView(button("Esporta backup",false,this::exportBackup),new LinearLayout.LayoutParams(-1,-2));
        files.addView(button("Scarica copertine mancanti",R.style.Atlante_Button_Text,()->fetchMissingCovers(store.snapshot(),true)),new LinearLayout.LayoutParams(-1,-2));
        p.addView(text("Chiavi e password restano solo su questo telefono. A Hardcover e Calibre vengono inviati solo stato, pagina, capitolo e voto dei libri collegati.",12,MUTED,false));
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
        if(books.isEmpty()){Toast.makeText(this,"Nessuna nuova saga riconosciuta. Puoi indicare saga e numero nella scheda del libro.",Toast.LENGTH_LONG).show();return;}
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
        if(books.isEmpty()){Toast.makeText(this,"Aggiungi prima un libro alla biblioteca",Toast.LENGTH_SHORT).show();return;}
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
                if(changes.isEmpty()){Toast.makeText(this,"Nessun avanzamento trovato. Controlla i collegamenti e invia prima da KOReader.",Toast.LENGTH_LONG).show();return;}
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

    private void runHardcover() {
        if(busy) return; busy=true;
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        HardcoverSync.Settings s=new HardcoverSync.Settings(); s.createMissing=prefs.getBoolean("hardcoverCreate",true); s.sendToRead=prefs.getBoolean("hardcoverToRead",false);
        AlertDialog[] dialog=new AlertDialog[1]; TextView label=progressDialog("Hardcover",dialog,()->s.cancelled=true);
        worker.execute(()->{
            Exception failure=null; HardcoverSync.Report r=null;
            try { s.token=app.atlante.library.sync.HardcoverAuth.token(this); r=HardcoverSync.sync(store,s,m->runOnUiThread(()->label.setText(m))); } catch(Exception e){ failure=e; }
            final Exception error=failure; final HardcoverSync.Report report=r;
            runOnUiThread(()->{ dialog[0].dismiss(); busy=false; if(isFinishing()||isDestroyed()) return;
                if(error!=null){ error(error); return; }
                String when=java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm",Locale.ITALIAN));
                String line=when+" · "+report.pushed+" inviati, "+report.pulled+" ricevuti, "+(report.created+report.added)+" nuovi";
                prefs.edit().putString("hardcoverUser",report.user).putString("hardcoverLast","Ultima sincronizzazione: "+line).apply();
                syncReport("Hardcover sincronizzato", report.remote+" libri su Hardcover · "+report.linked+" collegati\n"+report.pushed+" aggiornati su Hardcover · "+report.pulled+" aggiornati in Atlante\n"
                    +report.added+" aggiunti a Hardcover · "+report.created+" nuove schede in Atlante"+(report.recognized>0?"\n"+report.recognized+" libri riconosciuti come già presenti: nessun doppione":""), report.notes); });
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
                String when=java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm",Locale.ITALIAN));
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
        String lang=prefs.getString("bookLanguage","ita"); s.language=lang.isEmpty()?"ita":lang;
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
        if (request == RESTORE && data.getData() != null) { if (!busy) readBackup(data.getData()); return; }
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
            busy = true; showLibrary();
            worker.execute(() -> {
                int duplicate = 0, reattached = 0; ArrayList<String> errors = new ArrayList<>();
                LinkedHashMap<String, Book> candidates = new LinkedHashMap<>();
                for (Uri uri : uris) {
                    try { Book candidate = store.importBook(uri); if (candidate != null && candidate.reattached) { candidate.reattached = false; reattached++; } else if (candidate == null || candidates.containsKey(candidate.id)) duplicate++; else candidates.put(candidate.id, candidate); }
                    catch (Exception e) { errors.add(message(e)); }
                    finally {
                        try { getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
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
        } else if (request == EXPORT && data.getData() != null && exportId != null) {
            Book b = store.find(exportId); Uri dest = data.getData(); exportId = null;
            if (b == null) return;
            worker.execute(() -> {
                try (InputStream in = new FileInputStream(store.original(b)); OutputStream out = getContentResolver().openOutputStream(dest, "wt")) {
                    if (out == null) throw new IOException("Destinazione non disponibile");
                    byte[] buffer = new byte[32768]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    runOnUiThread(() -> Toast.makeText(this, "File esportato", Toast.LENGTH_SHORT).show());
                } catch (Exception e) { runOnUiThread(() -> error(e)); }
            });
        }
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
    private boolean canGoBack() { return detailId != null || !destination.equals("Home") || drawer != null || reading != null || searching() || !tab.equals("Tutti i libri"); }
    private void updateBack() { back.setEnabled(store != null && canGoBack()); }
    private void goBack() {
        if (drawer != null) closeDrawer();
        else if (reading != null) { Book b = reading; rememberPage(); details(b); }
        else if (detailId != null) { Book b=store.find(detailId); if(editingPage && b!=null) details(b); else showLibrary(); }
        else if (searching()) { query = ""; showLibrary(); }
        else if (!tab.equals("Tutti i libri")) { tab = "Tutti i libri"; showLibrary(); }
        else if (destination.equals("Impostazioni")) navigate(getPreferences(MODE_PRIVATE).getString("settingsReturn","Home"));
        else if (!destination.equals("Home")) navigate("Home");
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
        OPEN_STATS = "app.atlante.library.OPEN_STATS", OPEN_LIBRARY = "app.atlante.library.OPEN_LIBRARY", EXTRA_BOOK = "bookId";
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
        if (store == null || widgetIntent == null) return;
        Intent i = widgetIntent; widgetIntent = null;
        String action = i.getAction();
        if (OPEN_STATS.equals(action)) { if (reading == null) navigate("Statistiche"); return; }
        if (OPEN_LIBRARY.equals(action)) { if (reading == null) navigate("Biblioteca"); return; }
        Book b = store.find(i.getStringExtra(EXTRA_BOOK) == null ? "" : i.getStringExtra(EXTRA_BOOK));
        if (b == null) { Toast.makeText(this, "Questo libro non è più nella libreria.", Toast.LENGTH_LONG).show(); navigate("Biblioteca"); return; }
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
        if (stoppedVersion >= 0 && store.version() != stoppedVersion) { Book b = detailId == null ? null : store.find(detailId); if (b != null) details(b); else showLibrary(); }
        if (android.os.SystemClock.elapsedRealtime() - stoppedAt > 10 * 60 * 1000L && !destination.equals("Home")) {
            destination = "Home"; detailId = null; closeDrawer(); showLibrary();
        }
    }
    private long stoppedAt;

    /** Restart for a theme change, staying on the current page. */
    private void recreateHere() { getPreferences(MODE_PRIVATE).edit().putBoolean("keepPageOnce", true).commit(); recreate(); }
    @Override protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null); worker.shutdown(); catalogWorker.shutdown(); coverWorker.shutdown(); covers.shutdown(); super.onDestroy();
    }
    private void animateIn(View view) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        view.setAlpha(0); view.setTranslationY(dp(10));
        view.animate().alpha(1).translationY(0).setDuration(200).start();
    }
    private void navigate(String next) {
        if (grid != null && grid.getLayoutManager() != null && detailId == null) libraryPositions.put(destination, grid.getLayoutManager().onSaveInstanceState());
        if (companionScroll != null && detailId == null) pagePositions.put(destination, companionScroll.getScrollY());
        companionScroll = null; grid = null; body = null; detailId = null;
        destinationQueries.put(destination,query); query=destinationQueries.getOrDefault(next, "");
        destination = next; getPreferences(MODE_PRIVATE).edit().putString("destination",next).apply(); showLibrary();
    }
    private void addBottomNav() {
        com.google.android.material.bottomnavigation.BottomNavigationView nav = new com.google.android.material.bottomnavigation.BottomNavigationView(this);
        String[] names={"Home","Biblioteca","Scopri","Statistiche","Sync"};
        int[] icons={R.drawable.ic_home,R.drawable.ic_library,R.drawable.ic_discover,R.drawable.ic_stats,R.drawable.ic_sync};
        nav.setLabelVisibilityMode(com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED);
        nav.setBackgroundColor(WHITE);
        for(int i=0;i<names.length;i++) { nav.getMenu().add(0,i+1,i,names[i]).setIcon(icons[i]); if(names[i].equals(destination)) nav.setSelectedItemId(i+1); }
        nav.setOnItemSelectedListener(item -> { String next=names[item.getItemId()-1]; if(!destination.equals(next)) navigate(next); return true; });
        nav.setOnApplyWindowInsetsListener((v,insets)->insets); // root already pads for the system bars
        nav.setPadding(0,0,0,0); root.addView(nav,new LinearLayout.LayoutParams(-1,-2));
    }
    private LinearLayout companionPage(String title, String subtitle) {
        reading=null; pages=null; body=null; grid=null; detailId=null; base(PAPER);
        LinearLayout top=row(); top.setPadding(dp(12),dp(6),dp(12),dp(8)); root.addView(top);
        top.addView(iconButton(R.drawable.ic_menu,"Apri menu laterale",this::openDrawer));
        TextView name=text(destination.equals("Home")?"Atlante":destination,22,INK,true); name.setPadding(dp(8),0,0,0);
        top.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(iconButton(R.drawable.ic_settings,"Impostazioni",this::settings));
        companionScroll=new ScrollView(this); companionScroll.setFillViewport(true); root.addView(companionScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout panel=column(); panel.setPadding(dp(20),dp(4),dp(20),dp(28)); companionScroll.addView(panel);
        panel.addView(text(title,24,INK,true)); gap(panel,4); panel.addView(text(subtitle,14,MUTED,false)); gap(panel,16);
        return panel;
    }
    /** A destination drawn with Jetpack Compose, between the usual top bar and bottom navigation. */
    private void composePage(View content) {
        reading=null; pages=null; body=null; grid=null; detailId=null; companionScroll=null; base(PAPER);
        LinearLayout top=row(); top.setPadding(dp(12),dp(6),dp(12),dp(8)); root.addView(top);
        top.addView(iconButton(R.drawable.ic_menu,"Apri menu laterale",this::openDrawer));
        TextView name=text(destination,22,INK,true); name.setPadding(dp(8),0,0,0);
        top.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(iconButton(R.drawable.ic_settings,"Impostazioni",this::settings));
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        addBottomNav(); updateBack();
    }

    private void showCompanion() {
        if(destination.equals("Statistiche")) {
            android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
            app.atlante.library.model.ReadingGoals g=new app.atlante.library.model.ReadingGoals(prefs);
            composePage(app.atlante.library.ui.compose.StatisticsView.create(this,palette,store.snapshot(),g.getYearOn()?g.getYearBooks():0,g.getMonthOn()?g.getMonthBooks():0,motion(),this::goals,g));
            return;
        }
        if(destination.equals("Scopri")) {
            composePage(app.atlante.library.ui.compose.DiscoverView.create(this,palette,sagaHost,this::openLink,this::collections));
            return;
        }
        if(destination.equals("Impostazioni")) { LinearLayout p=companionPage("La tua biblioteca, come piace a te.","Aspetto, lettura e gestione dei dati."); settingsPage(p); updateBack(); return; }
        boolean sync=destination.equals("Sync");
        LinearLayout panel=companionPage(sync?"Invia e ricevi.":destination.equals("Statistiche")?"Ogni pagina conta.":destination.equals("Scopri")?"La prossima storia.":"Il tempo per un libro.",
            sync?"Stato, pagina e capitolo condivisi con i tuoi servizi di lettura.":destination.equals("Statistiche")?"Le tue letture, un giorno alla volta.":destination.equals("Scopri")?"Nuove idee da esplorare. La scelta è tua.":"Un piccolo spazio per le tue grandi storie.");
        if(sync) syncPage(panel); else home(panel);
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
    private void home(LinearLayout p) {
        List<Book> all=store.snapshot(); java.time.LocalDate today=java.time.LocalDate.now();
        long[] totals=ReadingJournal.totals(all,today);
        LinearLayout todayCard=surface(p,"OGGI · "+today.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM",Locale.ITALIAN)).toUpperCase(Locale.ITALIAN));
        LinearLayout metrics=row(); todayCard.addView(metrics);
        metric(metrics,Long.toString(totals[0]),"pagine"); metric(metrics,Long.toString(totals[1]/60),"minuti"); metric(metrics,Integer.toString(ReadingJournal.streak(all,today)),"giorni di fila");
        app.atlante.library.model.ReadingGoals goals=new app.atlante.library.model.ReadingGoals(getPreferences(MODE_PRIVATE));
        if(goals.getDailyPagesOn()) { int daily=goals.getDailyPages();
            gap(todayCard,16); todayCard.addView(text("Obiettivo pagine · "+totals[0]+" / "+daily+" pagine",13,MUTED,false));
            LinearProgressIndicator bar=new LinearProgressIndicator(this); bar.setIndicatorColor(SAGE); bar.setTrackColor(LINE); bar.setProgressCompat((int)Math.min(100,100*totals[0]/Math.max(1,daily)),motion()); todayCard.addView(bar); }
        if(goals.getDailyMinutesOn()) { int daily=goals.getDailyMinutes(); long minutes=totals[1]/60;
            gap(todayCard,12); todayCard.addView(text("Obiettivo minuti · "+minutes+" / "+daily+" min",13,MUTED,false));
            LinearProgressIndicator bar=new LinearProgressIndicator(this); bar.setIndicatorColor(SAGE); bar.setTrackColor(LINE); bar.setProgressCompat((int)Math.min(100,100*minutes/Math.max(1,daily)),motion()); todayCard.addView(bar); }
        if(timerBook!=null) { Book active=store.find(timerBook); if(active!=null) p.addView(button("Sessione in corso · "+active.title,true,() -> timerScreen(active))); }
        headline(p,"Sul tuo comodino");
        boolean found=false;
        for(Book b:all) if(b.status.equals(Book.READING)) { compactBook(p,b,true); found=true; }
        if(!found) { LinearLayout box=surface(p,""); box.addView(text("La prossima pagina ti aspetta.",20,INK,true)); gap(box,8); box.addView(text("Scegli un libro dalla biblioteca e inizia la tua lettura.",14,MUTED,false)); box.addView(button("Apri la biblioteca",true,() -> navigate("Biblioteca"))); }
        goalCard(p); headline(p,"Attività recente"); activity(p,null,8);
        headline(p,"Scelti dalla tua biblioteca");
        p.addView(text("Suggerimenti locali basati su generi, autori e voti delle tue letture. Non sono classifiche di vendita.",13,MUTED,false)); gap(p,12);
        Set<String> genres=new HashSet<>(), authors=new HashSet<>();
        for(Book b:all) if(b.status.equals(Book.READ) || ReadingJournal.rating(b)>=4) { if(!b.genre.isEmpty()) genres.add(b.genre); authors.add(b.author); }
        int n=0; for(Book b:all) if(b.status.equals(Book.TO_READ) && (genres.contains(b.genre)||authors.contains(b.author))) { compactBook(p,b,false); if(++n==3) break; }
        if(n==0) p.addView(text("Completa e valuta qualche libro per trovare affinità nel tuo catalogo.",14,MUTED,false));
    }
    private void metric(LinearLayout row,String value,String label) {
        LinearLayout item=column(); row.addView(item,new LinearLayout.LayoutParams(0,-2,1));
        TextView count=text(value,28,INK,true); item.addView(count); item.addView(text(label,11,MUTED,false));
    }
    private boolean motion() { return android.os.Build.VERSION.SDK_INT<26 || android.animation.ValueAnimator.areAnimatorsEnabled(); }
    private void compactBook(LinearLayout p,Book b,boolean actions) {
        LinearLayout box=surface(p,""); LinearLayout line=row(); box.addView(line); line.setGravity(Gravity.TOP);
        FrameLayout art=new FrameLayout(this); TextView fallback=text(b.title,13,SAGE,true); fallback.setPadding(dp(8),dp(12),dp(8),dp(12)); fallback.setBackground(background(PAPER,LINE)); art.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
        ImageView img=new ImageView(this); img.setScaleType(ImageView.ScaleType.FIT_CENTER); img.setContentDescription("Copertina di "+b.title); art.addView(img,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(76),dp(112)); size.setMargins(0,0,dp(16),0); line.addView(art,size); covers.thumbnail(b.id,store.cover(b),dp(150),img,fallback);
        LinearLayout info=column(); line.addView(info,new LinearLayout.LayoutParams(0,-2,1)); info.addView(text(b.title,20,INK,true)); gap(info,4); info.addView(text(b.author,13,MUTED,false)); gap(info,8);
        info.addView(text(progressLabel(b),13,SAGE,false)); info.addView(progressBar(b));
        if(b.totalPages>0) info.addView(text(Math.max(0,b.totalPages-b.currentPage)+" pagine rimaste"+estimate(b),12,MUTED,false));
        line.setOnClickListener(v->details(b));
        if(actions) { gap(box,12); box.addView(button("Aggiorna avanzamento",false,()->progressEditor(b,0,false))); }
    }
    private String estimate(Book b) {
        long pages=0,seconds=0; org.json.JSONArray a=ReadingJournal.array(b,"sessions");
        for(int i=0;i<a.length();i++) { org.json.JSONObject s=a.optJSONObject(i); if(s!=null && s.optLong("seconds")>0 && s.optInt("pages")>0) { pages+=s.optInt("pages"); seconds+=s.optLong("seconds"); } }
        if(pages<10 || seconds<60 || b.totalPages<=b.currentPage) return "";
        long minutes=Math.max(1,(b.totalPages-b.currentPage)*seconds/pages/60); return " · circa "+(minutes>=60?minutes/60+" h "+minutes%60+" min":minutes+" min");
    }
    private void goalCard(LinearLayout p) {
        java.time.LocalDate now=java.time.LocalDate.now(); List<Book> all=store.snapshot();
        app.atlante.library.model.ReadingGoals rotta=new app.atlante.library.model.ReadingGoals(getPreferences(MODE_PRIVATE));
        int yearly=rotta.getYearOn()?rotta.getYearBooks():0, monthly=rotta.getMonthOn()?rotta.getMonthBooks():0, done=ReadingJournal.completed(all,now,false);
        LinearLayout box=surface(p,"LA TUA ROTTA · "+now.getYear()); LinearLayout line=row(); box.addView(line);
        CircularProgressIndicator circle=new CircularProgressIndicator(this); circle.setIndeterminate(false); circle.setIndicatorSize(dp(74)); circle.setTrackThickness(dp(7)); circle.setTrackColor(LINE); circle.setIndicatorColor(SAGE); circle.setProgressCompat(Math.min(100,done*100/Math.max(1,yearly)),motion());
        circle.setContentDescription(done+" libri su "+yearly); line.addView(circle);
        LinearLayout info=column(); info.setPadding(dp(18),0,0,0); line.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        info.addView(text(yearly>0?done+" / "+yearly+" libri":done+(done==1?" libro":" libri"),25,INK,true)); info.addView(text("Quest’anno",13,MUTED,false)); gap(info,8);
        int monthDone=ReadingJournal.completed(all,now,true);
        info.addView(text(monthly>0?monthDone+" / "+monthly+" questo mese":monthDone+" questo mese",14,SAGE,false));
        box.addView(button("Imposta obiettivi",R.style.Atlante_Button_Text,this::goals));
        if(yearly>0&&done>=yearly) box.addView(text("★ Obiettivo annuale raggiunto!",18,COPPER,true));
    }
    private void backupMenu() {
        dialogBuilder().setTitle("Backup").setItems(new String[]{"Esporta backup (ZIP)", "Ripristina un backup o importa un catalogo"}, (d, w) -> { if (w == 0) exportBackup(); else chooseBackup(); })
            .setNegativeButton("Chiudi", null).show();
    }

    /** Goals are edited in the settings ("Obiettivi di lettura"); this opens them. */
    private void goals() { settings(); }

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
    private void bookActions(Book b) {
        String[] actions={"Modifica scheda","Completa i dati online","Preferito: "+(b.journal.optBoolean("favorite")?"sì":"no"),"Collezioni","Inizia una rilettura","Apri con KOReader"};
        dialogBuilder().setTitle("Il tuo libro").setItems(actions,(d,w)->{
            if(w==0) edit(b);
            if(w==1) { completeMetadata(b); return; }
            if(w==2) journalEdit(b,c->ReadingJournal.put(c,"favorite",!c.journal.optBoolean("favorite")));
            if(w==3) editCollections(b);
            if(w==4 && b.id.equals(timerBook)) { dialogBuilder().setTitle("Sessione in corso").setMessage("Concludi o scarta la sessione prima di iniziare una rilettura.").setPositiveButton("Apri sessione",(dd,ww)->timerScreen(b)).setNegativeButton("Annulla",null).show(); return; }
            if(w==4) dialogBuilder().setTitle("Iniziare una nuova lettura?").setMessage("Sessioni e recensione precedenti vengono conservate. Il nuovo tentativo riparte da pagina zero.").setNegativeButton("Annulla",null).setPositiveButton("Inizia",(dd,ww)->journalEdit(b,c->{
                org.json.JSONArray a=ReadingJournal.array(c,"attempts"); a.put(new org.json.JSONObject().put("number",ReadingJournal.attempt(c)).put("started",c.started).put("finished",c.finished).put("page",c.currentPage).put("percent",c.percent)); ReadingJournal.put(c,"attempts",a); ReadingJournal.put(c,"attempt",ReadingJournal.attempt(c)+1); c.currentPage=0;c.percent=0;c.page=0;c.started=java.time.LocalDate.now().toString();c.finished="";c.status=Book.READING;
            })).show();
            if(w==5) openKoreader(b);
        }).setNegativeButton("Chiudi",null).show();
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
        gap(p,12); org.json.JSONObject review=b.journal.optJSONObject("review");
        if(review!=null) { p.addView(text(review.optString("title"),22,INK,true)); p.addView(text(review.optString("body"),15,INK,false)); p.addView(text(italianDate(review.optString("date")),12,MUTED,false)); }
        p.addView(button(review==null?"Scrivi una recensione":"Modifica recensione",false,()->reviewEditor(b)));
        headline(p,"Il tuo taccuino"); p.addView(button("Aggiungi nota o citazione",false,()->noteEditor(b,-1)));
        EditText search=field(p,"Cerca nelle note",""); LinearLayout list=column(); p.addView(list);
        Runnable refresh=()->{list.removeAllViews();String q=search.getText().toString().toLowerCase(Locale.ROOT); org.json.JSONArray a=ReadingJournal.array(b,"notes");
            for(int i=a.length()-1;i>=0;i--){org.json.JSONObject n=a.optJSONObject(i);if(n==null||!n.toString().toLowerCase(Locale.ROOT).contains(q))continue;int index=i;LinearLayout item=surface(list,n.optString("type")+" · "+n.optString("date"));item.addView(text(n.optString("body"),15,INK,false));item.addView(text("Pagina "+n.optInt("page")+" · "+n.optString("tags"),12,MUTED,false));item.addView(button("Modifica nota",R.style.Atlante_Button_Text,()->noteEditor(b,index)));}
            if(a.length()==0)list.addView(text("Pensieri, citazioni, domande: ritrovali qui, anche offline.",14,MUTED,false));}; refresh.run();
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){refresh.run();}public void afterTextChanged(Editable e){}});
        headline(p,"Storia della lettura"); activity(p,b,100);
    }
    private LinearLayout editorPage(String title,Book b) {
        base(PAPER); detailId=b.id; editingPage=true; timerLabel=null; uiHandler.removeCallbacks(timerTick); LinearLayout top=row();top.setPadding(dp(8),dp(4),dp(8),dp(4));root.addView(top);top.addView(iconButton(R.drawable.ic_back,"Annulla e torna al libro",()->details(b)));top.addView(text(title,22,INK,true));
        LinearLayout panel=column();panel.setPadding(dp(20),dp(16),dp(20),dp(24));ScrollView scroll=new ScrollView(this);scroll.addView(panel);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));updateBack();return panel;
    }
    private void reviewEditor(Book b) {
        LinearLayout p=editorPage("La tua recensione",b);org.json.JSONObject review=b.journal.optJSONObject("review");if(review==null)review=new org.json.JSONObject();
        EditText title=field(p,"Titolo della recensione",review.optString("title")),body=field(p,"La tua recensione",review.optString("body")); body.setSingleLine(false);body.setMinLines(8);
        EditText date=field(p,"Data (AAAA-MM-GG)",review.optString("date",java.time.LocalDate.now().toString()));
        RatingBar rating=new RatingBar(this);rating.setNumStars(5);rating.setStepSize(.5f);rating.setRating((float)ReadingJournal.rating(b));rating.setContentDescription("Voto della recensione");p.addView(rating);
        p.addView(button("Salva recensione",true,()->{try{java.time.LocalDate.parse(date.getText().toString());}catch(Exception e){date.setError("Usa una data valida AAAA-MM-GG");return;}
            journalEdit(b,c->{ReadingJournal.put(c,"review",new org.json.JSONObject().put("title",title.getText().toString()).put("body",body.getText().toString()).put("date",date.getText().toString()).put("rating",rating.getRating()));ReadingJournal.put(c,"rating",(double)rating.getRating());c.rating=Math.round(rating.getRating());});}));
        if(b.journal.has("review"))p.addView(button("Elimina recensione",false,()->dialogBuilder().setTitle("Eliminare la recensione?").setNegativeButton("Annulla",null).setPositiveButton("Elimina",(d,w)->journalEdit(b,c->c.journal.remove("review"))).show()));
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
    private void activity(LinearLayout p,Book only,int limit) {
        ArrayList<org.json.JSONObject> events=new ArrayList<>();
        for(Book b:only==null?store.snapshot():Arrays.asList(only)) {
            for(String key:new String[]{"sessions","notes"}){org.json.JSONArray a=ReadingJournal.array(b,key);for(int i=0;i<a.length();i++){org.json.JSONObject e=a.optJSONObject(i);if(e==null)continue;try{org.json.JSONObject row=new org.json.JSONObject().put("at",e.optLong("at")).put("date",e.optString("date")).put("book",b.title).put("text",key.equals("sessions")?e.optInt("from")+" → "+e.optInt("to")+" pagine · "+e.optLong("seconds")/60+" min · lettura "+e.optInt("attempt",1):e.optString("type")+": "+e.optString("body"));events.add(row);}catch(Exception ignored){}}}
            for(String date:new String[]{b.started,b.finished})if(!date.isEmpty())try{events.add(new org.json.JSONObject().put("at",java.time.LocalDate.parse(date).toEpochDay()*86400000L).put("date",date).put("book",b.title).put("text",date.equals(b.finished)?"Libro completato":"Lettura iniziata"));}catch(Exception ignored){}
            org.json.JSONArray attempts=ReadingJournal.array(b,"attempts");for(int i=0;i<attempts.length();i++){org.json.JSONObject e=attempts.optJSONObject(i);if(e!=null)try{events.add(new org.json.JSONObject().put("date",e.optString("finished",e.optString("started"))).put("book",b.title).put("text","Lettura precedente · tentativo "+e.optInt("number")+" · "+e.optInt("page")+" pagine").put("at",0));}catch(Exception ignored){}}
        }
        events.sort((a,b)->Long.compare(b.optLong("at"),a.optLong("at")));int count=0;
        for(org.json.JSONObject e:events){if(count++>=limit)break;LinearLayout box=surface(p,italianDate(e.optString("date")));box.addView(text(e.optString("book"),18,INK,true));TextView label=text(e.optString("text"),14,MUTED,false);label.setMaxLines(4);box.addView(label);}
        if(events.isEmpty())p.addView(text("Le tue sessioni, note e letture completate appariranno qui.",14,MUTED,false));
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
        app.atlante.library.ui.compose.ProgressWheelState wheels=new app.atlante.library.ui.compose.ProgressWheelState(b.currentPage,b.totalPages,progress(b),b.currentPage,prefs.getInt("progressMode",0),(int)Math.min(1440,seconds/60),timed);
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
                Book c=Book.from(b.json()); c.totalPages=wheels.getTotal(); int m=wheels.getMode();
                if(m==0){ c.totalPages=wheels.getTotal(); c.currentPage=wheels.getPage();
                    // Unknown total: the page is kept, the percentage stays as it was (no invented number of pages).
                    if(c.totalPages>0) c.percent=Math.round(100f*c.currentPage/c.totalPages); }
                else if(m==1){ c.percent=wheels.getPercent(); if(c.totalPages>0) c.currentPage=Math.round(c.percent*c.totalPages/100f); }
                else { c.currentPage=b.currentPage+wheels.getRead(); if(c.totalPages>0){ c.currentPage=Math.min(c.currentPage,c.totalPages); c.percent=Math.round(100f*c.currentPage/c.totalPages);} }
                boolean done=c.totalPages>0?c.currentPage==c.totalPages&&c.currentPage>0:c.percent==100;
                boolean moved=c.currentPage>0||c.percent>0;
                if(done) c.status=Book.READ; else if(moved&&!c.status.equals(Book.READ)) c.status=Book.READING;
                Book.applyStatusEffects(c,java.time.LocalDate.now().toString());
                long duration=timed&&wheels.getMinutes()==seconds/60?seconds:wheels.getMinutes()*60L;
                if(chapterWheel!=null) { if(chapterWheel.getValue()==0) c.journal.remove("chapter"); else ReadingJournal.put(c,"chapter",b.chapters.get(chapterWheel.getValue()-1).title); }
                if(timed) ReadingJournal.sessionOnce(c,timerFrom,c.currentPage,duration,java.time.LocalDate.now(),b.id+":"+timerWall);
                else if(duration>0||c.currentPage!=b.currentPage) ReadingJournal.session(c,b.currentPage,c.currentPage,duration,java.time.LocalDate.now());
                store.replace(c); if(timed) clearTimer(); renderContent(); details(c);
            } catch(Exception e){ error(e); }
        }),new LinearLayout.LayoutParams(-1,-2));
        p.addView(button(timed?"Torna al timer":"Annulla",R.style.Atlante_Button_Text,()->{if(timed)timerScreen(b);else details(b);}),new LinearLayout.LayoutParams(-1,-2));
    }
    private long bootCount() { return android.provider.Settings.Global.getInt(getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1); }
    private void restoreTimer() {
        android.content.SharedPreferences p=getPreferences(MODE_PRIVATE);timerBook=p.getString("timerBook",null);timerAccumulated=p.getLong("timerAccumulated",0);timerFrom=p.getInt("timerFrom",0);timerRunning=p.getBoolean("timerRunning",false);timerMark=p.getLong("timerMark",0);timerBoot=p.getLong("timerBoot",-2);timerWall=p.getLong("timerWall",0);
        if(timerBook!=null&&store.find(timerBook)==null){clearTimer();return;}
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
        p.addView(text("Termina ferma il tempo e ti chiede fin dove sei arrivato.",12,MUTED,false)); gap(p,16);
        p.addView(button("Scarta sessione",R.style.Atlante_Button_Text,()->dialogBuilder().setTitle("Scartare la sessione?").setMessage("Questo tempo non verrà registrato.").setNegativeButton("Annulla",null).setPositiveButton("Scarta",(d,w)->{clearTimer();details(b);}).show()));
        uiHandler.removeCallbacks(timerTick);uiHandler.post(timerTick);
    }
    private void clearTimer(){timerBook=null;timerRunning=false;timerAccumulated=0;timerLabel=null;uiHandler.removeCallbacks(timerTick);persistTimer();}
}
