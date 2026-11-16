package app.atlante.library.ui.compose

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import kotlinx.coroutines.delay
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

private fun monthName(m: YearMonth) = m.month.getDisplayName(TextStyle.FULL, L.locale())
private val Tile = RoundedCornerShape(22.dp)

/**
 * "Obiettivi di lettura" in the settings, drawn like the theme tiles of Aspetto (serif name, accent border when
 * on, the theme's surface): books in the year, books in the month, pages and minutes a day. Each tile has its
 * ring with the progress, its switch and, when on, the horizontal wheel to choose the number.
 * The goal is saved when the wheel stops (not at every number it passes) and when the page closes.
 */
@Composable
fun GoalsSection(goals: ReadingGoals, books: List<Book>) {
    val today = remember { LocalDate.now() }; val month = remember { YearMonth.now() }
    val year = remember(books) { goals.year(books, today).done }
    val monthDone = remember(books) { goals.month(books, month).done }
    val pages = remember(books) { goals.pagesToday(books, today).done }
    val minutes = remember(books) { goals.minutesToday(books, today).done }
    val daysLeft = ChronoUnit.DAYS.between(today, LocalDate.of(today.year, 12, 31))
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WheelGoal("Libri all’anno", "La tua rotta ${today.year}", goals.yearOn, { goals.yearOn = it }, goals.yearBooks, 1..200, "libri",
            { goals.yearBooks = it }, year, "completati quest’anno") { goal ->
            if (year < goal) "Mancano $daysLeft giorni e ${goal - year} " + (if (goal - year == 1L) "libro" else "libri") + "." else null }
        WheelGoal("Libri al mese", "Obiettivo di ${monthName(month)}", goals.monthOn, { goals.monthOn = it }, goals.monthBooks, 1..30, "libri",
            { goals.monthBooks = it }, monthDone, "completati questo mese") { null }
        WheelGoal("Pagine al giorno", "Obiettivo di oggi", goals.dailyPagesOn, { goals.dailyPagesOn = it }, goals.dailyPages, 1..500, "pagine",
            { goals.dailyPages = it }, pages, "lette oggi") { null }
        WheelGoal("Minuti al giorno", "Tempo di lettura", goals.dailyMinutesOn, { goals.dailyMinutesOn = it }, goals.dailyMinutes, 1..300, "min",
            { goals.dailyMinutes = it }, minutes, "letti oggi") { null }
        Text("Scorri la rotella o tocca un numero. Gli obiettivi compaiono in Home e in Statistiche; i progressi usano le sessioni registrate e le date in cui hai finito i libri.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One goal as a theme tile: ring, kicker and serif name, switch; when on, the wheel and how far you are. */
@Composable
private fun WheelGoal(title: String, kicker: String, startOn: Boolean, onSwitch: (Boolean) -> Unit, value: Int, range: IntRange, unit: String,
                      save: (Int) -> Unit, done: Long, doneLabel: String, note: (Int) -> String?) {
    val scheme = MaterialTheme.colorScheme
    var on by remember { mutableStateOf(startOn) }
    var current by remember { mutableIntStateOf(value.coerceIn(range)) }
    var saved by remember { mutableIntStateOf(current) }
    val saveNow by rememberUpdatedState(save)
    // Saved when the wheel rests a moment, and in any case when the page goes away.
    LaunchedEffect(current) { delay(350); if (current != saved) { saveNow(current); saved = current } }
    DisposableEffect(Unit) { onDispose { if (current != saved) saveNow(current) } }
    val progress = ReadingGoals.Progress(done, current)
    val border by animateColorAsState(if (on) scheme.primary else scheme.outlineVariant, label = "border")
    OutlinedCard(Modifier.fillMaxWidth(), shape = Tile, border = BorderStroke(if (on) 1.5.dp else 1.dp, border),
        colors = CardDefaults.outlinedCardColors(containerColor = scheme.surface)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoalRing(progress, on, "$title: ${progress.done} di $current $unit")
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(L.t(kicker).uppercase(L.locale()), style = MaterialTheme.typography.labelSmall, color = if (on) scheme.primary else scheme.onSurfaceVariant)
                    Text(title, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
                    Text(if (on) "$current $unit" else "Spento", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Switch(on, { on = it; onSwitch(it) }, Modifier.semantics { contentDescription = "$title: " + if (on) "attivo" else "spento" })
            }
            AnimatedVisibility(on, enter = expandVertically(spring(stiffness = 500f)) + fadeIn(), exit = shrinkVertically(spring(stiffness = 500f)) + fadeOut()) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalWheel(current, range, true, title, { "$it" }) { v -> current = v.coerceIn(range) }
                    Text("$current $unit", Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    val bar by animateFloatAsState(progress.fraction, label = "bar")
                    LinearProgressIndicator(progress = { bar }, Modifier.fillMaxWidth().height(6.dp),
                        trackColor = scheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                    Text("${progress.done} di $current $doneLabel · ${progress.percent}%" + if (progress.reached) " · raggiunto ✓" else "",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (progress.reached) scheme.primary else scheme.onSurfaceVariant)
                    note(current)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/** Small ring with the percentage, in the accent when on and in the line colour when off. */
@Composable
private fun GoalRing(progress: ReadingGoals.Progress, on: Boolean, description: String) {
    val scheme = MaterialTheme.colorScheme
    val share by animateFloatAsState(if (on) progress.fraction else 0f, label = "ring")
    Box(Modifier.size(52.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { share }, modifier = Modifier.fillMaxSize(), strokeWidth = 5.dp,
            color = if (on) scheme.primary else scheme.outlineVariant, trackColor = scheme.outlineVariant, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
        Text(if (on) "${progress.percent}%" else "–", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = if (on) scheme.onSurface else scheme.onSurfaceVariant)
    }
}
