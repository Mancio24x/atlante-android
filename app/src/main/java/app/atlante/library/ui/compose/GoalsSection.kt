package app.atlante.library.ui.compose

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
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
 * (Material 3): books in the year, books in the month, pages and minutes a day. Each is a tile drawn like the
 * theme tiles in Aspetto (rounded, thin outline, the accent outline when it is on), with its switch and, under the
 * wheel, how far you are. Each tile keeps its own state: a switch opens or closes only its wheel, with an animation,
 * instead of rebuilding the whole page (the wheels jumped back and the others flickered).
 */
@Composable
fun GoalsSection(goals: ReadingGoals, books: List<Book>) {
    val today = LocalDate.now(); val month = YearMonth.now()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val year = remember(books) { goals.year(books, today) }
        val daysLeft = ChronoUnit.DAYS.between(today, LocalDate.of(today.year, 12, 31))
        WheelGoal("Libri all’anno", "La tua rotta ${today.year}", goals.yearOn, { goals.yearOn = it }, goals.yearBooks, 1..200, "libri",
            { goals.yearBooks = it }, year.done, "completati quest’anno") { goal ->
            if (year.done < goal) "Mancano $daysLeft giorni e ${goal - year.done} " + (if (goal - year.done == 1L) "libro" else "libri") + "." else null }
        val monthDone = remember(books) { goals.month(books, month).done }
        WheelGoal("Libri al mese", "Obiettivo di ${monthName(month)}", goals.monthOn, { goals.monthOn = it }, goals.monthBooks, 1..30, "libri",
            { goals.monthBooks = it }, monthDone, "completati questo mese") { null }
        val pages = remember(books) { goals.pagesToday(books, today).done }
        WheelGoal("Pagine al giorno", "Obiettivo di oggi", goals.dailyPagesOn, { goals.dailyPagesOn = it }, goals.dailyPages, 1..500, "pagine",
            { goals.dailyPages = it }, pages, "lette oggi") { null }
        val minutes = remember(books) { goals.minutesToday(books, today).done }
        WheelGoal("Minuti al giorno", "Tempo di lettura", goals.dailyMinutesOn, { goals.dailyMinutesOn = it }, goals.dailyMinutes, 1..300, "min",
            { goals.dailyMinutes = it }, minutes, "letti oggi") { null }
        Text("Compaiono in Home e in Statistiche. I progressi usano le sessioni registrate e le date in cui hai finito i libri.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One goal: name and switch, the wheel to choose the number, and the progress under it. */
@Composable
private fun WheelGoal(title: String, kicker: String, initiallyOn: Boolean, onSwitch: (Boolean) -> Unit, value: Int, wheel: IntRange, unit: String,
                      onValue: (Int) -> Unit, done: Long, doneLabel: String, note: (Int) -> String?) {
    val scheme = MaterialTheme.colorScheme
    var on by remember { mutableStateOf(initiallyOn) }
    // A goal saved above the wheel's end (older versions allowed more) stays as it is: the wheel grows to show it.
    val range = remember(wheel) { wheel.first..maxOf(wheel.last, value) }
    var current by remember { mutableIntStateOf(value.coerceIn(range)) }
    val outline by androidx.compose.animation.animateColorAsState(if (on) scheme.primary else scheme.outlineVariant, label = "outline")
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainer, border = BorderStroke(if (on) 1.5.dp else 1.dp, outline)) {
        Column(Modifier.animateContentSize().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Switch) { on = it; onSwitch(it) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.atlante.library.ui.L.t(kicker).uppercase(app.atlante.library.ui.L.locale()), style = MaterialTheme.typography.labelSmall,
                        color = if (on) scheme.primary else scheme.onSurfaceVariant, letterSpacing = 1.sp)
                    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif, fontSize = 18.sp), color = scheme.onSurface)
                    if (!on) Text("Spento", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    else Text("$current $unit", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Switch(on, null, Modifier.semantics { contentDescription = "$title: " + if (on) "attivo" else "spento" })
            }
            AnimatedVisibility(on, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    HorizontalWheel(current, range, true, title, { "$it" }) { v -> current = v; onValue(v) }
                    Spacer(Modifier.height(10.dp))
                    val fraction = (done.toFloat() / current.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val percent = (done * 100 / current.coerceAtLeast(1)).toInt(); val reached = done >= current
                    val shown by animateFloatAsState(fraction, label = "progress")
                    LinearProgressIndicator(progress = { shown }, Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = scheme.primary, trackColor = scheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$done di $current $doneLabel", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        Text(if (reached) "Raggiunto ✓" else "${percent.coerceAtMost(100)}%", style = MaterialTheme.typography.labelLarge,
                            color = if (reached) scheme.primary else scheme.onSurface)
                    }
                    note(current)?.let { Text(it, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                }
            }
        }
    }
}
