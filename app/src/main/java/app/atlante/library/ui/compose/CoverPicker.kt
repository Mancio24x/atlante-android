package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.atlante.library.model.Book
import app.atlante.library.online.CoverSearch
import app.atlante.library.ui.Covers
import app.atlante.library.ui.ThemePalette
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * "Cambia copertina": every cover found for this book (Hardcover's editions in Italian, English and the original
 * language, Open Library, Apple Books), each with source, language and publisher. They appear as they arrive
 * (six downloads at a time). Nothing changes until you tap one; the one you choose stays: the app never replaces it.
 */
object CoverPickerView {
    fun interface Chosen { fun chosen(bytes: ByteArray, origin: String) }

    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, book: Book, onChosen: Chosen, onClose: Runnable): View = create(activity, palette, book, emptyList(), onChosen, onClose)

    /** [local]: covers already saved with this work (alternatives kept from merged records and editions). */
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, book: Book, local: List<java.io.File>, onChosen: Chosen, onClose: Runnable): View = ComposeView(activity).apply {
        setContent { AtlanteTheme(palette) { CoverPicker(book, local, { c -> onChosen.chosen(c.bytes, c.origin) }) { onClose.run() } } }
    }
}

/** The picker's filters. */
enum class CoverFilter(val label: String) { ALL("Tutte"), ITALIAN("Italiane"), ORIGINAL("Originali e inglesi"), OTHER("Altre lingue") }

fun CoverSearch.Cover.fits(f: CoverFilter) = when (f) {
    CoverFilter.ALL -> true
    CoverFilter.ITALIAN -> italian
    CoverFilter.ORIGINAL -> original || language == "eng"
    CoverFilter.OTHER -> !italian && !original && language != "eng" && language.isNotEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverPicker(book: Book, local: List<java.io.File> = emptyList(), onChosen: (CoverSearch.Cover) -> Unit, onClose: () -> Unit) {
    val covers = remember(book.id) { mutableStateListOf<CoverSearch.Cover>() }
    var searching by remember(book.id) { mutableStateOf(true) }
    // The preferred kind (Impostazioni › Copertine) is the first filter once one such cover is there; a tap decides.
    val preferred = when (CoverSearch.prefer) { CoverSearch.ITALIAN -> CoverFilter.ITALIAN; CoverSearch.ORIGINAL -> CoverFilter.ORIGINAL; else -> CoverFilter.ALL }
    var chosenFilter by remember { mutableStateOf<CoverFilter?>(null) }
    val filter = chosenFilter ?: if (covers.any { it.fits(preferred) }) preferred else CoverFilter.ALL
    LaunchedEffect(book.id) {
        withContext(Dispatchers.IO) {
            val slots = Semaphore(6); val sizes = HashSet<Int>()
            for (f in local) runCatching {
                val bytes = f.readBytes(); val (w, h) = CoverSearch.dimensions(bytes) ?: return@runCatching
                val cover = CoverSearch.Cover(bytes, "Le tue copertine", w, h, false, detail = "di un’altra edizione unita")
                withContext(Dispatchers.Main) { if (sizes.add(bytes.size)) covers.add(cover) }
            }
            coroutineScope {
                for (c in CoverSearch.candidates(book).distinctBy { it.url }.take(48)) launch {
                    val cover = slots.withPermit { runCatching { CoverSearch.download(c) }.getOrNull() } ?: return@launch
                    withContext(Dispatchers.Main) { if (sizes.add(cover.bytes.size)) covers.add(cover) }   // the same image from two addresses once
                }
            }
        }
        searching = false
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                TopAppBar(title = { Text("Cambia copertina", maxLines = 1) }, navigationIcon = { TextButton(onClick = onClose) { Text("Chiudi") } })
                Text("«${book.title}». Tocca la copertina da usare: resta tua, l’app non la sostituisce più.", Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverFilter.entries.forEach { f ->
                        val n = covers.count { it.fits(f) }
                        if (f == CoverFilter.ALL || n > 0 || f == filter) FilterChip(filter == f, { chosenFilter = f }, label = { Text("${f.label} · $n") })
                    }
                }
                val shown = covers.filter { it.fits(filter) }
                when {
                    shown.isEmpty() && searching -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(); Spacer(Modifier.height(12.dp))
                        Text("Cerco su Hardcover, Open Library e Apple Books…", style = MaterialTheme.typography.bodyMedium)
                    }
                    shown.isEmpty() && covers.isNotEmpty() -> Column(Modifier.padding(24.dp)) {
                        Text("Nessuna copertina «${filter.label.lowercase()}» per questo libro.", style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { chosenFilter = CoverFilter.ALL }) { Text("Mostra tutte (${covers.size})") }
                    }
                    shown.isEmpty() -> Text("Nessuna copertina trovata per questo titolo e autore. Controlla ISBN, titolo e autore nella scheda, oppure scegli un’immagine dalla galleria.",
                        Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                    else -> LazyVerticalGrid(GridCells.Adaptive(140.dp), contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(shown, key = { it.bytes.size.toString() + it.source + it.width }) { c -> CoverChoice(c) { onChosen(c) } }
                        if (searching) item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                                Text("Cerco altre copertine…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverChoice(c: CoverSearch.Cover, onTap: () -> Unit) {
    val image = remember(c) { Covers.decode(null, c.bytes, 480)?.asImageBitmap() }
    val kind = when { c.exact -> "stessa edizione"; c.original -> "copertina principale"; else -> "" }
    Column(Modifier.clickable(onClick = onTap).semantics { contentDescription = "Copertina ${c.origin}" + (if (kind.isEmpty()) "" else ", $kind") + ", ${c.width} per ${c.height} pixel" }) {
        Surface(Modifier.fillMaxWidth().aspectRatio(0.66f), shape = RoundedCornerShape(8.dp),
            border = if (c.exact) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null) {
            image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Text(listOf(c.source, c.languageName).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        if (c.detail.isNotBlank()) Text(c.detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${c.width}×${c.height}" + if (kind.isEmpty()) "" else " · $kind", style = MaterialTheme.typography.bodySmall,
            color = if (c.exact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
