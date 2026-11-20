package app.atlante.library.ui.compose

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingGoals
import app.atlante.library.ui.ThemePalette
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

object GoalsView {
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, prefs: SharedPreferences, books: List<Book>): View = ComposeView(activity).apply {
        setContent { AtlanteTheme(palette) { GoalsSection(ReadingGoals(prefs), books) } }
    }
}

private fun monthName(m: YearMonth) = m.month.getDisplayName(TextStyle.FULL, app.atlante.library.ui.L.locale())

/** One goal on the page: what it measures, its switch and number (saved at once), and what is already done. */
private class Goal(val title: String, val kicker: String, val short: String, on: Boolean, value: Int, val range: IntRange, val unit: String,
                   val done: Long, val doneLabel: String, private val saveOn: (Boolean) -> Unit, private val saveValue: (Int) -> Unit,
                   val note: (Int) -> String? = { null }) {
    var on by mutableStateOf(on); private set
    var value by mutableIntStateOf(value.coerceIn(range)); private set
    fun switch(v: Boolean) { on = v; saveOn(v) }
    fun choose(v: Int) { val c = v.coerceIn(range); if (c != value) { value = c; saveValue(c) } }
    val fraction: Float get() = (done.toFloat() / value.coerceAtLeast(1)).coerceIn(0f, 1f)
    val percent: Int get() = (done * 100 / value.coerceAtLeast(1)).toInt().coerceAtMost(100)
    val reached: Boolean get() = done >= value
}

/**
 * "Obiettivi di lettura" in the settings, in the colors of the chosen theme: on top "La tua rotta" with a ring for
 * every goal, then one card per goal with its switch, the horizontal wheel for the number and how far you are.
 * Every change is kept in its own state (no rebuilding the page), so switches and wheels animate.
 */
@Composable
fun GoalsSection(goals: ReadingGoals, books: List<Book>) {
    val today = remember { LocalDate.now() }; val month = remember { YearMonth.now() }
    val list = remember(goals, books) {
        val year = goals.year(books, today).done
        listOf(
            Goal("Libri all’anno", "La tua rotta ${today.year}", "Anno", goals.yearOn, goals.yearBooks, 1..200, "libri", year, "completati quest’anno",
                { goals.yearOn = it }, { goals.yearBooks = it }) { goal ->
                if (year < goal) "Mancano ${ChronoUnit.DAYS.between(today, LocalDate.of(today.year, 12, 31))} giorni e ${goal - year} " +
                    (if (goal - year == 1L) "libro" else "libri") + "." else null },
            Goal("Libri al mese", "Obiettivo di ${monthName(month)}", "Mese", goals.monthOn, goals.monthBooks, 1..30, "libri",
                goals.month(books, month).done, "completati questo mese", { goals.monthOn = it }, { goals.monthBooks = it }),
            Goal("Pagine al giorno", "Obiettivo di oggi", "Pagine", goals.dailyPagesOn, goals.dailyPages, 1..500, "pagine",
                goals.pagesToday(books, today).done, "lette oggi", { goals.dailyPagesOn = it }, { goals.dailyPages = it }),
            Goal("Minuti al giorno", "Tempo di lettura", "Minuti", goals.dailyMinutesOn, goals.dailyMinutes, 1..300, "min",
                goals.minutesToday(books, today).done, "letti oggi", { goals.dailyMinutesOn = it }, { goals.dailyMinutes = it }),
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RouteCard(list)
        list.forEach { WheelGoal(it) }
        Text("Compaiono in Home e in Statistiche. I progressi usano le sessioni registrate e le date in cui hai finito i libri.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val Round20 = RoundedCornerShape(20.dp)

/** "La tua rotta": a ring for every goal, in the theme accent (copper once reached); switched-off goals are faint. */
@Composable
private fun RouteCard(list: List<Goal>) {
    val scheme = MaterialTheme.colorScheme
    val active = list.count { it.on }; val reached = list.count { it.on && it.reached }
    OutlinedCard(Modifier.fillMaxWidth(), shape = Round20, colors = CardDefaults.outlinedCardColors(containerColor = scheme.surfaceContainer),
        border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(app.atlante.library.ui.L.t("La tua rotta").uppercase(app.atlante.library.ui.L.locale()), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            Text(when { active == 0 -> "Nessun obiettivo attivo"; reached == active -> "Tutti raggiunti ✓"; else -> "$reached di $active raggiunti" },
                style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                list.forEach { g -> Ring(g, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Ring(g: Goal, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fraction by animateFloatAsState(if (g.on) g.fraction else 0f, spring(stiffness = Spring.StiffnessLow), label = "ring")
    val color by animateColorAsState(if (g.reached) scheme.tertiary else scheme.primary, label = "ringColor")
    val faint by animateFloatAsState(if (g.on) 1f else 0.4f, label = "ringAlpha")
    Column(modifier.alpha(faint).semantics(mergeDescendants = true) {
        contentDescription = "${g.title}: " + if (g.on) "${g.done} di ${g.value}, ${g.percent}%" else "spento" },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { fraction }, Modifier.fillMaxSize(), color = color, trackColor = scheme.outlineVariant,
                strokeWidth = 5.dp, gapSize = 0.dp, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
            Text(if (g.on) "${g.percent}%" else "–", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(g.short, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

/** One goal: name and switch, the wheel to choose the number, and the progress under it. */
@Composable
private fun WheelGoal(g: Goal) {
    val scheme = MaterialTheme.colorScheme
    val border by animateColorAsState(if (g.on) scheme.primary.copy(alpha = 0.55f) else scheme.outlineVariant, label = "goalBorder")
    OutlinedCard(Modifier.fillMaxWidth(), shape = Round20, colors = CardDefaults.outlinedCardColors(containerColor = scheme.surfaceContainer),
        border = BorderStroke(1.dp, border)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.atlante.library.ui.L.t(g.kicker).uppercase(app.atlante.library.ui.L.locale()), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    Text(g.title, style = MaterialTheme.typography.titleMedium)
                    if (!g.on) Text("Spento", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Switch(g.on, { g.switch(it) }, Modifier.semantics { contentDescription = "${g.title}: " + if (g.on) "attivo" else "spento" })
            }
            AnimatedVisibility(g.on, enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalWheel(g.value, g.range, true, g.title, { "$it" }) { v -> g.choose(v) }
                    Text("${g.value} ${g.unit}", Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    val fraction by animateFloatAsState(g.fraction, spring(stiffness = Spring.StiffnessLow), label = "goalBar")
                    val bar by animateColorAsState(if (g.reached) scheme.tertiary else scheme.primary, label = "goalBarColor")
                    LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().height(6.dp), color = bar,
                        trackColor = scheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {}, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                    Text("${g.done} di ${g.value} ${g.doneLabel} · ${g.percent}%" + if (g.reached) " · raggiunto ✓" else "",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (g.reached) scheme.tertiary else scheme.onSurfaceVariant)
                    g.note(g.value)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                }
            }
        }
    }
}
