package app.atlante.library.ui.compose

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * "Obiettivi di lettura" in the settings: no extra taps, the goals open straight on their horizontal wheels
 * (Material 3): books in the year, books in the month, pages and minutes a day. Each is a tile like the theme tiles
 * of Aspetto (same corners, surface and line; the accent line when the goal is on), with its switch and, under the
 * wheel, how far you are.
 */
@Composable
fun GoalsSection(goals: ReadingGoals, books: List<Book>) {
    val today = LocalDate.now(); val month = YearMonth.now()
    // Progress is read once: turning a wheel changes the goal, not what you have read.
    val year = remember { goals.year(books, today) }; val monthDone = remember { goals.month(books, month) }
    val pages = remember { goals.pagesToday(books, today) }; val minutes = remember { goals.minutesToday(books, today) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WheelGoal("Libri all’anno", "La tua rotta ${today.year}", goals.yearOn, { goals.yearOn = it }, goals.yearBooks, 200, "libri",
            { goals.yearBooks = it }, year.done, "completati quest’anno") { goal ->
            val left = goal - year.done
            if (left > 0) "Mancano ${ChronoUnit.DAYS.between(today, LocalDate.of(today.year, 12, 31))} giorni e $left " + (if (left == 1L) "libro" else "libri") + "." else null
        }
        WheelGoal("Libri al mese", "Obiettivo di ${monthName(month)}", goals.monthOn, { goals.monthOn = it }, goals.monthBooks, 30, "libri",
            { goals.monthBooks = it }, monthDone.done, "completati questo mese") { null }
        WheelGoal("Pagine al giorno", "Obiettivo di oggi", goals.dailyPagesOn, { goals.dailyPagesOn = it }, goals.dailyPages, 500, "pagine",
            { goals.dailyPages = it }, pages.done, "lette oggi") { null }
        WheelGoal("Minuti al giorno", "Tempo di lettura", goals.dailyMinutesOn, { goals.dailyMinutesOn = it }, goals.dailyMinutes, 300, "min",
            { goals.dailyMinutes = it }, minutes.done, "letti oggi") { null }
        Text("Compaiono in Home e in Statistiche. I progressi usano le sessioni registrate e le date in cui hai finito i libri.",
            Modifier.padding(horizontal = 4.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One goal: name and switch, the wheel to choose the number, and the progress under it. The tile keeps its own
 * state, so a switch opens and closes its wheel with an animation and the other wheels stay where they are
 * (the whole page used to be rebuilt at every switch: nothing animated and the page jumped).
 */
@Composable
private fun WheelGoal(title: String, kicker: String, startOn: Boolean, onSwitch: (Boolean) -> Unit, startValue: Int, limit: Int, unit: String,
                      onValue: (Int) -> Unit, done: Long, doneLabel: String, note: (Int) -> String?) {
    val scheme = MaterialTheme.colorScheme
    // A goal saved above the wheel's usual end (an older version, a backup) stays on the wheel instead of being shown as the end.
    val range = remember { 1..maxOf(limit, startValue) }
    var on by remember { mutableStateOf(startOn) }
    var current by remember { mutableIntStateOf(startValue.coerceIn(range)) }
    val line by animateColorAsState(if (on) scheme.primary else scheme.outlineVariant, label = "line")
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainer, border = BorderStroke(1.dp, line)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).animateContentSize()) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp), color = scheme.onSurface)
                    Text(if (on) "$kicker · $current $unit" else kicker, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Switch(on, { on = it; onSwitch(it) }, Modifier.semantics { contentDescription = "$title: " + if (on) "attivo" else "spento" })
            }
            AnimatedVisibility(on, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    HorizontalWheel(current, range, true, title, { "$it" }) { v -> current = v; onValue(v) }
                    Spacer(Modifier.height(10.dp))
                    val reached = done >= current
                    val fraction by animateFloatAsState((done.toFloat() / current.coerceAtLeast(1)).coerceIn(0f, 1f), label = "progress")
                    LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().height(6.dp),
                        trackColor = scheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                    Text("$done di $current $doneLabel · ${(done * 100 / current.coerceAtLeast(1)).coerceAtMost(100)}%" + if (reached) " · raggiunto ✓" else "",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (reached) scheme.primary else scheme.onSurfaceVariant)
                    note(current)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                }
            }
        }
    }
}
