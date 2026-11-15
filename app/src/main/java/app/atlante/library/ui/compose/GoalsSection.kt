package app.atlante.library.ui.compose

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingGoals
import app.atlante.library.ui.ThemePalette
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object GoalsView {
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, prefs: SharedPreferences, books: List<Book>): View = ComposeView(activity).apply {
        setContent { AtlanteTheme(palette) { GoalsSection(ReadingGoals(prefs), books) } }
    }
}

private fun monthName(m: YearMonth) = m.month.getDisplayName(TextStyle.FULL, app.atlante.library.ui.L.locale())

/** One goal as the section shows it: where its switch and number are kept, and how far you are today. */
private class GoalSpec(val title: String, val kicker: String, val range: IntRange, val unit: String, val doneLabel: String,
                       val isOn: () -> Boolean, val setOn: (Boolean) -> Unit, val get: () -> Int, val set: (Int) -> Unit, val done: Long,
                       val yearEnd: LocalDate? = null)

/**
 * "Obiettivi di lettura" in the settings, drawn like the theme tiles: rounded cards on the surface colour, an accent
 * outline on the goals that are on, a ring with how far you are. The horizontal wheel is always there: moving it
 * changes the number and turns the goal on. Every value is Compose state, so a switch animates instead of rebuilding
 * the whole section (it used to: the cards jumped and the wheels lost their place).
 */
@Composable
fun GoalsSection(goals: ReadingGoals, books: List<Book>) {
    val today = remember { LocalDate.now() }; val month = remember { YearMonth.now() }
    val specs = remember(books) { listOf(
        GoalSpec("Libri all’anno", "La tua rotta ${today.year}", 1..200, "libri", "completati quest’anno",
            { goals.yearOn }, { goals.yearOn = it }, { goals.yearBooks }, { goals.yearBooks = it }, goals.year(books, today).done, LocalDate.of(today.year, 12, 31)),
        GoalSpec("Libri al mese", "Obiettivo di ${monthName(month)}", 1..30, "libri", "completati questo mese",
            { goals.monthOn }, { goals.monthOn = it }, { goals.monthBooks }, { goals.monthBooks = it }, goals.month(books, month).done),
        GoalSpec("Pagine al giorno", "Obiettivo di oggi", 1..500, "pagine", "lette oggi",
            { goals.dailyPagesOn }, { goals.dailyPagesOn = it }, { goals.dailyPages }, { goals.dailyPages = it }, goals.pagesToday(books, today).done),
        GoalSpec("Minuti al giorno", "Tempo di lettura", 1..300, "min", "letti oggi",
            { goals.dailyMinutesOn }, { goals.dailyMinutesOn = it }, { goals.dailyMinutes }, { goals.dailyMinutes = it }, goals.minutesToday(books, today).done)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Sposta la rotella per scegliere il numero: l’obiettivo si accende da solo. Tocca un numero per andarci.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        specs.forEach { WheelGoal(it, today) }
        Text("Compaiono in Home e in Statistiche. I progressi usano le sessioni registrate e le date in cui hai finito i libri.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One goal: ring, name and switch on top (like a theme tile), the wheel under it, and how far you are. */
@Composable
private fun WheelGoal(spec: GoalSpec, today: LocalDate) {
    val scheme = MaterialTheme.colorScheme
    var on by remember { mutableStateOf(spec.isOn()) }
    var current by remember { mutableIntStateOf(spec.get().coerceIn(spec.range)) }
    val percent = (spec.done * 100 / current.coerceAtLeast(1)).toInt(); val reached = spec.done >= current
    val share by animateFloatAsState((percent / 100f).coerceIn(0f, 1f), tween(450), label = "goal")
    val outline by animateColorAsState(if (on) scheme.primary else scheme.outlineVariant, label = "outline")
    val dim by animateFloatAsState(if (on) 1f else 0.55f, label = "dim")
    val shape = RoundedCornerShape(22.dp)
    Surface(Modifier.fillMaxWidth().animateContentSize(), shape = shape, color = scheme.surfaceContainer, border = BorderStroke(if (on) 1.5.dp else 1.dp, outline)) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).alpha(dim).semantics { contentDescription = "${spec.title}: $percent%" }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { share }, Modifier.fillMaxSize(), color = if (reached) scheme.tertiary else scheme.primary, strokeWidth = 5.dp,
                        trackColor = scheme.outlineVariant, strokeCap = StrokeCap.Round, gapSize = 0.dp)
                    Text(if (reached) "✓" else "${percent.coerceAtMost(100)}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = if (reached) scheme.tertiary else scheme.onSurface)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(app.atlante.library.ui.L.t(spec.kicker).uppercase(app.atlante.library.ui.L.locale()), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    Text(spec.title + if (on) "  ✓" else "", style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, fontFamily = FontFamily.Serif), color = scheme.onSurface)
                    Text("${spec.done} di $current ${spec.doneLabel}", style = MaterialTheme.typography.bodySmall, color = if (reached && on) scheme.tertiary else scheme.onSurfaceVariant)
                }
                Switch(on, { on = it; spec.setOn(it) }, Modifier.semantics { contentDescription = "${spec.title}: " + if (on) "attivo" else "spento" })
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.alpha(dim)) {
                HorizontalWheel(current, spec.range, true, spec.title, { "$it" }) { v ->
                    current = v; spec.set(v)
                    if (!on) { on = true; spec.setOn(true) }            // choosing a number means you want the goal
                }
            }
            Text("$current ${spec.unit}",
                Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            AnimatedVisibility(on, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.padding(top = 8.dp, end = 4.dp)) {
                    LinearProgressIndicator(progress = { share }, Modifier.fillMaxWidth().height(6.dp), color = if (reached) scheme.tertiary else scheme.primary,
                        trackColor = scheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {}, strokeCap = StrokeCap.Round)
                    Text(if (reached) "Raggiunto: ${spec.done} ${spec.doneLabel}." else if (spec.yearEnd != null) "$percent%" else "$percent% · ne mancano ${current - spec.done}",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (reached) scheme.tertiary else scheme.onSurfaceVariant)
                    val end = spec.yearEnd
                    if (end != null && !reached) Text("Mancano ${ChronoUnit.DAYS.between(today, end)} giorni e ${current - spec.done} " +
                        (if (current - spec.done == 1L) "libro" else "libri") + ".", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}
