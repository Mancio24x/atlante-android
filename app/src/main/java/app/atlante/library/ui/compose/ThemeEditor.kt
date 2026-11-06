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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.atlante.library.ui.ThemePalette
import kotlin.math.roundToInt

/** "I miei temi": three colours chosen by touch (no colour codes), with a live preview. */
object ThemeEditorView {
    fun interface Save { fun save(name: String, paper: Int, surface: Int, accent: Int) }
    fun interface Apply { fun apply(name: String) }

    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, paper: Int, surface: Int, accent: Int, saved: List<String>, onSave: Save, onApply: Apply): View =
        ComposeView(activity).apply { setContent { AtlanteTheme(palette) { ThemeEditor(Color(paper), Color(surface), Color(accent), saved, onSave, onApply) } } }
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
fun ThemeEditor(paper0: Color, surface0: Color, accent0: Color, saved: List<String>, onSave: ThemeEditorView.Save, onApply: ThemeEditorView.Apply) {
    val colors = remember { mutableStateListOf(paper0, surface0, accent0) }
    var editing by remember { mutableIntStateOf(2) }
    var name by remember { mutableStateOf("Il mio tema") }
    val labels = listOf("Sfondo", "Schede", "Accento")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tocca uno dei tre colori, poi scegli la tonalità trascinando il dito nel riquadro e sulla barra.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            labels.forEachIndexed { i, label ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { editing = i }
                    .border(if (editing == i) 3.dp else 1.dp, if (editing == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(8.dp).semantics { contentDescription = "Modifica il colore $label"; selected = editing == i },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp)).background(colors[i]))
                    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        ColorPicker(colors[editing], labels[editing]) { colors[editing] = it }
        Preview(colors[0], colors[1], colors[2])
        OutlinedTextField(name, { name = it.take(30) }, label = { Text("Nome del tema") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = name.isNotBlank(), onClick = { onSave.save(name.trim(), colors[0].toArgb(), colors[1].toArgb(), colors[2].toArgb()) }, modifier = Modifier.fillMaxWidth()) {
            Text("Salva e applica il tema")
        }
        if (saved.isNotEmpty()) {
            Text("Temi salvati", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(saved) { s -> AssistChip(onClick = { onApply.apply(s) }, label = { Text(s) }) } }
        }
    }
}

/** Saturation/brightness square, hue bar and a few ready shades; everything by touch. */
@Composable
fun ColorPicker(color: Color, label: String, onColor: (Color) -> Unit) {
    val start = color.hsv()
    var hue by remember(label) { mutableFloatStateOf(start[0]) }
    var sat by remember(label) { mutableFloatStateOf(start[1]) }
    var value by remember(label) { mutableFloatStateOf(start[2]) }
    fun emit() = onColor(hsvColor(hue, sat, value))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Square: left to right more saturated, bottom to top brighter.
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(12.dp)).keepTouch()
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

@Composable
private fun Preview(paper: Color, surface: Color, accent: Color) {
    val ink = if (paper.luminance() > 0.4f) Color(0xFF192126) else Color(0xFFF6F6F8)
    val cardInk = if (surface.luminance() > 0.4f) Color(0xFF192126) else Color(0xFFF6F6F8)
    val onAccent = if (accent.luminance() > 0.45f) Color.Black else Color.White
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = paper, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Anteprima", color = ink, style = MaterialTheme.typography.titleMedium)
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = surface) {
                Column(Modifier.padding(12.dp)) {
                    Text("Il nome della rosa", color = cardInk, style = MaterialTheme.typography.titleSmall)
                    Text("Umberto Eco · pagina 120 di 503", color = cardInk.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { 0.24f }, color = accent, trackColor = cardInk.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(accent).padding(horizontal = 18.dp, vertical = 8.dp)) { Text("Riprendi la lettura", color = onAccent) }
        }
    }
}
