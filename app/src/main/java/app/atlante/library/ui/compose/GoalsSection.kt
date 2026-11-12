package app.atlante.library.ui.compose

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingGoals
import app.atlante.library.ui.L
import app.atlante.library.ui.ThemePalette
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

object GoalsView {
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, prefs: SharedPreferences, books: List<Book>): View = ComposeView(activity).apply {
        setContent { AtlanteTheme(palette) { GoalsSection(ReadingGoals(prefs), books, prefs.getBoolean("wheelHaptics", true)) } }
    }
}

private fun monthName(m: YearMonth) = m.month.getDisplayName(TextStyle.FULL, L.locale())

/**
 * "Obiettivi di lettura" in the settings, drawn like the theme tiles of Aspetto (rounded card on the theme surface,
 * serif name, accent outline when active): books in the year, books in the month, pages and minutes a day.
 * Each goal has its switch, a ring with how far you are, and — when active — a horizontal wheel with − / + to move
 * the number. State lives in each card, so a switch no longer rebuilds the page (the old `key(version)` reset the
 * wheels and skipped the open/close animation).
 */
@Composable
fun GoalsSection(goals: ReadingGoals, books: List<Book>, haptics: Boolean = true) {
    val today = remember { LocalDate.now() }; val month = remember { YearMonth.now() }
    val year = remember(books) { goals.year(books, today).done }
    val monthDone = remember(books) { goals.month(books, month).done }
    val pages = remember(books) { goals.pagesToday(books, today).done }
    val minutes = remember(books) { goals.minutesToday(books, today).done }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GoalCard("Libri all’anno", "La tua rotta ${today.year}", goals.yearOn, { goals.yearOn = it }, goals.yearBooks, 1..200, "libri",
            { goals.yearBooks = it }, year, "completati quest’anno", haptics) { goal ->
            val left = goal - year
            if (left > 0) "Mancano ${ChronoUnit.DAYS.between(today, LocalDate.of(today.year, 12, 31))} giorni e $left " + (if (left == 1L) "libro" else "libri") + "." else null
        }
        GoalCard("Libri al mese", "Obiettivo di ${monthName(month)}", goals.monthOn, { goals.monthOn = it }, goals.monthBooks, 1..30, "libri",
            { goals.monthBooks = it }, monthDone, "completati questo mese", haptics) { null }
        GoalCard("Pagine al giorno", "Obiettivo di oggi", goals.dailyPagesOn, { goals.dailyPagesOn = it }, goals.dailyPages, 1..500, "pagine",
            { goals.dailyPages = it }, pages, "lette oggi", haptics) { null }
        GoalCard("Minuti al giorno", "Tempo di lettura", goals.dailyMinutesOn, { goals.dailyMinutesOn = it }, goals.dailyMinutes, 1..300, "min",
            { goals.dailyMinutes = it }, minutes, "letti oggi", haptics) { null }
        Text("Compaiono in Home e in Statistiche. I progressi usano le sessioni registrate e le date in cui hai finito i libri.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One goal as a theme-like tile: ring, name and switch; when active the wheel to choose the number and the progress. */
@Composable
private fun GoalCard(title: String, kicker: String, initialOn: Boolean, save: (Boolean) -> Unit, initialValue: Int, range: IntRange, unit: String,
                     saveValue: (Int) -> Unit, done: Long, doneLabel: String, haptics: Boolean, note: (Long) -> String?) {
    val scheme = MaterialTheme.colorScheme
    var on by remember { mutableStateOf(initialOn) }
    var goal by remember { mutableIntStateOf(initialValue.coerceIn(range)) }
    val fraction = if (goal <= 0) 0f else (done.toFloat() / goal).coerceIn(0f, 1f)
    val percent = (done * 100 / goal.coerceAtLeast(1)).toInt().coerceAtMost(100); val reached = done >= goal
    val ring by animateFloatAsState(if (on) fraction else 0f, spring(stiffness = Spring.StiffnessLow), label = "ring")
    val border by animateColorAsState(if (on) scheme.primary else scheme.outlineVariant, label = "border")
    val setGoal: (Int) -> Unit = { v -> val c = v.coerceIn(range); if (c != goal) { goal = c; saveValue(c) } }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = scheme.surface, border = BorderStroke(if (on) 1.5.dp else 1.dp, border)) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).semantics { contentDescription = if (on) "$title: $percent%" else "$title: spento" }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { ring }, Modifier.fillMaxSize(), color = scheme.primary, trackColor = scheme.outlineVariant,
                        strokeWidth = 4.dp, gapSize = 0.dp)
                    Text(if (on) (if (reached) "✓" else "$percent%") else "–", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold, color = if (on) scheme.primary else scheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(L.t(kicker).uppercase(L.locale()), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    Text(title, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
                    Text(if (on) "$done di $goal $doneLabel" else "Spento", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Switch(on, { on = it; save(it) }, Modifier.semantics { contentDescription = "$title: " + if (on) "attivo" else "spento" })
            }
            AnimatedVisibility(on, enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton({ setGoal(goal - 1) }, enabled = goal > range.first,
                            modifier = Modifier.semantics { contentDescription = "$title: meno uno" }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                        Box(Modifier.weight(1f)) { HorizontalWheel(goal, range, haptics, title, { "$it" }, setGoal) }
                        FilledTonalIconButton({ setGoal(goal + 1) }, enabled = goal < range.last,
                            modifier = Modifier.semantics { contentDescription = "$title: più uno" }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    }
                    Text("$goal $unit", Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { ring }, Modifier.fillMaxWidth().height(6.dp),
                        trackColor = scheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                    Text("$done di $goal $doneLabel · $percent%" + if (reached) " · raggiunto ✓" else "",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (reached) scheme.primary else scheme.onSurfaceVariant)
                    note(goal.toLong())?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                }
            }
        }
    }
}
