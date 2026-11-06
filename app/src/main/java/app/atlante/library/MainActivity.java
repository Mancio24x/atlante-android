package app.atlante.library;

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

/** Android edition. Uses only platform APIs, private storage and the system document picker. */
public final class MainActivity extends Activity {
    int INK, PAPER, WHITE, SAGE, COPPER, GOLD, MUTED, LINE, ON_ACCENT;
    private ThemePalette palette = ThemePalette.ALL[0];
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable filterTask;
    private int visibleBooks = 60;
    private final ExecutorService catalogWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService coverWorker = Executors.newSingleThreadExecutor();
    /** Grid thumbnails are decoded off the UI thread, separately from network cover downloads. */
    private final ExecutorService thumbWorker = Executors.newFixedThreadPool(2);
    private volatile int thumbGeneration;
    private ScrollView libraryScroll;
    private LinearLayout gridHost;
    private List<Book> shownBooks;
    private static final int IMPORT = 101, EXPORT = 102, COVER = 103, BACKUP = 104, RESTORE = 105;
    private boolean backupFiles = true;
    private final Runnable savePosition = () -> { if (this.store != null) save(); };
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> covers = new LruCache<String, Bitmap>((int) Math.min(64 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount() / 1024; }
    };
    private LibraryStore store;
    private LinearLayout root, content;
    private FrameLayout screen, drawer;
    private int insetTop, insetBottom;
    private String lastOnlineQuery = "";
    private String tab = "Tutti i libri", query = "", mapAuthor = "", exportId;
    private String filterStatus = "", filterGenre = "", filterFormat = "", sortOrder = "Titolo";
    private String coverBookId;
    private Book reading;
    private List<String> pages;
    private int page;
    private float fontSize = 20;
    private boolean readerDark, manga, busy, listView;
    private TextView notice;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            tab = state.getString("tab", tab); query = state.getString("query", "");
            mapAuthor = state.getString("author", ""); exportId = state.getString("export"); coverBookId = state.getString("coverBook");
            backupFiles = state.getBoolean("backupFiles", true);
        }
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        applyPalette(ThemePalette.find(prefs.getString("theme", "atlante")));
        filterStatus = prefs.getString("filterStatus", ""); filterGenre = prefs.getString("filterGenre", "");
        filterFormat = prefs.getString("filterFormat", ""); sortOrder = prefs.getString("sortOrder", "Titolo");
        listView = prefs.getBoolean("listView", false);
        OnlineCatalog.configure(new File(getCacheDir(), "catalog"));
        fontSize = Math.max(8, Math.min(80, prefs.getFloat("fontSize", 20)));
        readerDark = prefs.getBoolean("dark", false); manga = prefs.getBoolean("manga", false);
        try { store = new LibraryStore(this); }
        catch (Exception e) {
            dialogBuilder().setTitle("Libreria non disponibile")
                .setMessage("I dati salvati non sono stati modificati. " + message(e))
                .setCancelable(false).setPositiveButton("Chiudi", (d, w) -> finish()).show();
            return;
        }
        showLibrary();
        if (state != null && state.getString("reading") != null) {
            Book b = store.find(state.getString("reading")); if (b != null) openReader(b);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("coverBook", coverBookId); state.putBoolean("backupFiles", backupFiles);
        state.putString("tab", tab); state.putString("query", query);
        state.putString("author", mapAuthor); state.putString("export", exportId);
        if (reading != null) state.putString("reading", reading.id);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (store == null) return;
        if (reading != null && pages != null) showReader(); else showLibrary();
    }

    private void openDrawer() {
        if (drawer != null) { closeDrawer(); return; }
        drawer = new FrameLayout(this); FrameLayout overlay = drawer;
        View scrim = new View(this); scrim.setBackgroundColor(0x99000000);
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1,-1)); scrim.setOnClickListener(v -> closeDrawer());
        int width = Math.min(dp(340), Math.round(getResources().getDisplayMetrics().widthPixels * .88f));
        ScrollView scroll = new ScrollView(this); scroll.setBackground(background(WHITE, LINE)); scroll.setClipToOutline(true);
        FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(width,-1,Gravity.START); overlay.addView(scroll,size);
        LinearLayout menu = column(); menu.setPadding(dp(18), Math.max(dp(18),insetTop),dp(18),Math.max(dp(24),insetBottom)); scroll.addView(menu);
        LinearLayout top = row(); menu.addView(top);
        Button close = button("☰", false, this::closeDrawer); close.setContentDescription("Chiudi menu laterale");
        top.addView(close,new LinearLayout.LayoutParams(dp(52),dp(48)));
        TextView brand = text("  Atlante",28,INK,true); top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        menu.addView(text("LA TUA BIBLIOTECA",11,SAGE,false)); gap(menu,18);
        for (String name : new String[]{"Tutti i libri","Desideri","In lettura","Letti","Mappa","Autori","Saghe"}) {
            Button item = button((tab.equals(name) ? "•  " : "") + name,tab.equals(name),() -> {
                closeDrawer(); tab = name; visibleBooks = 60; showLibrary();
            }); item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); menu.addView(item); gap(menu,8);
        }
        gap(menu,12); rule(menu); gap(menu,16);
        menu.addView(button("⌕ Cerca libri online",false,() -> { closeDrawer(); onlineSearch(); })); gap(menu,8);
        Button add = button("+ Importa file",false,() -> { closeDrawer(); chooseBooks(); }); add.setEnabled(!busy); menu.addView(add); gap(menu,8);
        menu.addView(button("⚙ Impostazioni",false,() -> { closeDrawer(); settings(); }));
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        screen.addView(overlay,new FrameLayout.LayoutParams(-1,-1)); close.requestFocus();
        scroll.setTranslationX(-width); scroll.animate().translationX(0).setDuration(220).start();
    }
    private void closeDrawer() {
        if (drawer == null) return;
        FrameLayout closing = drawer; drawer = null;
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        closing.animate().alpha(0).setDuration(140).withEndAction(() -> {
            if (closing.getParent() instanceof ViewGroup) ((ViewGroup)closing.getParent()).removeView(closing);
        }).start();
    }
    private void settings() {
        LinearLayout panel = column(); panel.setPadding(dp(20),dp(12),dp(20),dp(22));
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = dialogBuilder().setTitle("Impostazioni").setView(scroll).setNegativeButton("Chiudi",null).create();
        panel.addView(text("ASPETTO",11,SAGE,false)); gap(panel,8);
        panel.addView(button("◐ Temi · " + palette.name,false,() -> { dialog.dismiss(); chooseTheme(); })); gap(panel,20);
        panel.addView(text("LETTURA",11,SAGE,false)); gap(panel,10);
        Switch dark = new Switch(this); dark.setText("Lettore scuro"); dark.setTextColor(INK); dark.setChecked(readerDark); dark.setMinHeight(dp(48));
        dark.setThumbTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{SAGE,MUTED}));
        dark.setTrackTintList(android.content.res.ColorStateList.valueOf(LINE)); panel.addView(dark);
        dark.setOnCheckedChangeListener((v, checked) -> { readerDark=checked; getPreferences(MODE_PRIVATE).edit().putBoolean("dark",checked).apply(); });
        TextView label = text("Dimensione del testo · " + Math.round(fontSize),15,INK,false); panel.addView(label);
        SeekBar font = new SeekBar(this); font.setMax(72); font.setProgress(Math.round(fontSize)-8); font.setContentDescription("Dimensione del testo del lettore");
        font.setProgressTintList(android.content.res.ColorStateList.valueOf(SAGE)); font.setThumbTintList(android.content.res.ColorStateList.valueOf(SAGE));
        font.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE)); panel.addView(font);
        font.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser) { if(fromUser) { fontSize=value+8; label.setText("Dimensione del testo · "+Math.round(fontSize)); getPreferences(MODE_PRIVATE).edit().putFloat("fontSize",fontSize).apply(); } }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        gap(panel,20); panel.addView(text("RICERCA ONLINE",11,SAGE,false)); gap(panel,8);
        panel.addView(text("Open Library · Le ricerche recenti e le schede vengono riutilizzate dalla cache. I libri e le note personali restano sul dispositivo.",14,MUTED,false));
        gap(panel,20); panel.addView(text("BACKUP",11,SAGE,false)); gap(panel,8);
        panel.addView(text("Salva catalogo, note, avanzamento e copertine in un file ZIP da conservare fuori dall’app. Disinstallando Atlante i dati interni vengono cancellati.",14,MUTED,false)); gap(panel,8);
        panel.addView(button("Esporta backup",false,() -> { dialog.dismiss(); exportBackup(); })); gap(panel,8);
        Button restore = button("Ripristina da backup",false,() -> { dialog.dismiss(); chooseBackup(); }); restore.setEnabled(!busy); panel.addView(restore);
        gap(panel,20); panel.addView(text("ATLANTE LIBRARY",11,SAGE,false)); gap(panel,8);
        panel.addView(button("Progetto e aggiornamenti",false,() -> startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/Mancio24x/atlante-android")))));
        dialog.show();
    }

    private void applyPalette(ThemePalette p) {
        palette = p; PAPER = p.paper; WHITE = p.surface; INK = p.ink; MUTED = p.muted;
        SAGE = p.accent; ON_ACCENT = p.onAccent; COPPER = p.warm; GOLD = p.onAccent; LINE = p.line;
    }
    private AlertDialog loading(String title, String message) {
        LinearLayout panel = row(); panel.setPadding(dp(24),dp(24),dp(24),dp(24));
        ProgressBar spinner = new ProgressBar(this); spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(SAGE));
        panel.addView(spinner,new LinearLayout.LayoutParams(dp(36),dp(36)));
        TextView label = text(message,15,INK,false); label.setPadding(dp(16),0,0,0); panel.addView(label);
        AlertDialog dialog = dialogBuilder().setTitle(title).setView(panel).setCancelable(false).create(); dialog.show(); return dialog;
    }
    private AlertDialog.Builder dialogBuilder() {
        android.view.ContextThemeWrapper context = new android.view.ContextThemeWrapper(this,
            palette.dark ? R.style.AtlanteDialogDark : R.style.AtlanteDialogLight);
        return new AlertDialog.Builder(context) {
            @Override public AlertDialog create() {
                AlertDialog dialog = super.create();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(background(WHITE, LINE));
                    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                }
                return dialog;
            }
        };
    }
    private void chooseTheme() {
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(12), dp(20), dp(20));
        panel.addView(text("Scegli l’atmosfera della tua biblioteca. Il tema viene ricordato sul dispositivo.", 14, MUTED, false)); gap(panel, 16);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = dialogBuilder().setTitle("Aspetto e temi").setView(scroll).setNegativeButton("Chiudi", null).create();
        for (ThemePalette option : ThemePalette.ALL) {
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
            tile.setOnClickListener(v -> { applyPalette(option); getPreferences(MODE_PRIVATE).edit().putString("theme", option.id).apply(); dialog.dismiss(); showLibrary(); });
            panel.addView(tile); gap(panel, 10);
        }
        dialog.show();
    }

    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
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
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(primary ? ON_ACCENT : INK); b.setMinHeight(dp(48));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x224C7880), background(primary ? SAGE : WHITE, primary ? SAGE : LINE), null);
        b.setBackground(ripple); b.setStateListAnimator(null);
        b.setOnClickListener(v -> action.run()); return b;
    }
    void base(int color) {
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
        reading = null; pages = null;
        base(PAPER);
        LinearLayout header = column(); header.setPadding(dp(20), dp(18), dp(20), dp(18));
        root.addView(header);
        LinearLayout masthead = row(); header.addView(masthead);
        Button hamburger = button("☰", false, this::openDrawer); hamburger.setContentDescription("Apri menu laterale");
        masthead.addView(hamburger, new LinearLayout.LayoutParams(dp(52), dp(48)));
        TextView brand = text("  ATLANTE LIBRARY", 12, SAGE, false);
        brand.setLetterSpacing(.12f); masthead.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = button("", false, this::settings); settings.setContentDescription("Impostazioni");
        android.graphics.drawable.Drawable gear = getResources().getDrawable(R.drawable.ic_settings, getTheme()); gear.setTint(INK);
        settings.setCompoundDrawablesWithIntrinsicBounds(gear,null,null,null); masthead.addView(settings, new LinearLayout.LayoutParams(dp(52), dp(48))); gap(header, 12);
        LinearLayout titleRow = row();
        TextView title = text("La tua biblioteca", 29, INK, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(titleRow); gap(header, 8);
        header.addView(text("Libri, autori e connessioni. Sempre con te.", 14, MUTED, false));
        gap(header, 10);
        EditText search = new EditText(this); search.setSingleLine(true); search.setTextSize(15);
        search.setTextColor(INK); search.setHintTextColor(MUTED); search.setHint("Cerca un titolo, un autore, una saga…");
        search.setContentDescription("Cerca nella biblioteca"); search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.setBackground(background(WHITE, LINE)); search.setText(query); header.addView(search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { query = s.toString(); visibleBooks = 60;
                if (filterTask != null) uiHandler.removeCallbacks(filterTask);
                filterTask = () -> { if (reading == null) renderContent(); }; uiHandler.postDelayed(filterTask, 180); }
            public void afterTextChanged(Editable e) { }
        });
        gap(header, 8);
        header.addView(button("Filtri e ordine" + (hasFilters() ? " · attivi" : "") + " · " + sortOrder, false, this::libraryFilters));
        gap(header, 8);
        LinearLayout actions = row();
        actions.addView(button("＋ Aggiungi libro", true, this::manualBook), new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button(listView ? "Griglia" : "Elenco", false, () -> {
            listView = !listView; getPreferences(MODE_PRIVATE).edit().putBoolean("listView", listView).apply(); showLibrary();
        }), new LinearLayout.LayoutParams(dp(100), dp(48)));
        header.addView(actions); gap(header, 8);
        rule(root);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); libraryScroll = scroll;
        // Continuous scrolling: append the next books shortly before the end of the list.
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (content != null && y + v.getHeight() >= content.getHeight() - dp(900)) appendBooks();
        });
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = column(); content.setPadding(dp(14), dp(16), dp(14), dp(28)); scroll.addView(content);
        notice = text("", 12, MUTED, false); notice.setPadding(dp(20), dp(10), dp(20), dp(10));
        rule(root); root.addView(notice); renderContent();
    }

    private ArrayList<Book> filtered() {
        return LibraryQuery.select(store.snapshot(), query, tab, filterStatus, filterGenre, filterFormat, sortOrder);
    }

    private boolean hasFilters() { return !filterStatus.isEmpty() || !filterGenre.isEmpty() || !filterFormat.isEmpty(); }

    private Spinner filterPicker(LinearLayout panel, String label, List<String> options, String selected) {
        panel.addView(text(label, 14, INK, false));
        Spinner picker = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent); view.setTextColor(INK); return view;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(INK); view.setBackgroundColor(WHITE); return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(adapter); picker.setSelection(Math.max(0, options.indexOf(selected)));
        panel.addView(picker, new LinearLayout.LayoutParams(-1, dp(48))); gap(panel, 10); return picker;
    }

    private void libraryFilters() {
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(12), dp(20), dp(12));
        TreeSet<String> genres = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Book book : store.snapshot()) if (!book.genre.isEmpty()) genres.add(book.genre);
        if (!filterGenre.isEmpty()) genres.add(filterGenre);
        ArrayList<String> genreOptions = new ArrayList<>(); genreOptions.add("Tutti i generi"); genreOptions.addAll(genres);
        Spinner status = filterPicker(panel, "Stato di lettura", Arrays.asList("Tutti gli stati", "Da leggere", "In lettura", "Letto", "In pausa", "Abbandonato"), filterStatus);
        Spinner genre = filterPicker(panel, "Genere", genreOptions, filterGenre);
        Spinner format = filterPicker(panel, "Formato", Arrays.asList("Tutti i formati", "EPUB", "TXT", "CBZ", "CATALOGO"), filterFormat);
        Spinner order = filterPicker(panel, "Ordina per", Arrays.asList("Titolo", "Autore", "Saga"), sortOrder);
        dialogBuilder().setTitle("Filtri e ordine").setView(panel)
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Azzera filtri", (d, w) -> { filterStatus = ""; filterGenre = ""; filterFormat = ""; applyLibraryFilters(); })
            .setPositiveButton("Applica", (d, w) -> {
                filterStatus = status.getSelectedItemPosition() == 0 ? "" : status.getSelectedItem().toString();
                filterGenre = genre.getSelectedItemPosition() == 0 ? "" : genre.getSelectedItem().toString();
                filterFormat = format.getSelectedItemPosition() == 0 ? "" : format.getSelectedItem().toString();
                sortOrder = order.getSelectedItem().toString(); applyLibraryFilters();
            }).show();
    }

    private void applyLibraryFilters() {
        getPreferences(MODE_PRIVATE).edit().putString("filterStatus", filterStatus).putString("filterGenre", filterGenre)
            .putString("filterFormat", filterFormat).putString("sortOrder", sortOrder).apply();
        visibleBooks = 60; showLibrary();
    }

    private void renderContent() {
        if (content == null) return;
        content.removeAllViews(); thumbGeneration++; gridHost = null; shownBooks = null;
        ArrayList<Book> list = filtered();
        notice.setText(busy ? "Importazione in corso…" : list.size() + " di " + store.snapshot().size() + " libri · Sul dispositivo · Lettura offline");
        if (list.isEmpty()) {
            gap(content, 32);
            content.addView(text(store.snapshot().isEmpty() ? "Ogni libro è un punto di partenza." : "Nessun libro trovato.", 30, INK, true));
            gap(content, 16);
            content.addView(text(store.snapshot().isEmpty() ? "Apri il menu ☰ per cercare nuovi titoli online o importare EPUB, TXT e CBZ. Tutte le tue letture, anche su carta, trovano posto qui." : "Prova un altro titolo, autore o nome di saga.", 16, MUTED, false));
            gap(content, 24);
            if (hasFilters()) content.addView(button("Azzera filtri", false, () -> { filterStatus = ""; filterGenre = ""; filterFormat = ""; applyLibraryFilters(); }));
            if (store.snapshot().isEmpty()) content.addView(button("Cerca il tuo primo libro", true, this::onlineSearch));
            return;
        }
        if (tab.equals("Mappa")) { showMap(list); return; }
        if (tab.equals("Autori") || tab.equals("Saghe")) { showIndex(list, tab.equals("Autori")); return; }
        content.addView(text(tab.equals("Tutti i libri") ? "I tuoi libri" : tab, 28, INK, true)); gap(content, 5);
        content.addView(text(list.size() + " titoli · Libri importati e schede dal catalogo online", 13, MUTED, false));
        gap(content, 16);
        gridHost = column(); content.addView(gridHost); shownBooks = list;
        int shown = Math.min(Math.max(PAGE_SIZE, visibleBooks), list.size()); visibleBooks = shown;
        grid(gridHost, list.subList(0, shown));
        // Short lists may not fill the screen and never scroll: fill it once laid out.
        fillScreen();
    }

    private static final int PAGE_SIZE = 60; // divisible by every column count, so batches fill whole rows

    private void fillScreen() {
        LinearLayout target = content;
        target.post(() -> {
            if (target != content || libraryScroll == null || target.getHeight() > libraryScroll.getHeight() + dp(900)) return;
            int before = visibleBooks; appendBooks(); if (visibleBooks > before) fillScreen();
        });
    }

    private void appendBooks() {
        if (gridHost == null || shownBooks == null || visibleBooks >= shownBooks.size()) return;
        int end = Math.min(visibleBooks + PAGE_SIZE, shownBooks.size());
        grid(gridHost, shownBooks.subList(visibleBooks, end)); visibleBooks = end;
    }

    private void grid(LinearLayout parent, List<Book> books) {
        int width = getResources().getConfiguration().screenWidthDp;
        int columns = listView ? 1 : width >= 1100 ? 6 : width >= 760 ? 4 : width >= 550 ? 3 : 2;
        int coverHeight = listView ? 150 : Math.min(310, Math.max(170, (width - 40 - columns * 12) / columns * 14 / 10));
        LinearLayout line = null;
        for (int i = 0; i < books.size(); i++) {
            if (i % columns == 0) { line = row(); line.setGravity(Gravity.TOP); parent.addView(line); }
            Book b = books.get(i);
            LinearLayout card = column(); card.setPadding(dp(10), dp(10), dp(10), dp(14)); card.setBackground(background(WHITE, LINE));
            LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(0, -2, 1);
            cardSize.setMargins(dp(5), 0, dp(5), dp(12)); line.addView(card, cardSize);
            FrameLayout art = new FrameLayout(this); art.setBackground(background(PAPER, Color.TRANSPARENT)); art.setClipToOutline(true);
            art.setPadding(dp(7), dp(7), dp(7), dp(7));
            card.addView(art, new LinearLayout.LayoutParams(-1, dp(coverHeight)));
            TextView fallback = text(b.author + "\n\n" + b.title, 18, SAGE, true);
            fallback.setBackground(background(PAPER, LINE)); fallback.setPadding(dp(12), dp(18), dp(12), dp(18));
            art.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
            ImageView image = new ImageView(this); image.setContentDescription("Copertina di " + b.title);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER); art.addView(image, new FrameLayout.LayoutParams(-1, -1));
            loadThumbnail(b, image, fallback, dp(coverHeight));
            TextView format = text(b.format.equals("CATALOGO") ? "DESIDERIO" : b.format, 10, INK, false); format.setTypeface(Typeface.DEFAULT_BOLD);
            format.setPadding(dp(5), dp(3), dp(5), dp(3)); format.setBackground(background(WHITE, Color.TRANSPARENT));
            art.addView(format, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
            gap(card, 10); TextView bookTitle = text(b.title, 18, INK, true); bookTitle.setMaxLines(2); bookTitle.setEllipsize(TextUtils.TruncateAt.END); card.addView(bookTitle); gap(card, 4);
            TextView author = text(b.author, 12, MUTED, false); author.setMaxLines(2); card.addView(author);
            if (!b.series.isEmpty()) { gap(card, 5); card.addView(text("↳ " + b.series + order(b), 12, SAGE, false)); }
            if (!b.status.equals("Da leggere")) { gap(card, 5); card.addView(text(b.status, 11, COPPER, false)); }
            gap(card, 6); card.addView(text(progressLabel(b), 11, SAGE, false));
            if (b.totalPages > 0) {
                ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                bar.setMax(b.totalPages); bar.setProgress(b.currentPage); bar.setProgressTintList(android.content.res.ColorStateList.valueOf(SAGE));
                bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE)); card.addView(bar, new LinearLayout.LayoutParams(-1, dp(8)));
            }
            card.setOnClickListener(v -> details(b)); art.setOnClickListener(v -> details(b));
        }
        int remainder = books.size() % columns;
        if (remainder != 0) for (int i = remainder; i < columns; i++) line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    }

    static String order(Book b) { return b.number > 0 ? " · n. " + number(b.number) : ""; }
    static String number(double number) { return number == (long)number ? Long.toString((long)number) : Double.toString(number); }
    /** Shows a cached thumbnail at once, otherwise decodes it in the background at card size. */
    private void loadThumbnail(Book b, ImageView image, TextView fallback, int size) {
        String key = "t:" + b.id;
        Bitmap cached = covers.get(key);
        if (cached != null) { image.setImageBitmap(cached); fallback.setVisibility(View.INVISIBLE); return; }
        fallback.setVisibility(View.INVISIBLE); image.setTag(key);
        File file = store.cover(b); int generation = thumbGeneration;
        thumbWorker.execute(() -> {
            if (generation != thumbGeneration) return; // the grid was rebuilt meanwhile
            Bitmap bitmap = null;
            try { if (file.isFile()) bitmap = decodeThumbnail(file.getPath(), size); } catch (Exception | OutOfMemoryError ignored) { }
            if (bitmap != null) covers.put(key, bitmap);
            final Bitmap result = bitmap;
            uiHandler.post(() -> {
                if (!key.equals(image.getTag())) return;
                if (result != null) image.setImageBitmap(result); else fallback.setVisibility(View.VISIBLE);
            });
        });
    }
    static Bitmap decodeThumbnail(String file, int max) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (options.outWidth / (options.inSampleSize * 2) >= max || options.outHeight / (options.inSampleSize * 2) >= max) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file, options);
    }
    private void forgetCover(String id) { covers.remove(id); covers.remove("t:" + id); }
    private Bitmap cover(Book b) {
        Bitmap cached = covers.get(b.id); if (cached != null) return cached;
        File file = store.cover(b); if (!file.exists()) return null;
        Bitmap bitmap = decode(file.getPath(), null, 512);
        if (bitmap != null) covers.put(b.id, bitmap); return bitmap;
    }
    static Bitmap decode(String file, byte[] bytes, int max) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        if (file != null) BitmapFactory.decodeFile(file, options); else BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > max || options.outHeight / options.inSampleSize > max) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return file != null ? BitmapFactory.decodeFile(file, options) : BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
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

    private void showMap(ArrayList<Book> list) {
        TextView label = text("CONNESSIONI DI LETTURA", 11, SAGE, false); label.setLetterSpacing(.18f); content.addView(label);
        gap(content, 8); content.addView(text("Ogni autore è un punto di partenza.", 28, INK, true));
        gap(content, 8); content.addView(text("Scegli un autore e segui le frecce. Tocca un libro per aprire la sua scheda.", 14, MUTED, false)); gap(content, 18);
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Book b : list) names.add(b.author);
        ArrayList<String> authors = new ArrayList<>(names);
        if (!authors.contains(mapAuthor)) mapAuthor = authors.get(0);
        Spinner picker = new Spinner(this);
        picker.setBackground(background(WHITE, LINE)); picker.setMinimumHeight(dp(52));
        picker.setContentDescription("Autore della mappa");
        picker.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, authors) {
            private View item(int position) {
                TextView value = text(getItem(position), 16, INK, false); value.setPadding(dp(16),dp(12),dp(16),dp(12));
                value.setBackgroundColor(WHITE); value.setMinHeight(dp(48)); return value;
            }
            @Override public View getView(int position, View view, ViewGroup parent) { return item(position); }
            @Override public View getDropDownView(int position, View view, ViewGroup parent) { return item(position); }
        });
        picker.setSelection(authors.indexOf(mapAuthor)); content.addView(picker); gap(content, 12);
        LinearLayout graphHost = column(); content.addView(graphHost);
        Runnable draw = () -> {
            graphHost.removeAllViews(); ArrayList<Book> selected = new ArrayList<>();
            for (Book b : list) if (b.author.equals(mapAuthor) && selected.size() < 250) selected.add(b);
            HorizontalScrollView horizontal = new HorizontalScrollView(this);
            horizontal.addView(new ReadingMap(this, mapAuthor, selected, this::details));
            graphHost.addView(horizontal);
        };
        draw.run();
        picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int index, long id) { mapAuthor = authors.get(index); draw.run(); }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        gap(content, 12); content.addView(text("Autore → Saga → Libro · I numeri indicano l’ordine di lettura. Scorri in orizzontale. Fino a 250 opere per mappa; tutti i libri restano nella biblioteca.", 12, MUTED, false));
    }

    private void details(Book b) {
        LinearLayout panel = column(); panel.setPadding(dp(24), dp(14), dp(24), dp(20));
        Bitmap art = cover(b);
        if (art != null) {
            ImageView image = new ImageView(this); image.setImageBitmap(art); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            panel.addView(image, new LinearLayout.LayoutParams(-1, dp(220))); gap(panel, 16);
        }
        panel.addView(text(b.title, 28, INK, true)); gap(panel, 6);
        if (!b.subtitle.isEmpty()) panel.addView(text(b.subtitle, 18, MUTED, true));
        panel.addView(text(b.author, 16, SAGE, false));
        if (!b.tags.isEmpty()) panel.addView(text("Tag: " + b.tags, 14, SAGE, false));
        if (!b.location.isEmpty()) panel.addView(text("Posizione: " + b.location, 14, MUTED, false));
        if (!b.medium.isEmpty()) panel.addView(text(b.medium, 14, MUTED, false));
        if (b.rating > 0) panel.addView(text("Valutazione: " + b.rating + "/5", 14, COPPER, false));
        if (!b.started.isEmpty() || !b.finished.isEmpty()) panel.addView(text("Inizio: " + available(b.started) + " · Fine: " + available(b.finished), 13, MUTED, false)); gap(panel, 12);
        bibliographic(panel, b);
        if (!b.series.isEmpty()) panel.addView(text(b.series + order(b), 14, SAGE, false));
        if (!b.genre.isEmpty()) panel.addView(text(b.genre, 13, MUTED, false));
        panel.addView(text((b.format.equals("CATALOGO") ? "Scheda senza file" : b.format) + " · " + b.status, 13, MUTED, false));
        panel.addView(text(progressLabel(b), 14, SAGE, false));
        if (!b.notes.isEmpty()) panel.addView(text(b.notes, 15, INK, false));
        if (!b.source.isEmpty()) panel.addView(button("Scheda su Open Library", false, () -> {
            if (b.source.matches("/works/OL[0-9]+W")) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://openlibrary.org" + b.source)));
        }));
        if (!b.description.isEmpty()) { gap(panel, 14); panel.addView(text(b.description, 15, INK, false)); }
        gap(panel, 18);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = dialogBuilder().setView(scroll).setNegativeButton("Chiudi", null).create();
        if (!b.format.equals("CATALOGO")) panel.addView(button(b.page > 0 ? "Riprendi la lettura" : "Leggi", true, () -> { dialog.dismiss(); openReader(b); })); gap(panel, 8);
        panel.addView(button("Stato di lettura", false, () -> {
            String[] values = {"Da leggere", "In lettura", "Letto", "In pausa", "Abbandonato"};
            dialogBuilder().setTitle("Stato di lettura").setItems(values, (d, w) -> {
                b.status = values[w]; if (b.status.equals("Letto") && b.totalPages > 0) b.currentPage = b.totalPages; save(); dialog.dismiss(); renderContent(); details(b);
            }).show();
        })); gap(panel, 8);
        panel.addView(button("Aggiorna avanzamento e note", false, () -> { dialog.dismiss(); progress(b); })); gap(panel, 8);
        panel.addView(button("Modifica scheda", false, () -> { dialog.dismiss(); edit(b); })); gap(panel, 8);
        panel.addView(button("Scegli copertina dalla galleria", false, () -> {
            coverBookId = b.id; dialog.dismiss();
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), COVER);
        })); gap(panel, 8);
        if (!b.format.equals("CATALOGO")) panel.addView(button("Esporta il file originale", false, () -> {
            exportId = b.id;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(b.format.equals("EPUB") ? "application/epub+zip" : b.format.equals("TXT") ? "text/plain" : "application/vnd.comicbook+zip")
                .putExtra(Intent.EXTRA_TITLE, b.title.replaceAll("[\\/:*?\"<>|]", "_") + "." + b.format.toLowerCase(Locale.ROOT));
            startActivityForResult(intent, EXPORT);
        })); gap(panel, 8);
        panel.addView(button("Rimuovi dalla libreria", false, () -> dialogBuilder().setTitle("Rimuovere il libro?")
            .setMessage("La copia in Atlante e il punto di lettura verranno rimossi. Il file da cui hai importato il libro resta al suo posto.")
            .setNegativeButton("Annulla", null).setPositiveButton("Rimuovi", (d, w) -> {
                try { store.remove(b); forgetCover(b.id); dialog.dismiss(); renderContent(); } catch (Exception e) { error(e); }
            }).show()));
        dialog.show();
    }

    private void bibliographic(LinearLayout panel, Book b) {
        panel.addView(text("Editore: " + available(b.publisher) + "\nAnno: " + available(b.year)
            + "\nLingua: " + available(b.language) + "\nISBN / identificatore: " + available(b.isbn)
            + "\nGenere: " + available(b.genre), 13, MUTED, false));
    }
    private static String available(String value) { return value.isEmpty() ? "Non disponibile" : value; }

    private void previewImports(ArrayList<Book> candidates, int index) {
        if (index >= candidates.size()) { renderContent(); return; }
        preview(candidates.get(index), () -> previewImports(candidates, index + 1));
    }

    private void preview(Book b, Runnable next) {
        LinearLayout panel = column(); panel.setPadding(dp(24), dp(16), dp(24), dp(20));
        Bitmap image = b.onlineCover == null ? cover(b) : decode(null, b.onlineCover, 700);
        ImageView previewCover = new ImageView(this); previewCover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewCover.setContentDescription("Copertina di " + b.title);
        if (image != null) {
            previewCover.setImageBitmap(image); panel.addView(previewCover, new LinearLayout.LayoutParams(-1, dp(220)));
        } else if (b.coverId > 0) {
            previewCover.setBackground(background(PAPER,LINE)); panel.addView(previewCover,new LinearLayout.LayoutParams(-1,dp(220)));
        }
        panel.addView(text(b.title, 28, INK, true)); panel.addView(text(b.author, 18, SAGE, false)); gap(panel, 12);
        bibliographic(panel, b); gap(panel, 14); panel.addView(text("Trama", 20, INK, true));
        panel.addView(text(available(b.description), 16, INK, false)); gap(panel, 12);
        panel.addView(text(b.source.isEmpty() ? "Dati estratti dal file scelto. Puoi correggerli dalla scheda dopo l’aggiunta." : "Fonte: Open Library · " + b.source + "\nEdizione di riferimento: " + available(b.edition) + " · I dati possono variare tra edizioni. La scheda non include il file del libro.", 13, MUTED, false));
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = dialogBuilder().setTitle("Anteprima del libro").setView(scroll)
            .setNegativeButton("Non aggiungere", null).setPositiveButton("Aggiungi alla mia libreria", null).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> { store.discardCandidate(b); dialog.dismiss(); next.run(); });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try { store.addCatalog(b); dialog.dismiss(); next.run(); }
                catch (Exception e) { error(e); }
            });
        });
        dialog.setOnCancelListener(d -> { store.discardCandidate(b); next.run(); }); dialog.show();
        if (image == null && b.coverId > 0) coverWorker.execute(() -> {
            byte[] bytes = OnlineCatalog.loadCover(b);
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
        if (b.totalPages > 0) return "Pagina " + b.currentPage + " / " + b.totalPages + " · " + Math.round(100.0 * b.currentPage / b.totalPages) + "%";
        return b.currentPage > 0 ? "Pagina " + b.currentPage : "Avanzamento da impostare";
    }

    private void progress(Book b) {
        LinearLayout panel = column(); panel.setPadding(dp(24), dp(12), dp(24), dp(12));
        panel.addView(text("Registra dove sei arrivato, anche per un libro cartaceo. Queste pagine sono separate dalla posizione del lettore digitale.", 14, MUTED, false));
        EditText current = field(panel, "Pagina attuale", Integer.toString(b.currentPage));
        EditText total = field(panel, "Pagine totali (0 se sconosciute)", Integer.toString(b.totalPages));
        current.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); total.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText notes = field(panel, "Note personali", b.notes); notes.setSingleLine(false); notes.setMinLines(3);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = dialogBuilder().setTitle("Avanzamento di lettura").setView(scroll)
            .setNegativeButton("Annulla", null).setPositiveButton("Salva", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int c, t;
            try { c = Integer.parseInt(current.getText().toString()); t = Integer.parseInt(total.getText().toString()); }
            catch (NumberFormatException e) { current.setError("Inserisci numeri interi validi"); return; }
            if (c < 0 || t < 0 || (t > 0 && c > t)) { current.setError("La pagina attuale deve essere tra 0 e il totale"); return; }
            int oldC = b.currentPage, oldT = b.totalPages; String oldNotes = b.notes, oldStatus = b.status;
            b.currentPage = c; b.totalPages = t; b.notes = notes.getText().toString();
            if (t > 0 && c == t) b.status = "Letto";
            else if (c > 0 && (b.status.equals("Da leggere") || b.status.equals("Letto"))) b.status = "In lettura";
            try { store.save(); } catch (Exception e) { b.currentPage = oldC; b.totalPages = oldT; b.notes = oldNotes; b.status = oldStatus; error(e); return; }
            dialog.dismiss(); renderContent(); details(b);
        })); dialog.show();
    }

    private void onlineSearch() {
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(12), dp(20), dp(12));
        panel.addView(text("Cerca su Open Library per titolo, autore o ISBN. Si aggiunge una scheda personale, anche se non possiedi il file. La ricerca invia al servizio solo il testo inserito.", 14, MUTED, false));
        EditText input = field(panel, "Titolo, autore o ISBN", lastOnlineQuery.isEmpty() ? getPreferences(MODE_PRIVATE).getString("onlineQuery", "") : lastOnlineQuery);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
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
            catalogWorker.execute(() -> {
                try {
                    ArrayList<Book> found = OnlineCatalog.search(term);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                        search.setEnabled(true); results.removeAllViews();
                        results.addView(text(found.isEmpty() ? "Nessun risultato. Prova titolo e autore o un ISBN." : "Primi " + found.size() + " risultati · Fonte: Open Library", 13, MUTED, false));
                        for (Book candidate : found) {
                            gap(results, 12); LinearLayout tile = column(); tile.setPadding(dp(16),dp(16),dp(16),dp(16));
                            tile.setBackground(background(WHITE,LINE)); results.addView(tile);
                            tile.addView(text(candidate.title, 21, INK, true)); gap(tile,4);
                            tile.addView(text(candidate.author + (candidate.year.isEmpty() ? "" : " · " + candidate.year), 14, SAGE, false)); gap(tile,12);
                            Book existing = store.find(candidate.id);
                            tile.addView(button(existing == null ? "Vedi la scheda completa" : "Già in libreria · Apri", false, () -> {
                                if (existing != null) { dialog.dismiss(); details(existing); return; }
                                AlertDialog loading = loading("Scheda del libro", "Caricamento trama e metadati…");
                                catalogWorker.execute(() -> {
                                    try {
                                        OnlineCatalog.enrich(candidate);
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
    }

    private EditText field(LinearLayout panel, String label, String value) {
        panel.addView(text(label, 12, SAGE, false));
        EditText input = new EditText(this); input.setText(value); input.setTextSize(16); input.setSingleLine(true);
        input.setTextColor(INK); input.setHintTextColor(MUTED); input.setBackground(background(PAPER, LINE));
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setContentDescription(label); panel.addView(input); gap(panel, 10); return input;
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
        EditText currentPage = field(panel, "Pagina attuale (facoltativa)", b.currentPage > 0 ? Integer.toString(b.currentPage) : "");
        EditText totalPages = field(panel, "Numero di pagine (facoltativo)", b.totalPages > 0 ? Integer.toString(b.totalPages) : "");
        currentPage.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); totalPages.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText subtitle = field(panel, "Sottotitolo", b.subtitle);
        EditText tags = field(panel, "Tag (separati da virgola)", b.tags);
        EditText location = field(panel, "Posizione nella libreria", b.location);
        Spinner medium = filterPicker(panel, "Formato del libro", Arrays.asList("", "Cartaceo", "Ebook", "Audiolibro"), b.medium);
        Spinner status = filterPicker(panel, "Stato", Arrays.asList("Da leggere", "In lettura", "Letto", "In pausa", "Abbandonato"), b.status);
        Spinner rating = filterPicker(panel, "Valutazione (0 = non assegnata)", Arrays.asList("0", "1", "2", "3", "4", "5"), Integer.toString(b.rating));
        EditText started = field(panel, "Inizio lettura (AAAA-MM-GG)", b.started);
        EditText finished = field(panel, "Fine lettura (AAAA-MM-GG)", b.finished);
        EditText notes = field(panel, "Note personali", b.notes); notes.setSingleLine(false); notes.setMinLines(3);
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
            int current, total;
            try {
                current = currentPage.getText().toString().trim().isEmpty() ? 0 : Integer.parseInt(currentPage.getText().toString().trim());
                total = totalPages.getText().toString().trim().isEmpty() ? 0 : Integer.parseInt(totalPages.getText().toString().trim());
                if (current < 0 || total < 0 || (total > 0 && current > total)) throw new NumberFormatException();
            } catch (NumberFormatException e) { currentPage.setError("Controlla le pagine: attuale non oltre il totale"); return; }
            b.currentPage = current; b.totalPages = total;
            double n = 0;
            try { if (!number.getText().toString().isEmpty()) n = Double.parseDouble(number.getText().toString()); }
            catch (NumberFormatException e) { number.setError("Inserisci un numero"); return; }
            if (!Double.isFinite(n) || n < 0) { number.setError("Inserisci un numero positivo"); return; }
            for (EditText date : new EditText[]{started, finished}) {
                String value = date.getText().toString().trim();
                try { if (!value.isEmpty()) java.time.LocalDate.parse(value); }
                catch (java.time.format.DateTimeParseException e) { date.setError("Usa una data valida: AAAA-MM-GG"); return; }
            }
            if (!started.getText().toString().trim().isEmpty() && !finished.getText().toString().trim().isEmpty()
                    && started.getText().toString().trim().compareTo(finished.getText().toString().trim()) > 0) {
                finished.setError("La fine precede l’inizio"); return;
            }
            b.subtitle = subtitle.getText().toString().trim(); b.tags = tags.getText().toString().trim();
            b.location = location.getText().toString().trim(); b.medium = medium.getSelectedItem().toString();
            b.status = status.getSelectedItem().toString(); b.rating = rating.getSelectedItemPosition();
            b.started = started.getText().toString().trim(); b.finished = finished.getText().toString().trim();
            b.notes = notes.getText().toString();
            b.title = title.getText().toString().trim(); b.author = author.getText().toString().trim();
            if (b.author.isEmpty()) b.author = "Autore sconosciuto";
            b.series = series.getText().toString().trim(); b.number = n; b.genre = genre.getText().toString().trim();
            b.description = description.getText().toString().trim(); b.publisher = publisher.getText().toString().trim();
            b.year = year.getText().toString().trim(); b.language = language.getText().toString().trim(); b.isbn = isbn.getText().toString().trim();
            try {
                if (creating) store.addCatalog(b);
                else store.replace(b);
            } catch (Exception e) { error(e); return; }
            if (creating) { tab = "Tutti i libri"; query = ""; filterStatus = ""; filterGenre = ""; filterFormat = ""; showLibrary(); }
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
            startActivityForResult(intent, BACKUP);
        }).setNegativeButton("Annulla", null).show();
    }

    private void chooseBackup() {
        if (busy) return;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"}), RESTORE);
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
                BackupArchive.Contents contents = BackupArchive.readManifest(in);
                runOnUiThread(() -> { loading.dismiss(); busy = false; if (!isFinishing() && !isDestroyed()) confirmRestore(source, contents); });
            } catch (Exception e) { runOnUiThread(() -> { loading.dismiss(); busy = false; if (!isFinishing() && !isDestroyed()) error(e); }); }
        });
    }

    private void confirmRestore(Uri source, BackupArchive.Contents contents) {
        LinkedHashMap<String, Book> fresh = new LinkedHashMap<>();
        for (Book b : contents.books) if (store.find(b.id) == null) fresh.put(b.id, b);
        int kept = contents.books.size() - fresh.size();
        String date = contents.createdAt > 0 ? java.text.DateFormat.getDateTimeInstance().format(new Date(contents.createdAt)) : "data sconosciuta";
        String summary = "Backup del " + date + "\n" + contents.books.size() + " libri nel backup\n" + fresh.size() + " da aggiungere\n"
            + kept + " già presenti: restano come sono sul telefono";
        if (fresh.isEmpty()) { dialogBuilder().setTitle("Niente da ripristinare").setMessage(summary).setPositiveButton("OK", null).show(); return; }
        dialogBuilder().setTitle("Ripristinare il backup?").setMessage(summary + "\n\nNessun libro già presente verrà modificato o rimosso.")
            .setNegativeButton("Annulla", null).setPositiveButton("Ripristina", (d, w) -> applyRestore(source, fresh)).show();
    }

    private void applyRestore(Uri source, LinkedHashMap<String, Book> fresh) {
        busy = true;
        AlertDialog loading = loading("Ripristino", "Copia di " + fresh.size() + " libri…");
        worker.execute(() -> {
            File staging = store.staging();
            Exception failure = null; int added = 0, missing = 0;
            try (InputStream in = getContentResolver().openInputStream(source)) {
                if (in == null) throw new IOException("File non disponibile");
                LibraryStore.clear(staging);
                Set<String> withFiles = BackupArchive.extract(in, fresh, staging);
                for (Book b : fresh.values()) if (!b.format.equals("CATALOGO") && !withFiles.contains(b.id)) missing++;
                added = store.restore(new ArrayList<>(fresh.values()), staging);
            } catch (Exception e) { failure = e; }
            finally { LibraryStore.clear(staging); }
            final Exception result = failure; final int count = added, withoutFile = missing;
            runOnUiThread(() -> {
                loading.dismiss(); busy = false; if (isFinishing() || isDestroyed()) return;
                covers.evictAll(); showLibrary();
                if (result != null) { error(result); return; }
                dialogBuilder().setTitle("Ripristino completato").setMessage(count + " libri aggiunti alla biblioteca."
                    + (withoutFile > 0 ? "\n\n" + withoutFile + " libri sono senza file: per leggerli reimportali da ☰ › Importa file. Note e avanzamento restano collegati." : ""))
                    .setPositiveButton("OK", null).show();
            });
        });
    }

    private void chooseBooks() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, IMPORT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
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
                    runOnUiThread(() -> { forgetCover(target.id); renderContent(); details(target); });
                } catch (Exception e) { runOnUiThread(() -> error(e)); }
            });
            return;
        }
        if (request == BACKUP && data.getData() != null) { writeBackup(data.getData()); return; }
        if (request == RESTORE && data.getData() != null) { if (!busy) readBackup(data.getData()); return; }
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
                if (!store.original(b).isFile()) throw new IOException("Il file di questo libro non è sul telefono: reimportalo da ☰ › Importa file. Note e avanzamento restano collegati.");
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
        int paper = readerDark ? 0xff101014 : PAPER, ink = readerDark ? 0xffF1EDF5 : INK;
        base(paper);
        LinearLayout header = column(); header.setPadding(dp(16), dp(12), dp(16), dp(12)); root.addView(header);
        LinearLayout bar = row(); header.addView(bar);
        bar.addView(button("‹ Biblioteca", false, () -> { rememberPage(); showLibrary(); }));
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
            gap(footer, 6); footer.addView(button("Segna come letto", false, () -> { reading.status = "Letto"; rememberPage(); showReader(); }));
        }
    }

    private void changeFont(boolean increase) {
        fontSize = Math.max(8, Math.min(80, Math.round(fontSize * (increase ? 1.2f : .8f))));
        getPreferences(MODE_PRIVATE).edit().putFloat("fontSize", fontSize).apply(); showReader();
    }

    @Override public void onBackPressed() {
        if (drawer != null) { closeDrawer(); return; }
        if (reading != null) { rememberPage(); showLibrary(); }
        else if (!query.isEmpty()) { query = ""; showLibrary(); }
        else super.onBackPressed();
    }
    @Override protected void onStop() { if (reading != null) rememberPage(); super.onStop(); }
    @Override protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null); worker.shutdown(); catalogWorker.shutdown(); coverWorker.shutdown(); thumbWorker.shutdownNow(); super.onDestroy();
    }
}
