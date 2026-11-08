package app.atlante.library.ui.compose

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.atlante.library.ui.ThemePalette
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pages ↔ percentage: never above the total or 100%, and no page number invented when the total is unknown. */
object ProgressMath {
    const val UNKNOWN_MAX_PAGES = 5000

    fun maxPage(total: Int) = if (total > 0) total else UNKNOWN_MAX_PAGES
    fun clampPage(page: Int, total: Int) = page.coerceIn(0, maxPage(total))
    fun clampPercent(percent: Int) = percent.coerceIn(0, 100)
    /** Percentage for a page; null when the total is unknown (the percentage stays independent). */
    fun percentOf(page: Int, total: Int): Int? = if (total > 0) clampPercent((100.0 * clampPage(page, total) / total).roundToInt()) else null
    /** Page for a percentage; null when the total is unknown. */
    fun pageOf(percent: Int, total: Int): Int? = if (total > 0) clampPage((clampPercent(percent) * total.toDouble() / 100.0).roundToInt(), total) else null
}

/** Values chosen on the wheels, read by the book editor when you confirm. 0 = Pagina, 1 = %, 2 = Pagine lette. */
class ProgressWheelState @JvmOverloads constructor(page: Int, total: Int, percent: Int, val startPage: Int, mode: Int, minutes: Int = 0, val timed: Boolean = false) {
    /** Reading time of this update, in minutes (from the timer, or optional). */
    var minutes by mutableIntStateOf(minutes.coerceIn(0, 1440))
    var mode by mutableIntStateOf(mode.coerceIn(0, 2))
    var total by mutableIntStateOf(total.coerceAtLeast(0))
    var page by mutableIntStateOf(ProgressMath.clampPage(page, total))
    var percent by mutableIntStateOf(ProgressMath.clampPercent(percent))
    var read by mutableIntStateOf(0)

    fun choosePage(p: Int) { page = ProgressMath.clampPage(p, total); ProgressMath.percentOf(page, total)?.let { percent = it } }
    fun choosePercent(p: Int) { percent = ProgressMath.clampPercent(p); ProgressMath.pageOf(percent, total)?.let { page = it } }
    fun chooseTotal(t: Int) { total = t.coerceIn(0, ProgressMath.UNKNOWN_MAX_PAGES); page = ProgressMath.clampPage(page, total); ProgressMath.percentOf(page, total)?.let { percent = it } }
    val maxRead: Int get() = if (total > 0) (total - startPage).coerceAtLeast(0) else 2000
}

object ProgressWheelsView {
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, state: ProgressWheelState, prefs: SharedPreferences): View = ComposeView(activity).apply {
        setContent { AtlanteTheme(palette) { ProgressWheels(state, prefs) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressWheels(state: ProgressWheelState, prefs: SharedPreferences) {
    var haptics by remember { mutableStateOf(prefs.getBoolean("wheelHaptics", true)) }
    var typing by remember { mutableStateOf<String?>(null) }   // "page", "percent", "total", "read"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Pagine", "Percentuale", "Pagine lette").forEachIndexed { i, label ->
                SegmentedButton(state.mode == i, { state.mode = i; prefs.edit().putInt("progressMode", i).apply() }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
            }
        }
        Spacer(Modifier.height(16.dp))
        when (state.mode) {
            0 -> {
                BigValue("${state.page}", if (state.total > 0) "di ${state.total} pagine · ${state.percent}%" else "di ? pagine") { typing = "page" }
                HorizontalWheel(state.page, 0..ProgressMath.maxPage(state.total), haptics, "Pagina", { "$it" }) { state.choosePage(it) }
                TextButton(onClick = { typing = "total" }) { Text(if (state.total > 0) "Totale pagine: ${state.total} · modifica" else "Totale pagine sconosciuto · indicalo") }
            }
            1 -> {
                BigValue("${state.percent}%", if (state.total > 0) "pagina ${state.page} di ${state.total}" else "il numero di pagine non è noto") { typing = "percent" }
                HorizontalWheel(state.percent, 0..100, haptics, "Percentuale", { "$it" }) { state.choosePercent(it) }
            }
            else -> {
                BigValue("+${state.read}", "pagine da pagina ${state.startPage}" + if (state.total > 0) " (fino a ${minOf(state.total, state.startPage + state.read)})" else "") { typing = "read" }
                HorizontalWheel(state.read, 0..state.maxRead, haptics, "Pagine lette", { "+$it" }) { state.read = it.coerceIn(0, state.maxRead) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(if (state.timed) "Tempo della sessione" else "Minuti di lettura (facoltativo)", Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        BigValue("${state.minutes} min", if (state.timed) "dal timer, puoi correggerlo" else "oggi, in tutto: correggilo se serve") { typing = "minutes" }
        HorizontalWheel(state.minutes, 0..1440, haptics, "Minuti di lettura", { "$it" }) { state.minutes = it.coerceIn(0, 1440) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vibrazione", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(haptics, { haptics = it; prefs.edit().putBoolean("wheelHaptics", it).apply() }, Modifier.scale(0.8f))
            Spacer(Modifier.weight(1f))
            Text("Tocca il numero per scriverlo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    typing?.let { field ->
        val (label, max, current) = when (field) {
            "page" -> Triple("Pagina", ProgressMath.maxPage(state.total), state.page)
            "percent" -> Triple("Percentuale", 100, state.percent)
            "total" -> Triple("Totale pagine (0 se non lo sai)", ProgressMath.UNKNOWN_MAX_PAGES, state.total)
            "minutes" -> Triple("Minuti di lettura", 1440, state.minutes)
            else -> Triple("Pagine lette", state.maxRead, state.read)
        }
        // The current number is selected and focused: typing replaces it.
        var field_ by remember(field) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(current.toString(), androidx.compose.ui.text.TextRange(0, current.toString().length))) }
        val text = field_.text
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(field) { focus.requestFocus() }
        val n = text.toIntOrNull()
        AlertDialog(onDismissRequest = { typing = null }, title = { Text(label) },
            text = { OutlinedTextField(field_, { v -> field_ = v.copy(text = v.text.filter(Char::isDigit).take(5)) }, Modifier.focusRequester(focus), singleLine = true, isError = n == null || n > max,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text("Da 0 a $max") }) },
            confirmButton = { TextButton(enabled = n != null && n <= max, onClick = {
                when (field) { "page" -> state.choosePage(n!!); "percent" -> state.choosePercent(n!!); "total" -> state.chooseTotal(n!!); "minutes" -> state.minutes = n!!; else -> state.read = n!! }
                typing = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { typing = null }) { Text("Annulla") } })
    }
}

@Composable
private fun BigValue(value: String, detail: String, onTap: () -> Unit) {
    Column(Modifier.clickable(onClickLabel = "Scrivi il numero", onClick = onTap).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Horizontal number wheel: flings and snaps to the value under the centre mark, which is larger and coloured;
 * the others fade with the distance. A light tick (optional) for every value passed.
 */
@Composable
fun HorizontalWheel(value: Int, range: IntRange, haptics: Boolean, label: String, format: (Int) -> String, onValue: (Int) -> Unit) {
    val itemWidth = 56.dp
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (value - range.first).coerceIn(0, range.last - range.first))
    val haptic = LocalHapticFeedback.current
    // Value under the centre mark: the visible item whose middle is closest to the middle of the wheel.
    val centred by remember(range) { derivedStateOf {
        val info = list.layoutInfo; val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
        val index = info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }?.index ?: (value - range.first)
        range.first + index.coerceIn(0, range.last - range.first)
    } }
    val onValueNow by rememberUpdatedState(onValue)
    val valueNow by rememberUpdatedState(value)
    var userScroll by remember { mutableStateOf(false) }
    LaunchedEffect(list, range, haptics) {
        snapshotFlow { Triple(list.isScrollInProgress, centred, list.layoutInfo.visibleItemsInfo.isNotEmpty()) }
            .distinctUntilChanged().collect { (scrolling, centre, ready) ->
                if(scrolling) userScroll=true
                if(ready && userScroll && centre!=valueNow) {
                    onValueNow(centre)
                    if(haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                if(!scrolling) userScroll=false
            }
    }
    LaunchedEffect(value, range) {
        if (!list.isScrollInProgress && !userScroll && centred != value)
            list.scrollToItem((value - range.first).coerceIn(0, range.last - range.first))
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(72.dp).semantics {
        contentDescription = label; stateDescription = format(value)
        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(), (range.last - range.first - 1).coerceAtLeast(0))
        setProgress { target -> onValue(target.roundToInt().coerceIn(range)); true }
    }, contentAlignment = Alignment.Center) {
        val side = (maxWidth - itemWidth) / 2
        Surface(Modifier.size(itemWidth, 60.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) {}
        LazyRow(state = list, flingBehavior = rememberSnapFlingBehavior(list), contentPadding = PaddingValues(horizontal = side), modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically) {
            items(range.last - range.first + 1) { i ->
                val v = range.first + i
                val distance = abs(v - centred)
                val alpha by animateFloatAsState(when (distance) { 0 -> 1f; 1 -> 0.7f; 2 -> 0.45f; else -> 0.25f }, label = "alpha")
                Box(Modifier.width(itemWidth).fillMaxHeight().clickable { onValue(v) }, contentAlignment = Alignment.Center) {
                    Text(format(v), Modifier.alpha(alpha).scale(if (distance == 0) 1.25f else 1f),
                        style = if (distance == 0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = if (distance == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (distance == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
