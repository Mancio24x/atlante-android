package app.atlante.library.ui.compose

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
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

/**
 * "Obiettivi di lettura" in the settings: no extra taps, the goals open straight on their horizontal wheels
 * (Material 3): books in the year, books in the month, pages and minutes a day. Each card follows the theme like
 * the Rotta of Statistiche: a ring with how far you are (warm accent once reached), the name, its switch and, open,
 * the wheel. Turning a switch animates only its card (the whole section used to be rebuilt, the wheels jumped).
 */
@Composable
fun GoalsSection(goals: ReadingGoals, books: List<Book>) {
    val today = remember { LocalDate.now() }; val month = remember { YearMonth.now() }
    val year = remember(books) { goals.year(books, today) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WheelGoal("Libri all’anno", "La tua rotta ${today.year}", goals.yearOn, { goals.yearOn = it }, goals.yearBooks, 1..200, "libri",
            { goals.yearBooks = it }, year, "completati quest’anno") { target ->
            if (year.done < target) "Mancano ${ChronoUnit.DAYS.between(today, LocalDate.of(today.year, 12, 31))} giorni e ${target - year.done} " +
                (if (target - year.done == 1L) "libro" else "libri") + "." else null }
        WheelGoal("Libri al mese", "Obiettivo di ${monthName(month)}", goals.monthOn, { goals.monthOn = it }, goals.monthBooks, 1..30, "libri",
            { goals.monthBooks = it }, remember(books) { goals.month(books, month) }, "completati questo mese")
        WheelGoal("Pagine al giorno", "Obiettivo di oggi", goals.dailyPagesOn, { goals.dailyPagesOn = it }, goals.dailyPages, 1..500, "pagine",
            { goals.dailyPages = it }, remember(books) { goals.pagesToday(books, today) }, "lette oggi")
        WheelGoal("Minuti al giorno", "Tempo di lettura", goals.dailyMinutesOn, { goals.dailyMinutesOn = it }, goals.dailyMinutes, 1..300, "min",
            { goals.dailyMinutes = it }, remember(books) { goals.minutesToday(books, today) }, "letti oggi")
        Text("Compaiono in Home e in Statistiche. I progressi usano le sessioni registrate e le date in cui hai finito i libri.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One goal: ring, name and switch; open, the wheel to choose the number with its unit and how far you are. */
@Composable
private fun WheelGoal(title: String, kicker: String, initialOn: Boolean, onSwitch: (Boolean) -> Unit, value: Int, range: IntRange, unit: String,
                      onValue: (Int) -> Unit, progress: ReadingGoals.Progress, doneLabel: String, note: (Int) -> String? = { null }) {
    val scheme = MaterialTheme.colorScheme
    var on by remember { mutableStateOf(initialOn) }
    var current by remember { mutableIntStateOf(value.coerceIn(range)) }
    val reached = progress.done >= current
    val share = (progress.done.toFloat() / current.coerceAtLeast(1)).coerceIn(0f, 1f)
    val percent = (progress.done * 100 / current.coerceAtLeast(1)).toInt().coerceAtMost(100)
    val ring by animateFloatAsState(if (on) share else 0f, tween(500), label = "ring")
    val accent = if (reached) scheme.tertiary else scheme.primary
    OutlinedCard(Modifier.fillMaxWidth().animateContentSize(tween(220)), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = scheme.surfaceContainer),
        border = BorderStroke(1.dp, if (on) lerp(scheme.outlineVariant, scheme.primary, .35f) else scheme.outlineVariant)) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth().toggleable(on, role = Role.Switch) { on = it; onSwitch(it) }
                .semantics(mergeDescendants = true) { contentDescription = "$title: " + (if (on) "attivo, ${progress.done} di $current $unit" else "spento") },
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { ring }, modifier = Modifier.fillMaxSize(), strokeWidth = 5.dp,
                        color = accent, trackColor = scheme.outlineVariant, strokeCap = StrokeCap.Round)
                    Text(if (on) (if (reached) "✓" else "$percent%") else "–", style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold, color = if (on) accent else scheme.onSurfaceVariant)
                }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(app.atlante.library.ui.L.t(kicker).uppercase(app.atlante.library.ui.L.locale()), style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold, color = scheme.primary, maxLines = 1)
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(if (on) "$current $unit" else "Spento", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Switch(on, null)
            }
            AnimatedVisibility(on, enter = expandVertically(tween(260)) + fadeIn(tween(260)), exit = shrinkVertically(tween(200)) + fadeOut(tween(160))) {
                Column(Modifier.padding(end = 4.dp)) {
                    Spacer(Modifier.height(10.dp))
                    val choose: (Int) -> Unit = { v -> val c = v.coerceIn(range); if (c != current) { current = c; onValue(c) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton({ choose(current - 1) }, enabled = current > range.first,
                            modifier = Modifier.semantics { contentDescription = "$title: uno in meno" }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                        Box(Modifier.weight(1f)) { HorizontalWheel(current, range, true, title, { "$it" }, choose) }
                        FilledTonalIconButton({ choose(current + 1) }, enabled = current < range.last,
                            modifier = Modifier.semantics { contentDescription = "$title: uno in più" }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("${progress.done} di $current $doneLabel · $percent%" + if (reached) " · raggiunto ✓" else "",
                        style = MaterialTheme.typography.bodySmall, color = if (reached) scheme.tertiary else scheme.onSurfaceVariant)
                    note(current)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                }
            }
        }
    }
}
