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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
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

/**
 * "Obiettivi di lettura" in the settings: no extra taps, the goals open straight on their horizontal wheels
 * (Material 3): books in the year, books in the month, pages and minutes a day. Each goal is a tile like the
 * theme tiles of Aspetto (surface, rounded, accent outline when on), with its icon, switch and, under the wheel,
 * how far you are. Switching a goal animates its tile open or closed (no page rebuild).
 */
@Composable
fun GoalsSection(goals: ReadingGoals, books: List<Book>) {
    val today = LocalDate.now(); val month = YearMonth.now()
    val year = remember(books) { goals.year(books, today) }
    val monthDone = remember(books) { goals.month(books, month) }
    val pages = remember(books) { goals.pagesToday(books, today) }
    val minutes = remember(books) { goals.minutesToday(books, today) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WheelGoal(R.drawable.ic_flag, "Libri all’anno", "La tua rotta ${today.year}", goals.yearOn, { goals.yearOn = it }, goals.yearBooks, 1..200, "libri",
            { goals.yearBooks = it }, year, "completati quest’anno") { target ->
            val left = target - year.done
            if (left > 0) "Mancano ${ChronoUnit.DAYS.between(today, LocalDate.of(today.year, 12, 31))} giorni e $left " + (if (left == 1L) "libro" else "libri") + "." else null
        }
        WheelGoal(R.drawable.ic_library, "Libri al mese", "Obiettivo di ${monthName(month)}", goals.monthOn, { goals.monthOn = it }, goals.monthBooks, 1..30, "libri",
            { goals.monthBooks = it }, monthDone, "completati questo mese") { null }
        WheelGoal(R.drawable.ic_book, "Pagine al giorno", "Obiettivo di oggi", goals.dailyPagesOn, { goals.dailyPagesOn = it }, goals.dailyPages, 1..500, "pagine",
            { goals.dailyPages = it }, pages, "lette oggi") { null }
        WheelGoal(R.drawable.ic_timer, "Minuti al giorno", "Tempo di lettura", goals.dailyMinutesOn, { goals.dailyMinutesOn = it }, goals.dailyMinutes, 1..300, "min",
            { goals.dailyMinutes = it }, minutes, "letti oggi") { null }
        Text("Scorri la rotella per scegliere il numero, o tocca un valore. Compaiono in Home e in Statistiche; i progressi usano le sessioni registrate e le date in cui hai finito i libri.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One goal: icon, name and switch, the wheel to choose the number, and the progress under it. */
@Composable
private fun WheelGoal(icon: Int, title: String, kicker: String, initiallyOn: Boolean, onSwitch: (Boolean) -> Unit, value: Int, range: IntRange, unit: String,
                      onValue: (Int) -> Unit, progress: ReadingGoals.Progress, doneLabel: String, note: (Int) -> String?) {
    val scheme = MaterialTheme.colorScheme
    var on by remember { mutableStateOf(initiallyOn) }
    var current by remember { mutableIntStateOf(value.coerceIn(range)) }
    val reached = on && progress.done >= current
    val border by animateColorAsState(if (on) scheme.primary else scheme.outlineVariant, tween(220), label = "border")
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainer,
        border = BorderStroke(if (on) 1.5.dp else 1.dp, border)) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Switch) { on = it; onSwitch(it) }
                .semantics(mergeDescendants = true) { contentDescription = "$title: " + if (on) "attivo, $current $unit" else "spento" },
                verticalAlignment = Alignment.CenterVertically) {
                // The theme tile's swatch, here a round badge with the goal's icon (filled with the accent when on).
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (on) scheme.primary else scheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (reached) R.drawable.ic_check else icon), null, Modifier.size(22.dp), tint = if (on) scheme.onPrimary else scheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                    Text(app.atlante.library.ui.L.t(kicker).uppercase(app.atlante.library.ui.L.locale()), style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1)
                    Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), color = scheme.onSurface)
                    if (!on) Text("Spento", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Switch(on, null)
            }
            AnimatedVisibility(on, enter = fadeIn(tween(200)) + expandVertically(tween(260)), exit = fadeOut(tween(150)) + shrinkVertically(tween(220))) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalWheel(current, range, true, title, { "$it" }) { v -> current = v; onValue(v) }
                    Text("$current $unit", Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    val percent = (progress.done * 100 / current.coerceAtLeast(1)).toInt()
                    val shown by animateFloatAsState((percent / 100f).coerceIn(0f, 1f), tween(450), label = "progress")
                    LinearProgressIndicator(progress = { shown }, Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = scheme.primary, trackColor = scheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                    Text("${progress.done} di $current $doneLabel · ${percent.coerceAtMost(100)}%" + if (reached) " · raggiunto ✓" else "",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (reached) scheme.primary else scheme.onSurfaceVariant)
                    note(current)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                }
            }
        }
    }
}
