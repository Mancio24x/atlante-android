package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.atlante.library.R
import app.atlante.library.ui.ThemePalette
import kotlin.math.roundToInt

/** "Il mio tema": one custom theme, three colours chosen by touch (no colour codes), with a live preview. */
object ThemeEditorView {
    fun interface Apply { fun apply(paper: Int, surface: Int, accent: Int) }

    /** The full-screen editor: colours in the top half, the theme as it will look in the bottom half. */
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, paper: Int, surface: Int, accent: Int, onApply: Apply): View =
        ComposeView(activity).apply { setContent { AtlanteTheme(palette) { ThemeEditor(Color(paper), Color(surface), Color(accent), onApply) } } }

    /** The round "Il mio tema" button that sits above the ready-made themes. */
    @JvmStatic
    fun circle(activity: ComponentActivity, palette: ThemePalette, paper: Int, surface: Int, accent: Int, active: Boolean, onOpen: Runnable): View =
        ComposeView(activity).apply { setContent { AtlanteTheme(palette) { CustomThemeCircle(Color(paper), Color(surface), Color(accent), active) { onOpen.run() } } } }
}

@Composable
fun CustomThemeCircle(paper: Color, surface: Color, accent: Color, active: Boolean, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onOpen, shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainer,
        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) scheme.primary else scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "Il mio tema" + if (active) ", in uso" else ""; role = Role.Button
        }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Three slices: background, cards, accent.
            Canvas(Modifier.size(72.dp)) {
                val slices = listOf(paper, surface, accent)
                slices.forEachIndexed { i, c -> drawArc(c, -90f + i * 120f, 120f, true) }
                drawCircle(scheme.outlineVariant, size.minDimension / 2, style = Stroke(1.dp.toPx()))
                drawCircle(accent, size.minDimension / 5)
                drawCircle(Color.White, size.minDimension / 5, style = Stroke(2.dp.toPx()))
            }
            Column(Modifier.weight(1f)) {
                Text("Il mio tema", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(if (active) "In uso · tocca per cambiare i colori" else "Scegli tu sfondo, schede e accento",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (active) Icon(painterResource(R.drawable.ic_check), null, tint = scheme.primary)
            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = scheme.onSurfaceVariant)
        }
    }
}

/** Inside the scrolling settings page: a touch that starts here stays here (the page does not scroll). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Modifier.keepTouch(): Modifier {
    val view = androidx.compose.ui.platform.LocalView.current
    return this.then(Modifier.pointerInteropFilter { e ->
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
        false
    })
}

/** Hue (0–360), saturation and value (0–1) of a colour, and back. */
fun Color.hsv(): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(toArgb(), it) }
fun hsvColor(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))

@Composable
fun ThemeEditor(paper0: Color, surface0: Color, accent0: Color, onApply: ThemeEditorView.Apply) {
    val colors = remember { mutableStateListOf(paper0, surface0, accent0) }
    var editing by remember { mutableIntStateOf(2) }
    val labels = listOf("Sfondo", "Schede", "Accento")
    // The whole visible screen: top half for the colours, bottom half for the preview.
    // The visible screen under the page's heading, whatever the phone: never taller (the button must be reachable).
    val height = (LocalConfiguration.current.screenHeightDp - 190).coerceIn(380, 1100).dp
    Column(Modifier.fillMaxWidth().height(height), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Apply first: always in sight, also on a small phone.
        Button(onClick = { onApply.apply(colors[0].toArgb(), colors[1].toArgb(), colors[2].toArgb()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Applica il mio tema")
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, label ->
                    val on = editing == i
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable { editing = i }
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp).semantics { contentDescription = "Modifica il colore $label"; selected = on },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(colors[i]).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
            ColorPicker(colors[editing], labels[editing], Modifier.weight(1f)) { colors[editing] = it }
        }
        Preview(colors[0], colors[1], colors[2], Modifier.weight(1f))
    }
}

/** Saturation/brightness square, hue bar and a few ready shades; everything by touch. */
@Composable
fun ColorPicker(color: Color, label: String, modifier: Modifier = Modifier, onColor: (Color) -> Unit) {
    val start = color.hsv()
    var hue by remember(label) { mutableFloatStateOf(start[0]) }
    var sat by remember(label) { mutableFloatStateOf(start[1]) }
    var value by remember(label) { mutableFloatStateOf(start[2]) }
    fun emit() = onColor(hsvColor(hue, sat, value))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Square: left to right more saturated, bottom to top brighter.
        Canvas(Modifier.fillMaxWidth().weight(1f).heightIn(min = 120.dp).clip(RoundedCornerShape(12.dp)).keepTouch()
            .semantics { contentDescription = "Saturazione e luminosità di $label"; stateDescription = "saturazione ${(sat * 100).roundToInt()}%, luminosità ${(value * 100).roundToInt()}%" }
            .pointerInput(label) {
                fun set(p: Offset) { sat = (p.x / size.width).coerceIn(0f, 1f); value = 1f - (p.y / size.height).coerceIn(0f, 1f); emit() }
                detectTapGestures { set(it) }
            }
            .pointerInput(label) {
                detectDragGestures(onDragStart = { p -> sat = (p.x / size.width).coerceIn(0f, 1f); value = 1f - (p.y / size.height).coerceIn(0f, 1f); emit() }) { change, _ ->
                    change.consume(); sat = (change.position.x / size.width).coerceIn(0f, 1f); value = 1f - (change.position.y / size.height).coerceIn(0f, 1f); emit()
                }
            }) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hsvColor(hue, 1f, 1f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val p = Offset(sat * size.width, (1 - value) * size.height)
            drawCircle(Color.White, 12.dp.toPx(), p, style = Stroke(3.dp.toPx())); drawCircle(Color.Black.copy(alpha = 0.5f), 13.5.dp.toPx(), p, style = Stroke(1.dp.toPx()))
        }
        // Hue bar: the whole rainbow.
        Canvas(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(16.dp)).keepTouch()
            .semantics {
                contentDescription = "Tonalità di $label"
                progressBarRangeInfo = ProgressBarRangeInfo(hue, 0f..360f); setProgress { hue = it.coerceIn(0f, 360f); emit(); true }
            }
            .pointerInput(label) { detectTapGestures { hue = (it.x / size.width * 360f).coerceIn(0f, 360f); emit() } }
            .pointerInput(label) {
                detectDragGestures(onDragStart = { hue = (it.x / size.width * 360f).coerceIn(0f, 360f); emit() }) { change, _ ->
                    change.consume(); hue = (change.position.x / size.width * 360f).coerceIn(0f, 360f); emit()
                }
            }) {
            drawRect(Brush.horizontalGradient((0..6).map { hsvColor(it * 60f, 1f, 1f) }))
            val x = hue / 360f * size.width
            drawCircle(hsvColor(hue, 1f, 1f), size.height / 2 - 3.dp.toPx(), Offset(x, size.height / 2))
            drawCircle(Color.White, size.height / 2 - 3.dp.toPx(), Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
        }
        // Quick shades: light papers, darks for night, and a few accents.
        val quick = listOf(0xFFFBF8F1, 0xFFF3EFE6, 0xFFE9F1EC, 0xFF101014, 0xFF1C1C22, 0xFF326359, 0xFF8C3B2E, 0xFF44507A, 0xFFB5852B, 0xFF5B2A4E).map { Color(it) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quick) { q ->
                Box(Modifier.size(32.dp).clip(CircleShape).background(q).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(onClickLabel = "Usa questa tonalità") { val h = q.hsv(); hue = h[0]; sat = h[1]; value = h[2]; emit() })
            }
        }
    }
}

/** A small Home as it will look: heading, the book being read, chips and the bottom bar. */
@Composable
private fun Preview(paper: Color, surface: Color, accent: Color, modifier: Modifier = Modifier) {
    val ink = if (paper.luminance() > 0.4f) Color(0xFF192126) else Color(0xFFF6F6F8)
    val cardInk = if (surface.luminance() > 0.4f) Color(0xFF192126) else Color(0xFFF6F6F8)
    val onAccent = if (accent.luminance() > 0.45f) Color.Black else Color.White
    val line = ink.copy(alpha = 0.14f)
    Surface(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Anteprima del tema" },
        shape = RoundedCornerShape(24.dp), color = paper, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ANTEPRIMA", color = accent, style = MaterialTheme.typography.labelSmall)
                Text("Buongiorno", color = ink, style = MaterialTheme.typography.titleLarge)
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = surface, border = BorderStroke(1.dp, line)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 36.dp, height = 52.dp).clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.85f)))
                        Column(Modifier.weight(1f)) {
                            Text("Il nome della rosa", color = cardInk, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text("Umberto Eco · pagina 120 di 503", color = cardInk.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            LinearProgressIndicator(progress = { 0.24f }, color = accent, trackColor = cardInk.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(accent).padding(horizontal = 16.dp, vertical = 8.dp)) { Text("Riprendi", color = onAccent, style = MaterialTheme.typography.labelLarge) }
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, line, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) { Text("+10 pagine", color = ink, style = MaterialTheme.typography.labelLarge) }
                }
            }
            // Bottom bar: the selected place in the accent colour.
            Row(Modifier.fillMaxWidth().background(surface).padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Home", "Biblioteca", "Scopri").forEachIndexed { i, t ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(width = 40.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(if (i == 0) accent else cardInk.copy(alpha = 0.25f)))
                        Text(t, color = if (i == 0) cardInk else cardInk.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}
