package app.atlante.library.ui.compose

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
 * (Material 3): books in the year, books in the month, pages and minutes a day. Each has its switch and, under the
 * wheel, how far you are.
 */
@Composable
fun GoalsSection(goals: ReadingGoals, books: List<Book>) {
    var version by remember { mutableIntStateOf(0) }      // recomposes after every change
    val today = LocalDate.now(); val month = YearMonth.now()
    key(version) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val year = goals.year(books, today)
            WheelGoal("Libri all’anno", "La tua rotta ${today.year}", goals.yearOn, { goals.yearOn = it; version++ }, goals.yearBooks, 1..200, "libri",
                { goals.yearBooks = it }, year, "completati quest’anno",
                if (goals.yearOn && year.done < goals.yearBooks) "Mancano ${ChronoUnit.DAYS.between(today, LocalDate.of(today.year, 12, 31))} giorni e ${goals.yearBooks - year.done} " +
                    (if (goals.yearBooks - year.done == 1L) "libro" else "libri") + "." else null)
            WheelGoal("Libri al mese", "Obiettivo di ${monthName(month)}", goals.monthOn, { goals.monthOn = it; version++ }, goals.monthBooks, 1..30, "libri",
                { goals.monthBooks = it }, goals.month(books, month), "completati questo mese", null)
            WheelGoal("Pagine al giorno", "Obiettivo di oggi", goals.dailyPagesOn, { goals.dailyPagesOn = it; version++ }, goals.dailyPages, 1..500, "pagine",
                { goals.dailyPages = it }, goals.pagesToday(books, today), "lette oggi", null)
            WheelGoal("Minuti al giorno", "Tempo di lettura", goals.dailyMinutesOn, { goals.dailyMinutesOn = it; version++ }, goals.dailyMinutes, 1..300, "min",
                { goals.dailyMinutes = it }, goals.minutesToday(books, today), "letti oggi", null)
            Text("Compaiono in Home e in Statistiche. I progressi usano le sessioni registrate e le date in cui hai finito i libri.",
                Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One goal: name and switch, the wheel to choose the number, and the progress under it. */
@Composable
private fun WheelGoal(title: String, kicker: String, on: Boolean, onSwitch: (Boolean) -> Unit, value: Int, range: IntRange, unit: String,
                      onValue: (Int) -> Unit, progress: ReadingGoals.Progress, doneLabel: String, note: String?) {
    val scheme = MaterialTheme.colorScheme
    OutlinedCard(Modifier.fillMaxWidth().animateContentSize(), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = if (on) scheme.surfaceContainerHigh else scheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) scheme.primary.copy(alpha = 0.35f) else scheme.outlineVariant)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.atlante.library.ui.L.t(kicker).uppercase(app.atlante.library.ui.L.locale()), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    Text(app.atlante.library.ui.L.t(title), style = MaterialTheme.typography.titleMedium)
                }
                Switch(on, onSwitch, Modifier.semantics { contentDescription = "$title: " + if (on) "attivo" else "spento" })
            }
            AnimatedVisibility(on) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    var current by remember(value) { mutableIntStateOf(value.coerceIn(range)) }
                    HorizontalWheel(current, range, true, title, { "$it" }) { v -> current = v; onValue(v) }
                    Text("$current $unit", Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                    Spacer(Modifier.height(10.dp))
                    val percent = (progress.done * 100 / current.coerceAtLeast(1)).toInt(); val reached = progress.done >= current
                    LinearProgressIndicator(progress = { (percent / 100f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(6.dp),
                        color = scheme.primary, trackColor = scheme.primaryContainer, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
                    Text("${progress.done} di $current $doneLabel · ${percent.coerceAtMost(100)}%" + if (reached) " · raggiunto ✓" else "",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (reached) scheme.primary else scheme.onSurfaceVariant)
                    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}
