package app.atlante.library.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlante.library.saga.SagaShelf
import app.atlante.library.saga.ShelfItem

/** Reading orders of the map: the saga's own numbering, or the order in which the books came out. */
enum class SagaOrder(val label: String) { SAGA("Ordine della saga"), PUBLICATION("Ordine di pubblicazione") }

/** Items in the chosen order; books without a year go last when ordering by publication. */
fun ordered(items: List<ShelfItem>, order: SagaOrder): List<Int> = when (order) {
    SagaOrder.SAGA -> items.indices.toList()
    SagaOrder.PUBLICATION -> items.indices.sortedWith(compareBy<Int> { items[it].year?.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
}

/**
 * Vertical saga map for phones: a spine from top to bottom with one stop per volume; read, owned and missing
 * books look different, sub-series are bands on the left. Pinch to zoom, drag to move, tap a stop to open it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SagaMap(shelf: SagaShelf, currentBookId: String, onOpen: (ShelfItem) -> Unit) {
    var order by remember { mutableStateOf(SagaOrder.SAGA) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val row = with(density) { 76.dp.toPx() }; val top = with(density) { 40.dp.toPx() }
    val spineX = with(density) { 72.dp.toPx() }; val radius = with(density) { 15.dp.toPx() }
    val laneColors = listOf(scheme.tertiary, scheme.secondary, scheme.primary)
    val sequence = ordered(shelf.items, order)

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SagaOrder.entries.forEachIndexed { i, o ->
                SegmentedButton(order == o, { order = o; offset = Offset.Zero }, SegmentedButtonDefaults.itemShape(i, SagaOrder.entries.size)) { Text(o.label) }
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(scheme.primary, true, "Letto"); Legend(scheme.secondary, true, "In biblioteca"); Legend(scheme.outline, false, "Mancante")
        }
        if (shelf.lanes.isNotEmpty()) Text("Bande a sinistra: " + shelf.lanes.keys.joinToString(", "), Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Canvas(
            Modifier.fillMaxSize().clipToBounds()
                .semantics { contentDescription = "Mappa della saga ${shelf.series?.name}: ${shelf.total} volumi, ${shelf.owned} in biblioteca" }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(0.5f, 3f)
                        // Zoom around the fingers, then follow the drag.
                        offset = (offset - centroid) * (newScale / scale) + centroid + pan
                        scale = newScale
                    }
                }
                .pointerInput(sequence) {
                    detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) { p ->
                        val y = (p.y - offset.y) / scale
                        val i = ((y - top) / row + 0.5f).toInt()
                        if (i in sequence.indices) onOpen(shelf.items[sequence[i]])
                    }
                }
        ) {
            withTransform({ translate(offset.x, offset.y); scale(scale, scale, Offset.Zero) }) {
                val last = top + (sequence.size - 1).coerceAtLeast(0) * row
                drawLine(scheme.outlineVariant, Offset(spineX, top), Offset(spineX, last), strokeWidth = 4.dp.toPx())
                // Sub-series: a band on the left beside the volumes that belong to it.
                shelf.lanes.entries.forEachIndexed { lane, (_, indexes) ->
                    val positions = indexes.map { sequence.indexOf(it) }.filter { it >= 0 }
                    if (positions.isEmpty()) return@forEachIndexed
                    val x = 14.dp.toPx() + lane * 12.dp.toPx()
                    drawRoundRect(laneColors[lane % laneColors.size].copy(alpha = 0.55f), Offset(x, top + positions.min() * row - radius),
                        Size(6.dp.toPx(), (positions.max() - positions.min()) * row + 2 * radius), CornerRadius(3.dp.toPx()))
                }
                sequence.forEachIndexed { position, index ->
                    val item = shelf.items[index]
                    val center = Offset(spineX, top + position * row)
                    val owned = item.ownedBookId != null
                    val color = when { item.read -> scheme.primary; owned -> scheme.secondary; else -> scheme.outline }
                    if (owned) drawCircle(color, radius, center)
                    else {
                        drawCircle(scheme.surface, radius, center)
                        drawCircle(color, radius, center, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                    }
                    if (item.ownedBookId == currentBookId) drawCircle(scheme.tertiary, radius + 6.dp.toPx(), center, style = Stroke(3.dp.toPx()))
                    val number = item.ordinal ?: "–"
                    val n = measurer.measure(number, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (owned) scheme.surface else color))
                    drawText(n, topLeft = center - Offset(n.size.width / 2f, n.size.height / 2f))
                    val textX = spineX + radius + 16.dp.toPx(); val width = (size.width / scale - textX - 16.dp.toPx()).coerceAtLeast(40f)
                    val title = measurer.measure(item.title, TextStyle(fontSize = 16.sp, fontWeight = if (owned) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (owned) scheme.onSurface else scheme.onSurfaceVariant), overflow = TextOverflow.Ellipsis, maxLines = 1,
                        constraints = androidx.compose.ui.unit.Constraints(maxWidth = width.toInt()))
                    drawText(title, topLeft = Offset(textX, center.y - title.size.height + 2.dp.toPx()))
                    val detail = listOfNotNull(item.year, when { item.read -> "Letto"; owned -> "In biblioteca"; else -> "Mancante" }).joinToString(" · ")
                    val d = measurer.measure(detail, TextStyle(fontSize = 12.sp, color = color), maxLines = 1,
                        constraints = androidx.compose.ui.unit.Constraints(maxWidth = width.toInt()))
                    drawText(d, topLeft = Offset(textX, center.y + 4.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, filled: Boolean, label: String) {
    Surface(Modifier.size(12.dp), shape = CircleShape, color = if (filled) color else Color.Transparent,
        border = if (filled) null else androidx.compose.foundation.BorderStroke(2.dp, color)) {}
    Text(label, Modifier.padding(start = 6.dp, end = 16.dp), style = MaterialTheme.typography.bodySmall)
}
