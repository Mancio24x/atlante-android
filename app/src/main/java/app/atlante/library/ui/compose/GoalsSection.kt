package app.atlante.library.ui.compose

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.atlante.library.R
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

/** Wheel range: the usual span, widened to a goal saved earlier with a larger number (never shown cut down). */
internal fun goalRange(usual: IntRange, saved: Int) = usual.first..maxOf(usual.last, saved)

/**
 * "Obiettivi di lettura" in the settings: no extra taps, the goals open straight on their horizontal wheels
 * (Material 3): books in the year, books in the month, pages and minutes a day. Each card looks like the theme
 * tiles in Aspetto (surface of the theme, accent border when on, serif name) with a ring of how far you are.
 * Switches and wheels keep their own state: nothing is rebuilt when one changes, so the cards open and close
 * with their animation and a wheel never jumps back.
 */
@Composable
fun GoalsSection(goals: ReadingGoals, books: List<Book>) {
    val today = remember { LocalDate.now() }; val month = remember { YearMonth.now() }
    var yearOn by remember { mutableStateOf(goals.yearOn) }
    var monthOn by remember { mutableStateOf(goals.monthOn) }
    var pagesOn by remember { mutableStateOf(goals.dailyPagesOn) }
    var minutesOn by remember { mutableStateOf(goals.dailyMinutesOn) }
    val year = remember(books) { goals.year(books, today) }
    val monthDone = remember(books) { goals.month(books, month) }
    val pages = remember(books) { goals.pagesToday(books, today) }
    val minutes = remember(books) { goals.minutesToday(books, today) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WheelGoal(R.drawable.ic_flag, "Libri all’anno", "La tua rotta ${today.year}", yearOn, { goals.yearOn = it; yearOn = it }, goals.yearBooks,
            goalRange(1..200, goals.yearBooks), "libri", { goals.yearBooks = it }, year.done, "completati quest’anno") { goal ->
            val left = goal - year.done
            if (left > 0) "Mancano ${ChronoUnit.DAYS.between(today, LocalDate.of(today.year, 12, 31))} giorni e $left " + (if (left == 1L) "libro" else "libri") + "." else null
        }
        WheelGoal(R.drawable.ic_bookmark, "Libri al mese", "Obiettivo di ${monthName(month)}", monthOn, { goals.monthOn = it; monthOn = it }, goals.monthBooks,
            goalRange(1..30, goals.monthBooks), "libri", { goals.monthBooks = it }, monthDone.done, "completati questo mese") { null }
        WheelGoal(R.drawable.ic_book, "Pagine al giorno", "Obiettivo di oggi", pagesOn, { goals.dailyPagesOn = it; pagesOn = it }, goals.dailyPages,
            goalRange(1..500, goals.dailyPages), "pagine", { goals.dailyPages = it }, pages.done, "lette oggi") { null }
        WheelGoal(R.drawable.ic_timer, "Minuti al giorno", "Tempo di lettura", minutesOn, { goals.dailyMinutesOn = it; minutesOn = it }, goals.dailyMinutes,
            goalRange(1..300, goals.dailyMinutes), "min", { goals.dailyMinutes = it }, minutes.done, "letti oggi") { null }
        Text("Compaiono in Home e in Statistiche. I progressi usano le sessioni registrate e le date in cui hai finito i libri.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One goal: icon ring, name and switch, then the wheel to choose the number and the progress under it. */
@Composable
private fun WheelGoal(icon: Int, title: String, kicker: String, on: Boolean, onSwitch: (Boolean) -> Unit, value: Int, range: IntRange, unit: String,
                      onValue: (Int) -> Unit, done: Long, doneLabel: String, note: (Int) -> String?) {
    val scheme = MaterialTheme.colorScheme
    var current by remember { mutableIntStateOf(value.coerceIn(range)) }
    val percent = (done * 100 / current.coerceAtLeast(1)).toInt(); val reached = done >= current
    val border by animateColorAsState(if (on) scheme.primary else scheme.outlineVariant, tween(220), label = "border")
    val ring by animateFloatAsState(if (on) (percent / 100f).coerceIn(0f, 1f) else 0f, tween(500), label = "ring")
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainer, border = BorderStroke(if (on) 1.5.dp else 1.dp, border)) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).semantics { contentDescription = "$title: ${percent.coerceAtMost(100)}%" }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { ring }, Modifier.fillMaxSize(), color = if (reached) scheme.tertiary else scheme.primary,
                        trackColor = scheme.outlineVariant, strokeWidth = 3.dp, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                    Box(Modifier.size(36.dp).clip(CircleShape).background(if (on) scheme.primaryContainer else scheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = if (on) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(app.atlante.library.ui.L.t(kicker).uppercase(app.atlante.library.ui.L.locale()), style = MaterialTheme.typography.labelSmall, color = if (on) scheme.primary else scheme.onSurfaceVariant)
                    Text(title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif), color = scheme.onSurface, maxLines = 1)
                    if (!on) Text("Spento", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    else Text("$current $unit" + if (reached) " · raggiunto ✓" else "", style = MaterialTheme.typography.bodySmall,
                        color = if (reached) scheme.primary else scheme.onSurfaceVariant)
                }
                Switch(on, onSwitch, Modifier.semantics { contentDescription = "$title: " + if (on) "attivo" else "spento" })
            }
            AnimatedVisibility(on, enter = expandVertically(tween(260)) + fadeIn(tween(200, 60)), exit = shrinkVertically(tween(220)) + fadeOut(tween(120))) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalWheel(current, range, true, title, { "$it" }) { v -> if (v != current) { current = v; onValue(v) } }
                    Text("$current $unit", Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = scheme.primary)
                    Spacer(Modifier.height(10.dp))
                    val bar by animateFloatAsState((percent / 100f).coerceIn(0f, 1f), tween(300), label = "bar")
                    LinearProgressIndicator(progress = { bar }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = if (reached) scheme.tertiary else scheme.primary, trackColor = scheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                    Text("$done di $current $doneLabel · ${percent.coerceAtMost(100)}%" + if (reached) " · raggiunto ✓" else "",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (reached) scheme.primary else scheme.onSurfaceVariant)
                    note(current)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                }
            }
        }
    }
}
