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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
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

/**
 * "Obiettivi di lettura" in the settings, drawn like the theme tiles of Aspetto: a rounded card on the theme's
 * surface with a thin line, outlined in the accent while the goal is on. Each card has the progress ring, the
 * name and the switch; when on, the horizontal wheel to choose the number and how far you are. The switches keep
 * their state (no rebuild of the page), so the wheel opens and closes with its animation.
 */
@Composable
fun GoalsSection(goals: ReadingGoals, books: List<Book>) {
    val today = LocalDate.now(); val month = YearMonth.now()
    val year = remember(books) { goals.year(books, today) }
    val inMonth = remember(books) { goals.month(books, month) }
    val pages = remember(books) { goals.pagesToday(books, today) }
    val minutes = remember(books) { goals.minutesToday(books, today) }
    val daysLeft = ChronoUnit.DAYS.between(today, LocalDate.of(today.year, 12, 31))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Tocca un interruttore per attivare un obiettivo, poi scorri la rotella per scegliere il numero.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WheelGoal("Libri all’anno", "La tua rotta ${today.year}", goals.yearOn, { goals.yearOn = it }, goals.yearBooks, 1..200, "libri",
            { goals.yearBooks = it }, year, "completati quest’anno") { goal ->
                if (year.done < goal) "Mancano $daysLeft giorni e ${goal - year.done} " + (if (goal - year.done == 1L) "libro" else "libri") + "." else null }
        WheelGoal("Libri al mese", "Obiettivo di ${monthName(month)}", goals.monthOn, { goals.monthOn = it }, goals.monthBooks, 1..30, "libri",
            { goals.monthBooks = it }, inMonth, "completati questo mese") { null }
        WheelGoal("Pagine al giorno", "Obiettivo di oggi", goals.dailyPagesOn, { goals.dailyPagesOn = it }, goals.dailyPages, 1..500, "pagine",
            { goals.dailyPages = it }, pages, "lette oggi") { null }
        WheelGoal("Minuti al giorno", "Tempo di lettura", goals.dailyMinutesOn, { goals.dailyMinutesOn = it }, goals.dailyMinutes, 1..300, "min",
            { goals.dailyMinutes = it }, minutes, "letti oggi") { null }
        Text("Compaiono in Home e in Statistiche. I progressi usano le sessioni registrate e le date in cui hai finito i libri.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One goal: ring, name and switch; when on, the wheel to choose the number and the progress under it. */
@Composable
private fun WheelGoal(title: String, kicker: String, initiallyOn: Boolean, save: (Boolean) -> Unit, value: Int, range: IntRange, unit: String,
                      onValue: (Int) -> Unit, progress: ReadingGoals.Progress, doneLabel: String, note: (Int) -> String?) {
    val scheme = MaterialTheme.colorScheme
    var on by remember { mutableStateOf(initiallyOn) }
    var current by remember { mutableIntStateOf(value.coerceIn(range)) }
    val percent = (progress.done * 100 / current.coerceAtLeast(1)).toInt(); val reached = progress.done >= current
    val ring by animateFloatAsState(if (on) (percent / 100f).coerceIn(0f, 1f) else 0f, tween(450), label = "ring")
    val border by animateColorAsState(if (on) scheme.primary else scheme.outlineVariant, tween(250), label = "border")
    Surface(Modifier.fillMaxWidth().animateContentSize(tween(250)), shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, border)) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth().toggleable(on, role = Role.Switch) { on = it; save(it) }
                .semantics(mergeDescendants = true) { contentDescription = "$title: " + if (on) "attivo, $percent%" else "spento" },
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { ring }, Modifier.fillMaxSize(), color = if (reached && on) scheme.tertiary else scheme.primary,
                        strokeWidth = 4.dp, trackColor = scheme.outlineVariant, gapSize = 0.dp)
                    Text(if (on) (if (reached) "✓" else "${percent.coerceAtMost(100)}%") else "–", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold, color = if (on) scheme.onSurface else scheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(app.atlante.library.ui.L.t(kicker).uppercase(app.atlante.library.ui.L.locale()), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), color = scheme.onSurface)
                    if (on) Text("$current $unit", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Switch(on, null)
            }
            AnimatedVisibility(on, enter = expandVertically(tween(280)) + fadeIn(tween(280)), exit = shrinkVertically(tween(220)) + fadeOut(tween(180))) {
                Column(Modifier.padding(end = 4.dp)) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalWheel(current, range, true, title, { "$it" }) { v -> current = v; onValue(v) }
                    Text("${progress.done} di $current $doneLabel" + if (reached) " · raggiunto ✓" else "",
                        Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall, color = if (reached) scheme.primary else scheme.onSurfaceVariant)
                    note(current)?.let { Text(it, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                }
            }
        }
    }
}
