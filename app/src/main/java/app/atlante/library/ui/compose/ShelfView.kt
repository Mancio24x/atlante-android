package app.atlante.library.ui.compose

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlante.library.model.Book
import app.atlante.library.ui.ThemePalette
import kotlinx.coroutines.delay

/**
 * Bookcase view of the library: shelves on several levels, books standing side by side showing only the spine
 * with the title. Spine width, height and colour come from the book itself (same look every time); colours can be
 * varied or follow the app theme; six shelf materials drawn in 2D (no images to load).
 */
object ShelfView {
    interface Host { fun open(bookId: String); fun actions(bookId: String) }

    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, books: List<Book>, header: String, prefs: SharedPreferences, motion: Boolean, host: Host): View =
        ComposeView(activity).apply { setContent { AtlanteTheme(palette) { Bookcase(books, header, prefs, motion, host) } } }
}

enum class ShelfStyle(val label: String) { WOOD("Legno naturale"), WALNUT("Noce scuro"), OAK("Rovere chiaro"), METAL("Metallo"), MATERIAL("Material"), MINIMAL("Minimal") }

/** Spine geometry and colour, derived only from stable book data: identical after every restart. */
object Spines {
    private fun seed(b: Book): Int = (b.id.ifEmpty { b.title + b.author }).hashCode()
    /** Width in dp: from the page count when known (a fatter book has a wider spine), otherwise from the book's id. */
    fun width(b: Book): Float = if (b.totalPages > 0) (14f + b.totalPages / 22f).coerceIn(20f, 52f) else 22f + Math.floorMod(seed(b), 17)
    /** Height as a share of the shelf: 80%–100%. */
    fun height(b: Book): Float = 0.80f + Math.floorMod(seed(b) / 17, 21) / 100f
    val VARIED = listOf(0xFF7B2D26, 0xFF1F3A5F, 0xFF2F5233, 0xFFB5852B, 0xFF5B2A4E, 0xFF1E5E5A, 0xFF2B2B2B, 0xFFE8DCC4,
        0xFF8C3B2E, 0xFF44507A, 0xFF6B6B2A, 0xFFB0603A, 0xFF3C2A21, 0xFF9C8B6E).map { Color(it) }
    fun variedColor(b: Book) = VARIED[Math.floorMod(seed(b) / 7, VARIED.size)]
    fun ink(background: Color) = if (background.luminance() > 0.45f) Color(0xFF1B1B1B) else Color(0xFFF6F1E7)

    /** Books into shelves: as many spines as fit in the width, in the library's order. */
    fun rows(books: List<Book>, widthDp: Float, gap: Float = 2f): List<List<Book>> {
        val rows = ArrayList<List<Book>>(); var current = ArrayList<Book>(); var used = 0f
        for (b in books) {
            val w = width(b) + gap
            if (current.isNotEmpty() && used + w > widthDp) { rows += current; current = ArrayList(); used = 0f }
            current += b; used += w
        }
        if (current.isNotEmpty()) rows += current
        return rows
    }
}

private class ShelfColors(val back: Color, val backDark: Color, val top: Color, val front: Color, val frontDark: Color, val grain: Color?, val plank: Dp)

@Composable
private fun colorsFor(style: ShelfStyle): ShelfColors {
    val s = MaterialTheme.colorScheme
    return when (style) {
        ShelfStyle.WOOD -> ShelfColors(Color(0xFF4A2E1B), Color(0xFF2E1B10), Color(0xFFB07A45), Color(0xFF8B5A2B), Color(0xFF5E3A1A), Color(0x33000000), 16.dp)
        ShelfStyle.WALNUT -> ShelfColors(Color(0xFF2A1A11), Color(0xFF150C07), Color(0xFF6E4630), Color(0xFF4A2C1A), Color(0xFF2B180D), Color(0x40000000), 16.dp)
        ShelfStyle.OAK -> ShelfColors(Color(0xFFD9C3A0), Color(0xFFB79E78), Color(0xFFEBD6B0), Color(0xFFD2B48A), Color(0xFFA88C62), Color(0x22000000), 14.dp)
        ShelfStyle.METAL -> ShelfColors(Color(0xFF3A3F45), Color(0xFF24282C), Color(0xFFC9CFD5), Color(0xFF8E979F), Color(0xFF5B636B), null, 10.dp)
        ShelfStyle.MATERIAL -> ShelfColors(s.surfaceVariant, s.surfaceVariant.copy(alpha = 0.6f), s.primaryContainer, s.primary, s.primary.copy(alpha = 0.7f), null, 12.dp)
        ShelfStyle.MINIMAL -> ShelfColors(s.surface, s.surface, s.outline, s.outline, s.outline, null, 4.dp)
    }
}

@Composable
fun Bookcase(books: List<Book>, header: String, prefs: SharedPreferences, motion: Boolean, host: ShelfView.Host) {
    var style by remember { mutableStateOf(runCatching { ShelfStyle.valueOf(prefs.getString("shelfStyle", "WOOD")!!) }.getOrDefault(ShelfStyle.WOOD)) }
    var themed by remember { mutableStateOf(prefs.getBoolean("shelfThemeColors", false)) }
    val colors = colorsFor(style)
    val scheme = MaterialTheme.colorScheme
    val themeColors = listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer, scheme.inverseSurface)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rows = remember(books, maxWidth) { Spines.rows(books, maxWidth.value - 24f) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(header, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Look of the bookcase: one compact button; the options open over the shelf, apply at once
                    // (the menu stays open to compare) and a tap outside closes them.
                    var open by remember { mutableStateOf(false) }
                    Box(Modifier.padding(top = 8.dp)) {
                        FilterChip(open, { open = true }, label = { Text("Aspetto · ${style.label}") },
                            leadingIcon = { ShelfSwatch(style) }, trailingIcon = { Text(if (open) "▴" else "▾") },
                            modifier = Modifier.semantics { contentDescription = "Aspetto dello scaffale: ${style.label}, ${if (themed) "colori del tema" else "colori vari"}" })
                        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 380.dp)) {
                            MenuLabel("Colori dei dorsi")
                            ShelfOption("Colori vari", !themed, { SpinesSwatch(Spines.VARIED) }) { themed = false; prefs.edit().putBoolean("shelfThemeColors", false).apply() }
                            ShelfOption("Colori del tema", themed, { SpinesSwatch(themeColors) }) { themed = true; prefs.edit().putBoolean("shelfThemeColors", true).apply() }
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            MenuLabel("Scaffale")
                            ShelfStyle.entries.forEach { st ->
                                ShelfOption(st.label, style == st, { ShelfSwatch(st) }) { style = st; prefs.edit().putString("shelfStyle", st.name).apply() }
                            }
                        }
                    }
                }
            }
            itemsIndexed(rows, key = { i, row -> "$i:" + row.first().id }) { _, row ->
                Shelf(row, colors, motion, host) { b -> if (themed) themeColors[Math.floorMod(b.id.hashCode() / 7, themeColors.size)] else Spines.variedColor(b) }
            }
            if (rows.isEmpty()) item { Shelf(emptyList(), colors, motion, host) { Color.Gray } }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ShelfOption(label: String, selected: Boolean, swatch: @Composable () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick, leadingIcon = swatch,
        trailingIcon = { if (selected) Text("✓", color = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.semantics { this.selected = selected })
}

@Composable
private fun SpinesSwatch(colors: List<Color>) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width / 4
        for (i in 0 until 4) drawRect(colors[i % colors.size], Offset(i * w, 0f), Size(w * 0.85f, size.height))
    }
}

@Composable
private fun ShelfSwatch(style: ShelfStyle) {
    val c = colorsFor(style)
    Canvas(Modifier.size(18.dp)) {
        drawRect(c.back); drawRect(c.front, Offset(0f, size.height * 0.62f), Size(size.width, size.height * 0.38f))
    }
}

private val SHELF_HEIGHT = 168.dp

@Composable
private fun Shelf(row: List<Book>, c: ShelfColors, motion: Boolean, host: ShelfView.Host, color: (Book) -> Color) {
    Column(Modifier.fillMaxWidth()) {
        // Back wall with the books standing on the plank.
        Box(Modifier.fillMaxWidth().height(SHELF_HEIGHT)) {
            Canvas(Modifier.matchParentSize()) {
                drawRect(Brush.verticalGradient(listOf(c.backDark, c.back, c.back)))
                c.grain?.let { grain(it, size.width, size.height, 5) }
            }
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { b -> Spine(b, color(b), motion, host) }
            }
        }
        // The plank: a lighter top face, the front edge and a soft shadow.
        Canvas(Modifier.fillMaxWidth().height(c.plank + 8.dp)) {
            val plank = c.plank.toPx(); val top = (plank * 0.3f).coerceAtLeast(2f)
            drawRect(c.top, Offset.Zero, Size(size.width, top))
            drawRect(Brush.verticalGradient(listOf(c.front, c.frontDark), 0f + top, plank), Offset(0f, top), Size(size.width, plank - top))
            c.grain?.let { g -> for (i in 0..2) drawLine(g, Offset(0f, top + (plank - top) * (0.3f + i * 0.25f)), Offset(size.width, top + (plank - top) * (0.35f + i * 0.2f)), 1f) }
            drawRect(Brush.verticalGradient(listOf(Color(0x55000000), Color.Transparent), plank, size.height), Offset(0f, plank), Size(size.width, size.height - plank))
        }
    }
}

private fun DrawScope.grain(color: Color, w: Float, h: Float, lines: Int) {
    for (i in 0 until lines) {
        val y = h * (i + 0.5f) / lines
        drawLine(color, Offset(0f, y), Offset(w, y + h * 0.04f * ((i % 3) - 1)), 1.5f)
    }
}

@Composable
private fun Spine(b: Book, base: Color, motion: Boolean, host: ShelfView.Host) {
    val measurer = rememberTextMeasurer()
    val ink = Spines.ink(base)
    var lifted by remember { mutableStateOf(false) }
    val lift by animateDpAsState(if (lifted) (-10).dp else 0.dp, if (motion) tween(160) else tween(0), label = "lift")
    LaunchedEffect(lifted) { if (lifted) { delay(if (motion) 190 else 0); host.open(b.id); lifted = false } }
    val width = Spines.width(b).dp
    val height = SHELF_HEIGHT * Spines.height(b) - 8.dp
    val state = when (b.status) { Book.READ -> "letto"; Book.READING -> "in lettura"; Book.PAUSED -> "in pausa"; else -> "da leggere" }
    Canvas(Modifier.offset(y = lift).size(width, height)
        .semantics(mergeDescendants = true) {
            contentDescription = "${b.title}, di ${b.author}, $state"; role = Role.Button
            onClick("Apri") { lifted = true; true }
            customActions = listOf(CustomAccessibilityAction("Azioni del libro") { host.actions(b.id); true })
        }
        .pointerInput(b.id) { detectTapGestures(onTap = { lifted = true }, onLongPress = { host.actions(b.id) }) }) {
        val w = size.width; val h = size.height
        // Rounded spine: darker at the edges, a light stripe of "gloss", bands at top and bottom.
        drawRect(Brush.horizontalGradient(listOf(base.darken(0.35f), base, base.lighten(0.12f), base, base.darken(0.4f))))
        drawRect(ink.copy(alpha = 0.35f), Offset(0f, h * 0.07f), Size(w, 1.5f)); drawRect(ink.copy(alpha = 0.35f), Offset(0f, h * 0.09f), Size(w, 1f))
        drawRect(ink.copy(alpha = 0.35f), Offset(0f, h * 0.91f), Size(w, 1.5f)); drawRect(ink.copy(alpha = 0.35f), Offset(0f, h * 0.93f), Size(w, 1f))
        if (b.status == Book.READING) drawRect(ink.copy(alpha = 0.8f), Offset(w * 0.2f, h * 0.035f), Size(w * 0.6f, 3f))
        // Title only, reading from bottom to top as on Italian spines; smaller when long, then shortened with "…".
        val room = (h * 0.78f).toInt(); val lines = if (w > 34.dp.toPx()) 2 else 1
        var layout = measurer.measure(b.title, TextStyle(color = ink, fontSize = 13.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
            overflow = TextOverflow.Ellipsis, maxLines = lines, constraints = Constraints(maxWidth = room))
        if (layout.hasVisualOverflow) layout = measurer.measure(b.title, TextStyle(color = ink, fontSize = 11.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
            overflow = TextOverflow.Ellipsis, maxLines = lines, constraints = Constraints(maxWidth = room))
        rotate(-90f, Offset(w / 2, h / 2)) {
            // In the rotated frame the spine is h wide and w tall, centred on the same point.
            drawText(layout, topLeft = Offset(w / 2 - layout.size.width / 2f, h / 2 - layout.size.height / 2f))
        }
    }
}

private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)
private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)
