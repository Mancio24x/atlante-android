package app.atlante.library.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import app.atlante.library.MainActivity
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingGoals
import app.atlante.library.model.ReadingJournal
import app.atlante.library.storage.LibraryStore
import app.atlante.library.ui.ThemePalette
import app.atlante.library.ui.ThemePreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared by the three home screen widgets: their per-widget settings, the data they show (always the app's own
 * library and goals, read locally: no network), the colours of the app's theme, the covers and the refresh.
 */
object Widgets {
    // Per-widget settings, kept by Glance for each widget (they survive restarts).
    val BOOK = stringPreferencesKey("bookId")
    val PAGE_STEP = intPreferencesKey("pageStep")
    val PERCENT_STEP = intPreferencesKey("percentStep")
    val COLORS = stringPreferencesKey("colors")
    /** Grows at every refresh: Glance redraws only when a widget's own state changes. */
    val VERSION = intPreferencesKey("version")

    const val DEFAULT_PAGES = 5
    const val DEFAULT_PERCENT = 1
    /** "app" (the app's theme), "dynamic" (Android wallpaper colours), "light", "dark". */
    val COLOR_MODES = listOf("app" to "Colori dell’app", "dynamic" to "Colori di Android", "light" to "Chiaro", "dark" to "Scuro")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    private val widgets: List<Pair<Class<*>, () -> GlanceAppWidget>> = listOf(
        SmallWidgetReceiver::class.java to { SmallWidget() },
        MediumWidgetReceiver::class.java to { MediumWidget() },
        LargeWidgetReceiver::class.java to { LargeWidget() },
    )

    private fun anyPlaced(context: Context): Boolean = runCatching {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        widgets.any { (receiver, _) -> manager.getAppWidgetIds(ComponentName(context, receiver)).isNotEmpty() }
    }.getOrDefault(false)

    /** After a save of the library: one refresh for a burst of saves, nothing at all when no widget is placed. */
    @JvmStatic fun libraryChanged(context: Context) {
        val app = context.applicationContext ?: return
        if (!anyPlaced(app)) return
        synchronized(this) { pending?.cancel(); pending = scope.launch { delay(400); update(app) } }
    }

    /** Theme, goals or anything else changed: every widget redraws (called when the app goes to the background). */
    @JvmStatic fun refresh(context: Context) {
        val app = context.applicationContext ?: return
        if (!anyPlaced(app)) return
        scope.launch { update(app) }
    }

    suspend fun update(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        for ((_, make) in widgets) {
            val widget = make()
            for (id in manager.getGlanceIds(widget.javaClass)) {
                updateAppWidgetState(context, id) { it[VERSION] = (it[VERSION] ?: 0) + 1 }
                widget.update(context, id)
            }
        }
    }

    // ---- what a widget shows -------------------------------------------------------------------------

    data class Goal(val label: String, val done: Long, val goal: Int, val unit: String) {
        val fraction get() = if (goal <= 0) 0f else (done.toFloat() / goal).coerceIn(0f, 1f)
    }

    class Shown(val book: Book?, val percent: Int, val cover: Bitmap?, val goals: List<Goal>, val pageStep: Int, val percentStep: Int, val chosenMissing: Boolean)

    fun store(context: Context): LibraryStore? = runCatching { LibraryStore.shared(context) }.getOrNull()

    /** Books being read, most recently read first (last journal session, then start date). */
    fun reading(books: List<Book>): List<Book> = books.filter { it.status == Book.READING }
        .sortedByDescending { b -> maxOf(lastSession(b), b.addedAt) }

    private fun lastSession(b: Book): Long {
        val a = ReadingJournal.array(b, "sessions"); var last = 0L
        for (i in 0 until a.length()) last = maxOf(last, a.optJSONObject(i)?.optLong("at") ?: 0L)
        return last
    }

    /**
     * The book chosen for this widget; when it was removed or is finished, the book being read most recently
     * (the choice stays saved: the widget goes back to it if it is read again).
     */
    fun shown(context: Context, state: Preferences, withGoals: Boolean, coverPx: Int): Shown {
        val books = store(context)?.snapshot() ?: emptyList()
        // A book merged into another one (Ripara duplicati) is followed to the record that stayed.
        val chosenId = state[BOOK]?.let { app.atlante.library.storage.MergeHistory.redirect(context, it) }
        val chosen = chosenId?.let { id -> books.firstOrNull { it.id == id } }
        val book = chosen?.takeIf { it.status != Book.READ } ?: reading(books).firstOrNull()
        val goals = if (withGoals) goals(context, books) else emptyList()
        return Shown(book, book?.let { QuickProgress.percentOf(it) } ?: 0, book?.let { cover(context, it, coverPx) }, goals,
            state[PAGE_STEP] ?: DEFAULT_PAGES, state[PERCENT_STEP] ?: DEFAULT_PERCENT, chosenId != null && chosen == null)
    }

    /** The goals switched on in the app (Settings › Obiettivi di lettura), same numbers as Statistics. */
    fun goals(context: Context, books: List<Book>): List<Goal> {
        val g = ReadingGoals(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        val out = ArrayList<Goal>()
        if (g.dailyMinutesOn) g.minutesToday(books).let { out += Goal("Oggi", it.done, it.goal, "min") }
        if (g.dailyPagesOn) g.pagesToday(books).let { out += Goal(if (g.dailyMinutesOn) "Pagine oggi" else "Oggi", it.done, it.goal, "pag.") }
        if (g.monthOn) g.month(books).let { out += Goal("Questo mese", it.done, it.goal, "libri") }
        if (g.yearOn) g.year(books).let { out += Goal("Quest’anno", it.done, it.goal, "libri") }
        return out
    }

    const val PREFS = "MainActivity"

    // ---- covers: the app's own file, decoded once at widget size -------------------------------------

    private val covers = object : LruCache<String, Bitmap>(6 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }

    /** The cover saved with the book, scaled to about [maxPx] tall; null when the book has none (text cover). */
    fun cover(context: Context, b: Book, maxPx: Int): Bitmap? {
        val file = store(context)?.cover(b) ?: return null
        if (!file.isFile || file.length() < 500) return null
        val key = "${file.path}:${file.lastModified()}:$maxPx"
        covers.get(key)?.let { return it }
        return decode(file, maxPx)?.also { covers.put(key, it) }
    }

    fun decode(file: File, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }) ?: return null
        if (bitmap.height <= maxPx) bitmap
        else Bitmap.createScaledBitmap(bitmap, bitmap.width * maxPx / bitmap.height, maxPx, true).also { if (it !== bitmap) bitmap.recycle() }
    }.getOrNull()

    // ---- colours -------------------------------------------------------------------------------------

    /** Null = Android's dynamic colours (GlanceTheme). Otherwise the app's palette, or a light/dark one with its accent. */
    fun palette(context: Context, mode: String): ThemePalette? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val theme = prefs.getString("theme", "atlante") ?: "atlante"
        if (mode == "dynamic" || (mode == "app" && theme == "dynamic")) return null
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        var p = ThemePalette.find(theme)
        if (theme == "custom") p = ThemePalette("custom", "Il mio tema", "", p.dark, p.paper, p.surface, p.ink, p.muted, p.accent, p.onAccent, p.line, p.warm)
        if (mode == "app") p = ThemePreferences.resolve(prefs, p, systemDark)
        // Light or dark for this widget only: the app's colours, on a light or dark paper.
        if (mode == "light" || mode == "dark") p = ThemePreferences.resolve(prefs, p, mode == "dark", mode)
        return p
    }

    fun color(argb: Int) = Color(argb)

    // ---- opening the app -------------------------------------------------------------------------------

    /** Reuses the running app (singleTop) instead of stacking a new copy at every tap. */
    fun open(context: Context, action: String, bookId: String? = null): Intent = Intent(context, MainActivity::class.java)
        .setAction(action).apply { if (bookId != null) putExtra(MainActivity.EXTRA_BOOK, bookId) }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Distinct data per book and action: Android would otherwise merge the PendingIntents of two widgets.
        .setData(android.net.Uri.parse("atlante://widget/$action/${bookId ?: ""}"))
}
