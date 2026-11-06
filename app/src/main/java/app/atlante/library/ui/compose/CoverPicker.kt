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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Scegli copertina online": every cover found for this book, with source and size; nothing changes until you tap one. */
object CoverPickerView {
    fun interface Chosen { fun chosen(bytes: ByteArray) }

    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, book: Book, onChosen: Chosen, onClose: Runnable): View = ComposeView(activity).apply {
        setContent { AtlanteTheme(palette) { CoverPicker(book, { onChosen.chosen(it) }) { onClose.run() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverPicker(book: Book, onChosen: (ByteArray) -> Unit, onClose: () -> Unit) {
    var covers by remember { mutableStateOf<List<CoverSearch.Cover>?>(null) }
    LaunchedEffect(book.id) { covers = withContext(Dispatchers.IO) { runCatching { CoverSearch.all(book) }.getOrDefault(emptyList()) } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                TopAppBar(title = { Text("Copertine per «${book.title}»", maxLines = 1) }, navigationIcon = { TextButton(onClick = onClose) { Text("Chiudi") } })
                val list = covers
                when {
                    list == null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(); Spacer(Modifier.height(12.dp))
                        Text("Cerco su Open Library, Google Books e Apple Books…", style = MaterialTheme.typography.bodyMedium)
                    }
                    list.isEmpty() -> Text("Nessuna copertina trovata per questo titolo e autore. Controlla ISBN, titolo e autore nella scheda, oppure scegli un’immagine dalla galleria.",
                        Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                    else -> {
                        Text("Tocca la copertina da usare. «Stessa edizione» vuol dire stesso ISBN.", Modifier.padding(horizontal = 20.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyVerticalGrid(GridCells.Adaptive(140.dp), contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(list) { c -> CoverChoice(c) { onChosen(c.bytes) } }
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
    Column(Modifier.clickable(onClick = onTap).semantics { contentDescription = "Copertina da ${c.source}, ${c.width} per ${c.height} pixel" }) {
        Surface(Modifier.fillMaxWidth().aspectRatio(0.66f), shape = RoundedCornerShape(8.dp),
            border = if (c.exact) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null) {
            image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Text(c.source, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        Text("${c.width}×${c.height}" + if (c.exact) " · stessa edizione" else "", style = MaterialTheme.typography.bodySmall,
            color = if (c.exact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
