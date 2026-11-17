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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        setContent { AtlanteTheme(palette) { GoalsSection(ReadingGoals(prefs), books) } }
    }
}

private fun monthName(m: YearMonth) = m.month.getDisplayName(TextStyle.FULL, L.locale())
private val TileShape = RoundedCornerShape(22.dp)      // the same tile as the themes in Aspetto

/** One goal on the page: what it counts and how its value and switch are kept. */
private class GoalSpec(val title: String, val kicker: String, val unit: String, val range: IntRange, val doneLabel: String, val done: Long,
                       initialOn: Boolean, initialValue: Int, val saveOn: (Boolean) -> Unit, val saveValue: (Int) -> Unit) {
    var on by mutableStateOf(initialOn)
    var value by mutableIntStateOf(initialValue.coerceIn(range))
}

/**
 * "Obiettivi di lettura" in the settings, drawn like the theme tiles of Aspetto: a summary tile with a small ring
 * for each goal, then one tile per goal (serif name, ring with the percentage, switch; the outline takes the accent
 * colour when the goal is on). An active goal opens on its horizontal wheel. Switching a goal only opens or closes
 * its own tile (before, every switch rebuilt the whole page: the wheels jumped and nothing animated).
 */
@Composable
fun GoalsSection(goals: ReadingGoals, books: List<Book>) {
    val today = remember { LocalDate.now() }; val month = remember { YearMonth.now() }
    val specs = remember(goals, books) {
        listOf(
            GoalSpec("Libri all’anno", "La tua rotta ${today.year}", "libri", 1..200, "completati quest’anno", goals.year(books, today).done,
                goals.yearOn, goals.yearBooks, { goals.yearOn = it }, { goals.yearBooks = it }),
            GoalSpec("Libri al mese", "Obiettivo di ${monthName(month)}", "libri", 1..30, "completati questo mese", goals.month(books, month).done,
                goals.monthOn, goals.monthBooks, { goals.monthOn = it }, { goals.monthBooks = it }),
            GoalSpec("Pagine al giorno", "Obiettivo di oggi", "pagine", 1..500, "lette oggi", goals.pagesToday(books, today).done,
                goals.dailyPagesOn, goals.dailyPages, { goals.dailyPagesOn = it }, { goals.dailyPages = it }),
            GoalSpec("Minuti al giorno", "Tempo di lettura", "min", 1..300, "letti oggi", goals.minutesToday(books, today).done,
                goals.dailyMinutesOn, goals.dailyMinutes, { goals.dailyMinutesOn = it }, { goals.dailyMinutes = it }))
    }
    val year = specs[0]
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Summary(specs)
        for (spec in specs) {
            val note = if (spec === year && year.on && year.done < year.value) {
                val left = year.value - year.done
                "Mancano ${ChronoUnit.DAYS.between(today, LocalDate.of(today.year, 12, 31))} giorni e $left " + (if (left == 1L) "libro" else "libri") + "."
            } else null
            GoalTile(spec, note)
        }
        Text("Compaiono in Home e in Statistiche. I progressi usano le sessioni registrate e le date in cui hai finito i libri.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fraction(spec: GoalSpec) = (spec.done.toFloat() / spec.value.coerceAtLeast(1)).coerceIn(0f, 1f)
private fun percent(spec: GoalSpec) = (spec.done * 100 / spec.value.coerceAtLeast(1)).toInt().coerceIn(0, 100)

/** The summary tile: how many goals are on and reached, and one small ring per goal (like the theme swatches). */
@Composable
private fun Summary(specs: List<GoalSpec>) {
    val scheme = MaterialTheme.colorScheme
    val active = specs.filter { it.on }; val reached = active.count { it.done >= it.value }
    val line = when {
        active.isEmpty() -> "Nessun obiettivo attivo: accendine uno qui sotto."
        reached == active.size -> "Tutti raggiunti: ottima rotta."
        else -> "$reached di ${active.size} raggiunti"
    }
    Surface(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = L.t("Obiettivi di lettura") + ", " + L.t(line) },
        shape = TileShape, color = scheme.surfaceContainer, border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Obiettivi di lettura", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontSize = 20.sp), color = scheme.onSurface)
                Text(line, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            for (spec in specs) {
                val f by animateFloatAsState(if (spec.on) fraction(spec) else 0f, tween(600), label = "summary")
                Box(Modifier.padding(start = 6.dp).size(26.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { f }, Modifier.fillMaxSize(), strokeWidth = 4.dp,
                        color = if (spec.on) scheme.primary else scheme.outline, trackColor = scheme.outlineVariant, gapSize = 0.dp, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
    }
}

/** One goal: ring, serif name and switch; when on, the wheel to choose the number and how far you are. */
@Composable
private fun GoalTile(spec: GoalSpec, note: String?) {
    val scheme = MaterialTheme.colorScheme
    val reached = spec.on && spec.done >= spec.value
    val outline by animateColorAsState(if (spec.on) scheme.primary else scheme.outlineVariant, label = "outline")
    val f by animateFloatAsState(if (spec.on) fraction(spec) else 0f, tween(600), label = "ring")
    Surface(Modifier.fillMaxWidth(), shape = TileShape, color = scheme.surfaceContainer, border = BorderStroke(if (spec.on) 1.5.dp else 1.dp, outline)) {
        Column(Modifier.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).semantics(mergeDescendants = true) { contentDescription = L.t(spec.title) + ": " + if (spec.on) "${percent(spec)}%" else L.t("spento") },
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { f }, Modifier.fillMaxSize(), strokeWidth = 5.dp,
                        color = if (reached) scheme.tertiary else scheme.primary, trackColor = scheme.outlineVariant, gapSize = 0.dp, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                    Text(if (reached) "✓" else if (spec.on) "${percent(spec)}" else "–", style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold, color = if (spec.on) scheme.onSurface else scheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(L.t(spec.kicker).uppercase(L.locale()), style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1)
                    Text(spec.title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif, fontSize = 18.sp), color = scheme.onSurface)
                    Text(if (spec.on) "${spec.done} di ${spec.value} ${spec.unit}" else "Spento", style = MaterialTheme.typography.bodySmall,
                        color = if (reached) scheme.primary else scheme.onSurfaceVariant, maxLines = 1)
                }
                Switch(spec.on, { spec.on = it; spec.saveOn(it) }, Modifier.semantics { contentDescription = "${spec.title}: " + if (spec.on) "attivo" else "spento" })
            }
            AnimatedVisibility(spec.on, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalWheel(spec.value, spec.range, true, spec.title, { "$it" }) { v -> spec.value = v; spec.saveValue(v) }
                    Text("${spec.value} ${spec.unit}", Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    val bar by animateFloatAsState(fraction(spec), tween(400), label = "bar")
                    LinearProgressIndicator(progress = { bar }, Modifier.fillMaxWidth().height(6.dp),
                        color = if (reached) scheme.tertiary else scheme.primary, trackColor = scheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                    Text("${spec.done} di ${spec.value} ${spec.doneLabel} · ${percent(spec)}%" + if (reached) " · raggiunto ✓" else "",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (reached) scheme.primary else scheme.onSurfaceVariant)
                    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}
