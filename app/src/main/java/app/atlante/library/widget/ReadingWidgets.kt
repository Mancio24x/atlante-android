package app.atlante.library.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.atlante.library.MainActivity
import app.atlante.library.R
import app.atlante.library.model.Book
import app.atlante.library.ui.ThemePalette

// ---- colours: the app's theme, Android's dynamic colours, or a light/dark version, per widget ---------------

class WidgetColors(val background: ColorProvider, val surface: ColorProvider, val ink: ColorProvider, val muted: ColorProvider,
                   val accent: ColorProvider, val onAccent: ColorProvider, val track: ColorProvider) {
    companion object {
        fun of(p: ThemePalette) = WidgetColors(ColorProvider(Color(p.paper)), ColorProvider(Color(p.surface)), ColorProvider(Color(p.ink)),
            ColorProvider(Color(p.muted)), ColorProvider(Color(p.accent)), ColorProvider(Color(p.onAccent)), ColorProvider(Color(p.line)))
    }
}

@Composable
private fun colors(context: Context, state: Preferences): WidgetColors =
    Widgets.palette(context, state[Widgets.COLORS] ?: "app")?.let { WidgetColors.of(it) } ?: GlanceTheme.colors.let { c ->
        WidgetColors(c.widgetBackground, c.surfaceVariant, c.onSurface, c.onSurfaceVariant, c.primary, c.onPrimary, c.outline)
    }

// ---- actions ------------------------------------------------------------------------------------------------

private val BOOK_ID = ActionParameters.Key<String>("bookId")
private val STEP = ActionParameters.Key<Int>("step")

/** "+N pagine": saved in the library at once, every widget redrawn; the end of the book is confirmed in the app. */
class AddPagesAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[BOOK_ID] ?: return
        apply(context, id) { store, book -> QuickProgress.addPages(store, book, parameters[STEP] ?: Widgets.DEFAULT_PAGES) }
    }
}

/** "+N%": same, by percentage (the page follows when the total is known). */
class AddPercentAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[BOOK_ID] ?: return
        apply(context, id) { store, book -> QuickProgress.addPercent(store, book, parameters[STEP] ?: Widgets.DEFAULT_PERCENT) }
    }
}

private suspend fun apply(context: Context, bookId: String, step: (app.atlante.library.storage.LibraryStore, String) -> QuickProgress.Outcome) {
    val store = Widgets.store(context) ?: return
    val outcome = runCatching { step(store, bookId) }.getOrNull()
    if (outcome == QuickProgress.Outcome.CONFIRM_FINISH)
        context.startActivity(Widgets.open(context, MainActivity.OPEN_PROGRESS, bookId))
    Widgets.update(context)
}

// ---- pieces shared by the three widgets ---------------------------------------------------------------------

private fun configure(context: Context, appWidgetId: Int): Intent = Intent(context, WidgetConfigActivity::class.java)
    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    .setData(android.net.Uri.parse("atlante://widget/configure/$appWidgetId"))

@Composable
private fun CoverBox(shown: Widgets.Shown, c: WidgetColors, modifier: GlanceModifier) {
    val b = shown.book ?: return
    Box(modifier.cornerRadius(10.dp).background(c.surface), contentAlignment = Alignment.Center) {
        if (shown.cover != null)
            Image(ImageProvider(shown.cover), "Copertina di ${b.title}", GlanceModifier.fillMaxSize().cornerRadius(10.dp), contentScale = ContentScale.Crop)
        else Column(GlanceModifier.fillMaxSize().padding(8.dp).background(c.accent), verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally) {
            // Text cover in the theme colours when the book has no picture.
            Text(b.title, maxLines = 4, style = TextStyle(color = c.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(4.dp))
            Text(b.author, maxLines = 2, style = TextStyle(color = c.onAccent, fontSize = 10.sp))
        }
    }
}

@Composable
private fun Progress(shown: Widgets.Shown, c: WidgetColors, big: Boolean) {
    val b = shown.book ?: return
    val pages = if (b.totalPages > 0) " · pagina ${b.currentPage} di ${b.totalPages}" else if (b.currentPage > 0) " · pagina ${b.currentPage}" else ""
    Text("${shown.percent}%" + if (big) pages else "", maxLines = 1, style = TextStyle(color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium))
    Spacer(GlanceModifier.height(4.dp))
    LinearProgressIndicator(shown.percent / 100f, GlanceModifier.fillMaxWidth().height(6.dp), color = c.accent, backgroundColor = c.track)
}

@Composable
private fun Pill(text: String, c: WidgetColors, primary: Boolean, description: String, onClick: androidx.glance.action.Action, modifier: GlanceModifier = GlanceModifier) {
    Box(modifier.height(36.dp).cornerRadius(18.dp).background(if (primary) c.accent else c.surface).clickable(onClick)
        .semantics { contentDescription = description }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, maxLines = 1, style = TextStyle(color = if (primary) c.onAccent else c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun Empty(context: Context, c: WidgetColors, compact: Boolean) {
    Column(GlanceModifier.fillMaxSize().padding(14.dp).clickable(actionStartActivity(Widgets.open(context, MainActivity.OPEN_LIBRARY))),
        verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Nessun libro in lettura", style = TextStyle(color = c.ink, fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.height(6.dp))
        Text("Apri la libreria ›", style = TextStyle(color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium))
    }
}

/** Every widget: loads per-widget settings, refreshes when [Widgets.VERSION] grows, draws [content] on the theme background. */
abstract class ReadingWidget(private val withGoals: Boolean) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        Widgets.store(context)   // loads the library off the main thread before the first draw
        provideContent {
            val state = currentState<Preferences>()
            state[Widgets.VERSION]  // read so that a refresh redraws
            val size = LocalSize.current
            val shown = Widgets.shown(context, state, withGoals, coverPx(context, size))
            val c = colors(context, state)
            GlanceTheme {
                Box(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp).background(c.background)) {
                    if (shown.book == null) Empty(context, c, size.width < 200.dp) else content(context, shown, c, size, appWidgetId)
                }
            }
        }
    }

    /** Covers decoded at the height they are drawn, never bigger. */
    private fun coverPx(context: Context, size: DpSize) = (size.height.value * context.resources.displayMetrics.density).toInt().coerceIn(120, 480)

    @Composable abstract fun content(context: Context, shown: Widgets.Shown, c: WidgetColors, size: DpSize, appWidgetId: Int)
}

/** 2×2 "Lettura attuale": the cover first, title, percentage and bar; a tap opens the book. */
class SmallWidget : ReadingWidget(false) {
    @Composable override fun content(context: Context, shown: Widgets.Shown, c: WidgetColors, size: DpSize, appWidgetId: Int) {
        val b = shown.book!!
        val open = actionStartActivity(Widgets.open(context, MainActivity.OPEN_BOOK, b.id))
        Column(GlanceModifier.fillMaxSize().padding(10.dp).clickable(open)) {
            CoverBox(shown, c, GlanceModifier.fillMaxWidth().defaultWeight())
            Spacer(GlanceModifier.height(6.dp))
            Text(b.title, maxLines = 1, style = TextStyle(color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            Progress(shown, c, false)
        }
    }
}

/** 4×2 "Continua a leggere": cover on the left; title, author, progress and the quick buttons on the right. */
class MediumWidget : ReadingWidget(false) {
    @Composable override fun content(context: Context, shown: Widgets.Shown, c: WidgetColors, size: DpSize, appWidgetId: Int) {
        val b = shown.book!!
        val open = actionStartActivity(Widgets.open(context, MainActivity.OPEN_BOOK, b.id))
        Row(GlanceModifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverBox(shown, c, GlanceModifier.fillMaxHeight().width((size.height.value * 0.62f).coerceIn(56f, 120f).dp).clickable(open))
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight().clickable(open)) {
                        Text(b.title, maxLines = 2, style = TextStyle(color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                        if (size.height >= 120.dp) Text(b.author, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 12.sp))
                    }
                    ChangeBook(context, c, appWidgetId)
                }
                Spacer(GlanceModifier.height(6.dp))
                Progress(shown, c, size.width >= 280.dp)
                Spacer(GlanceModifier.height(8.dp))
                QuickButtons(context, shown, c, size.width >= 300.dp)
            }
        }
    }
}

@Composable
private fun ChangeBook(context: Context, c: WidgetColors, appWidgetId: Int) {
    Box(GlanceModifier.size(32.dp).cornerRadius(16.dp).clickable(actionStartActivity(configure(context, appWidgetId)))
        .semantics { contentDescription = "Scegli il libro e i colori del widget" }, contentAlignment = Alignment.Center) {
        Image(ImageProvider(R.drawable.ic_tune), "Impostazioni del widget", GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(c.muted))
    }
}

@Composable
private fun QuickButtons(context: Context, shown: Widgets.Shown, c: WidgetColors, withOpen: Boolean) {
    val b = shown.book!!
    Row(GlanceModifier.fillMaxWidth()) {
        if (withOpen) { Pill("Apri", c, false, "Apri ${b.title}", actionStartActivity(Widgets.open(context, MainActivity.OPEN_BOOK, b.id))); Spacer(GlanceModifier.width(6.dp)) }
        Pill("+${shown.pageStep} pag.", c, true, "Aggiungi ${shown.pageStep} pagine",
            actionRunCallback<AddPagesAction>(actionParametersOf(BOOK_ID to b.id, STEP to shown.pageStep)))
        Spacer(GlanceModifier.width(6.dp))
        Pill("+${shown.percentStep}%", c, true, "Aggiungi ${shown.percentStep} per cento",
            actionRunCallback<AddPercentAction>(actionParametersOf(BOOK_ID to b.id, STEP to shown.percentStep)))
    }
}

/** 4×4 dashboard: the book with its buttons, the goals switched on in the app, and Statistics. */
class LargeWidget : ReadingWidget(true) {
    @Composable override fun content(context: Context, shown: Widgets.Shown, c: WidgetColors, size: DpSize, appWidgetId: Int) {
        val b = shown.book!!
        val open = actionStartActivity(Widgets.open(context, MainActivity.OPEN_BOOK, b.id))
        val stats = actionStartActivity(Widgets.open(context, MainActivity.OPEN_STATS))
        // Less room than a real 4×4: the goals shrink to the two most useful, then the book row gets shorter.
        val roomy = size.height >= 330.dp
        Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
            Row(GlanceModifier.fillMaxWidth().height(if (size.height >= 260.dp) 132.dp else 96.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverBox(shown, c, GlanceModifier.fillMaxHeight().width(if (size.height >= 260.dp) 86.dp else 62.dp).clickable(open))
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(GlanceModifier.defaultWeight().clickable(open)) {
                            Text(b.title, maxLines = 2, style = TextStyle(color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                            Text(b.author, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 12.sp))
                        }
                        ChangeBook(context, c, appWidgetId)
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    Progress(shown, c, size.width >= 280.dp)
                }
            }
            Spacer(GlanceModifier.height(10.dp))
            val goals = if (roomy) shown.goals else shown.goals.take(2)
            if (goals.isEmpty()) Text("Attiva gli obiettivi di lettura nelle impostazioni dell’app.", maxLines = 2, style = TextStyle(color = c.muted, fontSize = 12.sp))
            Column(GlanceModifier.fillMaxWidth().clickable(stats)) {
                goals.forEach { g ->
                    Row(GlanceModifier.fillMaxWidth()) {
                        Text(g.label, GlanceModifier.defaultWeight(), maxLines = 1, style = TextStyle(color = c.ink, fontSize = 13.sp))
                        Text("${g.done} / ${g.goal} ${g.unit}" + if (g.unit == "libri") " · ${(g.fraction * 100).toInt()}%" else "",
                            maxLines = 1, style = TextStyle(color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                    }
                    Spacer(GlanceModifier.height(3.dp))
                    LinearProgressIndicator(g.fraction, GlanceModifier.fillMaxWidth().height(5.dp), color = c.accent, backgroundColor = c.track)
                    Spacer(GlanceModifier.height(8.dp))
                }
            }
            // The goals take their natural height (the last bar was clipped when they shared the free space).
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth()) {
                Pill("+${shown.pageStep} pag.", c, true, "Aggiungi ${shown.pageStep} pagine",
                    actionRunCallback<AddPagesAction>(actionParametersOf(BOOK_ID to b.id, STEP to shown.pageStep)))
                Spacer(GlanceModifier.width(6.dp))
                Pill("Aggiorna", c, false, "Aggiorna l’avanzamento di ${b.title}", actionStartActivity(Widgets.open(context, MainActivity.OPEN_PROGRESS, b.id)))
                Spacer(GlanceModifier.width(6.dp))
                Pill("Statistiche", c, false, "Apri le statistiche", stats)
            }
        }
    }
}

class SmallWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SmallWidget() }
class MediumWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MediumWidget() }
class LargeWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = LargeWidget() }
