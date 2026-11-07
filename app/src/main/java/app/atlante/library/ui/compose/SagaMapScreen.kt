package app.atlante.library.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.atlante.library.saga.SagaShelf
import app.atlante.library.saga.ShelfItem
import kotlinx.coroutines.launch

private val ROW = 96.dp
private val COVER_W = 44.dp
private val COVER_H = 66.dp
private val MAIN_X = 40.dp
private val SIDE_X = 96.dp

/**
 * Vertical saga map: the main line from top to bottom, one card per work (cover, number, title, state, source of
 * the position). Arrows only where the data documents a relation (see [SagaLayout]); volumes 2.5 and novellas
 * branch to the side; volumes without a verified position are listed under "Posizione da verificare", never
 * placed by guess. Orders: the saga's numbering, first publication, or your own (it wins over every update).
 * Pinch to zoom, drag to move, double tap to reset, tap a card to open it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SagaMap(shelf: SagaShelf, currentBookId: String, host: SagaHost, onOpen: (ShelfItem) -> Unit) {
    var manual by remember(shelf.series?.id) { mutableStateOf(shelf.manualOrder) }
    var order by remember(shelf.series?.id) { mutableStateOf(if (shelf.manualOrder != null) SagaLayout.Order.PERSONAL else SagaLayout.Order.SAGA) }
    var editing by remember { mutableStateOf(false) }
    val layout = remember(shelf.items, order, manual) { SagaLayout.layout(shelf.items, order, manual) }
    val laneOf = remember(shelf.lanes) { shelf.lanes.flatMap { (name, ids) -> ids.map { it to name } }.toMap() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val contentHeight = with(density) { (ROW * layout.nodes.size + 24.dp + if (layout.waiting.isEmpty()) 0.dp else 56.dp + ROW * layout.waiting.size).toPx() }

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SagaLayout.Order.entries.forEachIndexed { i, o ->
                SegmentedButton(order == o, { order = o; offset = Offset.Zero; scale = 1f }, SegmentedButtonDefaults.itemShape(i, SagaLayout.Order.entries.size)) {
                    Text(o.label, maxLines = 1)
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(scheme.primary, true, "Letto"); Legend(scheme.secondary, true, "In biblioteca"); Legend(scheme.outline, false, "Mancante")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { editing = true }) { Text("Cambia l’ordine") }
        }
        if (layout.note.isNotEmpty()) Text(layout.note, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Text("Ordini cronologico e consigliato: le fonti non li documentano per questa saga.", Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)

        Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }
            .semantics { contentDescription = "Mappa verticale della saga ${shelf.series?.name}: ${layout.nodes.size} volumi in ordine, ${layout.waiting.size} da verificare" }
            .pointerInput(contentHeight) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(0.5f, 2.5f)
                    var next = (offset - centroid) * (newScale / scale) + centroid + pan
                    // Kept on the map: no dragging it out of sight.
                    val minY = (viewport.height - contentHeight * newScale).coerceAtMost(0f)
                    val minX = (viewport.width - viewport.width * newScale).coerceAtMost(0f)
                    next = Offset(next.x.coerceIn(minX, 0f.coerceAtLeast(minX)), next.y.coerceIn(minY, 0f))
                    offset = next; scale = newScale
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
        ) {
            Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                .graphicsLayer { translationX = offset.x; translationY = offset.y; scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }) {
                Box(Modifier.fillMaxWidth().height(ROW * layout.nodes.size + 12.dp)) {
                    Canvas(Modifier.matchParentSize()) { arrows(layout, shelf.items, scheme.outline, scheme.primary, scheme.tertiary) }
                    layout.nodes.forEach { n ->
                        val item = shelf.items[n.item]
                        Card(item, n.label, kindLabel(n.kind, item), laneOf[n.item], item.ownedBookId != null && item.ownedBookId == currentBookId, host,
                            Modifier.offset(x = if (n.kind == SagaLayout.Kind.SIDE) SIDE_X - COVER_W / 2 else MAIN_X - COVER_W / 2, y = ROW * n.row + 6.dp)) { onOpen(item) }
                    }
                }
                if (layout.waiting.isNotEmpty()) {
                    Text("Posizione da verificare · ${layout.waiting.size}", Modifier.padding(start = 16.dp, top = 12.dp), style = MaterialTheme.typography.titleSmall)
                    Text("Nessun numero inventato: scegli tu dove vanno con «Cambia l’ordine».", Modifier.padding(start = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    layout.waiting.forEach { w ->
                        val item = shelf.items[w.item]
                        Card(item, null, w.reason, laneOf[w.item], item.ownedBookId != null && item.ownedBookId == currentBookId, host,
                            Modifier.padding(start = MAIN_X - COVER_W / 2).height(ROW)) { onOpen(item) }
                    }
                }
            }
        }
    }
    if (editing) OrderEditor(shelf, manual, onClose = { editing = false }) { saved ->
        editing = false; manual = saved
        order = if (saved != null) SagaLayout.Order.PERSONAL else SagaLayout.Order.SAGA
        val id = shelf.series?.id
        if (id != null) scope.launch { runCatching { SuggestionCovers.suggestions?.saveOrder(id, saved) } }
    }
}

private fun kindLabel(kind: SagaLayout.Kind, item: ShelfItem) = when {
    kind == SagaLayout.Kind.PREQUEL -> "prequel"
    kind == SagaLayout.Kind.SIDE -> if (item.membership == "novella") "racconto" else "volume intermedio"
    else -> ""
}

/** The arrows, only for the links of the layout: solid next volume, dashed for a gap, curved for a side branch. */
private fun DrawScope.arrows(layout: SagaLayout.Layout, items: List<ShelfItem>, line: Color, read: Color, side: Color) {
    val row = ROW.toPx(); val coverTop = 6.dp.toPx(); val coverH = COVER_H.toPx()
    fun x(n: SagaLayout.Node) = (if (n.kind == SagaLayout.Kind.SIDE) SIDE_X else MAIN_X).toPx()
    for (l in layout.links) {
        val a = layout.nodes[l.from]; val b = layout.nodes[l.to]
        val bothRead = items[a.item].read && items[b.item].read
        val color = when { l.type == SagaLayout.Edge.BRANCH -> side; bothRead -> read; else -> line }
        val width = 2.5.dp.toPx()
        val dash = if (l.type == SagaLayout.Edge.GAP) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
        val start: Offset; val end: Offset
        val path = Path()
        if (l.type == SagaLayout.Edge.BRANCH) {
            start = Offset(x(a), a.row * row + coverTop + coverH); end = Offset(x(b) - COVER_W.toPx() / 2, b.row * row + coverTop + coverH / 2)
            path.moveTo(start.x, start.y); path.cubicTo(start.x, end.y, start.x, end.y, end.x - 6.dp.toPx(), end.y)
        } else {
            start = Offset(x(a), a.row * row + coverTop + coverH + 2.dp.toPx()); end = Offset(x(b), b.row * row + coverTop - 2.dp.toPx())
            path.moveTo(start.x, start.y); path.lineTo(end.x, end.y - 6.dp.toPx())
        }
        drawPath(path, color, style = Stroke(width, pathEffect = dash))
        // Arrow head at the target.
        val head = 7.dp.toPx()
        val tip = if (l.type == SagaLayout.Edge.BRANCH) Offset(end.x, end.y) else end
        val headPath = Path().apply {
            if (l.type == SagaLayout.Edge.BRANCH) { moveTo(tip.x, tip.y); lineTo(tip.x - head, tip.y - head / 2); lineTo(tip.x - head, tip.y + head / 2) }
            else { moveTo(tip.x, tip.y); lineTo(tip.x - head / 2, tip.y - head); lineTo(tip.x + head / 2, tip.y - head) }
            close()
        }
        drawPath(headPath, color)
    }
}

@Composable
private fun Card(item: ShelfItem, number: String?, kind: String, lane: String?, current: Boolean, host: SagaHost, modifier: Modifier, onTap: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val owned = item.ownedBookId != null
    val state = when { item.read -> "Letto"; owned -> "In biblioteca"; else -> "Mancante" }
    val source = when (item.source) { "hardcover" -> "Hardcover"; "wikidata" -> "Wikidata"; "catalog" -> "catalogo offline"; "manual" -> "scelta tua"; else -> "" }
    val description = listOfNotNull(number?.let { "Numero $it" }, item.title, state, kind.ifEmpty { null }, lane?.let { "sotto-serie $it" },
        source.ifEmpty { null }?.let { "posizione da $it" }).joinToString(", ")
    Row(modifier.fillMaxWidth().padding(end = 12.dp).clickable(onClick = onTap).semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.Top) {
        Box {
            SagaCover(item, host, Modifier.size(COVER_W, COVER_H))
            if (current) Surface(Modifier.size(COVER_W, COVER_H), shape = RoundedCornerShape(8.dp), color = Color.Transparent, border = BorderStroke(3.dp, scheme.tertiary)) {}
            if (number != null) Surface(Modifier.align(Alignment.TopStart).offset((-8).dp, (-6).dp), shape = CircleShape,
                color = when { item.read -> scheme.primary; owned -> scheme.secondary; else -> scheme.surface },
                border = if (owned) null else BorderStroke(1.5.dp, scheme.outline)) {
                Text(number, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = if (owned) scheme.surface else scheme.onSurface)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (owned) FontWeight.SemiBold else FontWeight.Normal,
                color = if (owned) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(state, kind.ifEmpty { null }, item.year).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = when { item.read -> scheme.primary; owned -> scheme.secondary; else -> scheme.outline })
            val extra = listOfNotNull(lane?.let { "Sotto-serie: $it" }, source.ifEmpty { null }?.let { "Posizione: $it" }).joinToString(" · ")
            if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Your order: move the volumes up and down; saved, it wins over every source and every update. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderEditor(shelf: SagaShelf, manual: List<Long>?, onClose: () -> Unit, onSave: (List<Long>?) -> Unit) {
    val list = remember { SagaLayout.editable(shelf.items, manual).toMutableStateList() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                TopAppBar(title = { Text("Il tuo ordine") }, navigationIcon = { TextButton(onClick = onClose) { Text("Annulla") } },
                    actions = { TextButton(onClick = { onSave(list.mapNotNull { shelf.items[it].workId }) }) { Text("Salva") } })
                Text("Dall’alto verso il basso. Resta tuo: le sincronizzazioni e gli aggiornamenti delle fonti non lo cambiano.",
                    Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (manual != null) TextButton(onClick = { onSave(null) }, Modifier.padding(horizontal = 12.dp)) { Text("Torna all’ordine delle fonti") }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                    itemsIndexed(list, key = { _, i -> i }) { pos, i ->
                        val item = shelf.items[i]
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${pos + 1}.", Modifier.width(32.dp), style = MaterialTheme.typography.labelLarge)
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(item.ordinal?.let { "n. $it per le fonti" } ?: "senza numero", item.year).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { if (pos > 0) { list.removeAt(pos); list.add(pos - 1, i) } }, enabled = pos > 0,
                                modifier = Modifier.semantics { contentDescription = "Sposta su ${item.title}" }) { Text("↑") }
                            TextButton(onClick = { if (pos < list.size - 1) { list.removeAt(pos); list.add(pos + 1, i) } }, enabled = pos < list.size - 1,
                                modifier = Modifier.semantics { contentDescription = "Sposta giù ${item.title}" }) { Text("↓") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, filled: Boolean, label: String) {
    Surface(Modifier.size(12.dp), shape = CircleShape, color = if (filled) color else Color.Transparent,
        border = if (filled) null else BorderStroke(2.dp, color)) {}
    Text(label, Modifier.padding(start = 6.dp, end = 12.dp), style = MaterialTheme.typography.bodySmall)
}
