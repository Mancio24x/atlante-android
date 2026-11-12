package app.atlante.library.ui.compose

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch
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
 * the others fade with the distance and towards the edges, over a ruler (a longer tick every 5 and 10). Tapping a
 * number glides to it. A light tick (optional) for every value passed.
 *
 * The wheel sits in View pages that scroll vertically (Impostazioni, the book editor): a ScrollView parent steals
 * any drag that drifts a little vertically, so the wheel stopped half way. While a finger is on the wheel the parent
 * may not intercept, unless the drag is clearly vertical: then the page scrolls as before.
 */
@Composable
fun HorizontalWheel(value: Int, range: IntRange, haptics: Boolean, label: String, format: (Int) -> String, onValue: (Int) -> Unit) {
    val digits = maxOf(format(range.first).length, format(range.last).length)
    val itemWidth = if (digits >= 5) 76.dp else if (digits == 4) 66.dp else 56.dp
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (value - range.first).coerceIn(0, range.last - range.first))
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val scheme = MaterialTheme.colorScheme
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
        if (!list.isScrollInProgress && !userScroll && centred != value) {
            val target = (value - range.first).coerceIn(0, range.last - range.first)
            // One step (− / + of the goals) glides; it passes no other number, so nothing half way is reported.
            if (abs(centred - value) == 1 && list.layoutInfo.visibleItemsInfo.isNotEmpty()) list.animateScrollToItem(target)
            else list.scrollToItem(target)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(76.dp)
        .pointerInput(view) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                view.parent?.requestDisallowInterceptTouchEvent(true)
                var moved = Offset.Zero; var decided = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (!decided) {
                        moved += change.position - change.previousPosition
                        if (moved.getDistance() > viewConfiguration.touchSlop) {
                            decided = true
                            if (abs(moved.y) > abs(moved.x) * 1.5f) view.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        .semantics {
            contentDescription = label; stateDescription = format(value)
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(), (range.last - range.first - 1).coerceAtLeast(0))
            setProgress { target -> onValue(target.roundToInt().coerceIn(range)); true }
        }, contentAlignment = Alignment.Center) {
        val side = (maxWidth - itemWidth) / 2
        Surface(Modifier.size(itemWidth, 60.dp), shape = RoundedCornerShape(16.dp), color = lerp(scheme.surfaceContainer, scheme.primary, .16f),
            border = BorderStroke(2.dp, scheme.primary)) {}
        LazyRow(state = list, flingBehavior = rememberSnapFlingBehavior(list), contentPadding = PaddingValues(horizontal = side),
            modifier = Modifier.fillMaxSize()
                // The numbers fade out towards both edges, like a real wheel.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(0f to Color.Transparent, .22f to Color.Black, .78f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
            verticalAlignment = Alignment.CenterVertically) {
            items(range.last - range.first + 1) { i ->
                val v = range.first + i
                val distance = abs(v - centred)
                val alpha by animateFloatAsState(when (distance) { 0 -> 1f; 1 -> 0.72f; 2 -> 0.48f; else -> 0.3f }, label = "alpha")
                val grow by animateFloatAsState(if (distance == 0) 1.2f else 1f, label = "scale")
                Box(Modifier.width(itemWidth).fillMaxHeight()
                    .clickable(onClickLabel = format(v)) { scope.launch { list.animateScrollToItem(i) } }, contentAlignment = Alignment.Center) {
                    Text(format(v), Modifier.graphicsLayer { this.alpha = alpha; scaleX = grow; scaleY = grow },
                        style = if (distance == 0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = if (distance == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (distance == 0) scheme.primary else scheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                    // Ruler under the numbers: every value a tick, longer every 5, longest every 10.
                    val tick = when { v % 10 == 0 -> 10.dp; v % 5 == 0 -> 7.dp; else -> 4.dp }
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(if (distance == 0) 2.dp else 1.dp, tick)
                        .graphicsLayer { this.alpha = alpha }.background(if (distance == 0) scheme.primary else scheme.outline))
                }
            }
        }
    }
}
