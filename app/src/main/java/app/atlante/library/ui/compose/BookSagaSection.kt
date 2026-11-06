package app.atlante.library.ui.compose

import android.view.View
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.atlante.library.model.Book
import app.atlante.library.online.OnlineCatalog
import app.atlante.library.saga.*
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.saga.db.BookLink
import app.atlante.library.saga.db.SeriesSummary
import app.atlante.library.ui.Covers
import app.atlante.library.ui.ThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the book page offers to the saga section (implemented by MainActivity). */
interface SagaHost {
    fun openBook(bookId: String)
    /** Opens the online search with this text: suggestions are never added to the library by themselves. */
    fun searchOnline(query: String)
    /** Writes the saga in the book's record; [chosen] = picked by the user (otherwise only fills an empty field). */
    fun applySeries(bookId: String, name: String, ordinal: String?, chosen: Boolean)
    fun coverFile(bookId: String): String?
    fun library(): List<Book>
}

object BookSagaView {
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, book: Book, host: SagaHost): View = ComposeView(activity).apply {
        val dao = AtlanteDatabase.get(activity).saga()
        val repo = SagaRepository(dao)
        val suggestions = Suggestions(dao, host::library)
        SuggestionCovers.suggestions = suggestions
        setContent { AtlanteTheme(palette) { BookSagaSection(book, repo, suggestions, dao, host) } }
    }
}

@Composable
fun BookSagaSection(book: Book, repo: SagaRepository, suggestions: Suggestions, dao: app.atlante.library.saga.db.SagaDao, host: SagaHost) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<BookLink?>(null) }
    var shelf by remember { mutableStateOf<SagaShelf?>(null) }
    var loading by remember { mutableStateOf(true) }
    var rejected by remember { mutableStateOf(false) }
    var byAuthor by remember { mutableStateOf<List<ShelfItem>?>(null) }
    var interest by remember { mutableStateOf<List<ShelfItem>?>(null) }
    var fullSaga by remember { mutableStateOf(false) }
    var fullAuthor by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<String?>(null) }   // "title", "create", "pick"

    suspend fun show(l: BookLink?) {
        link = l; rejected = false
        shelf = l?.takeIf { it.status != "none" }?.let { runCatching { suggestions.saga(it) }.getOrNull() }
        val s = shelf
        if (l != null && s?.series != null && l.status in setOf("auto", "confirmed", "manual"))
            host.applySeries(book.id, s.series.name, s.items.firstOrNull { it.ownedBookId == book.id }?.ordinal, false)
    }
    fun run(block: suspend () -> BookLink?) = scope.launch { loading = true; show(runCatching { block() }.getOrNull()); loading = false }

    LaunchedEffect(book.id) {
        show(runCatching { repo.recognize(book) }.getOrNull()); loading = false
        // Progressive loading: each carousel appears when its data arrives.
        byAuthor = runCatching { suggestions.byAuthor(book, shelf?.items ?: emptyList()) }.getOrDefault(emptyList())
        interest = runCatching { suggestions.mayInterest(book, (shelf?.items ?: emptyList()) + (byAuthor ?: emptyList())) }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val l = link; val s = shelf
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp))
                Text("Cerco la saga su Wikidata e Open Library…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            l?.status == "none" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Senza saga, per tua scelta", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { rejected = true; link = l.copy(status = "unidentified") }) { Text("Cambia") }
            }
            s?.series != null && !rejected -> {
                if (l?.status == "to_confirm") ConfirmCard(s, book, l,
                    onYes = { run { repo.setByUser(book.id, l.workId, l.seriesId, "confirmed"); dao.link(book.id) } },
                    onNo = { rejected = true })
                SagaCarousel(s, book, host) { fullSaga = true }
            }
            else -> NoSagaCard(l, onRetry = { run { repo.recognize(book, force = true) } }, onAsk = { ask = it },
                onNone = { run { repo.setByUser(book.id, l?.workId, null, "none"); dao.link(book.id) } })
        }
        val authorItems = byAuthor
        if (book.author != "Autore sconosciuto") {
            if (authorItems == null) LoadingRow("Altri libri di ${book.author.substringBefore(" & ")}")
            else if (authorItems.isNotEmpty()) {
                Carousel("Altri libri di ${book.author.substringBefore(" & ")}", authorItems, host)
                TextButton(onClick = { fullAuthor = true }) { Text("Visualizza tutti i libri dell’autore") }
            }
        }
        interest?.takeIf { it.isNotEmpty() }?.let {
            Carousel("Potrebbero interessarti", it, host, "Stesso genere (${Genres.normalize(book.genre)}), i più pubblicati. Nessuno viene aggiunto da solo.")
        }
    }

    if (fullSaga) shelf?.let { FullSaga(it, book, host) { fullSaga = false } }
    if (fullAuthor) byAuthor?.let { FullList("Libri di ${book.author.substringBefore(" & ")}", it, host) { fullAuthor = false } }
    when (ask) {
        "title" -> TextAsk("Cerca per titolo originale", "Titolo originale (es. The Final Empire)", "Cerca", { ask = null }) { t ->
            ask = null; run { repo.recognize(Book().apply { id = book.id; title = t; author = book.author; isbn = "" }, force = true) }
        }
        "create" -> TextAsk("Crea una saga", "Nome della saga", "Crea", { ask = null }) { name ->
            ask = null; run { val id = repo.createManualSeries(name); repo.setByUser(book.id, link?.workId, id, "manual"); host.applySeries(book.id, name, null, true); dao.link(book.id) }
        }
        "pick" -> PickSaga(dao, { ask = null }) { picked ->
            ask = null; run { repo.setByUser(book.id, link?.workId, picked.series.id, "manual"); host.applySeries(book.id, picked.series.name, null, true); dao.link(book.id) }
        }
    }
}

@Composable
private fun ConfirmCard(s: SagaShelf, book: Book, l: BookLink, onYes: () -> Unit, onNo: () -> Unit) {
    val ordinal = s.items.firstOrNull { it.ownedBookId == book.id }?.ordinal
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("È questa la saga?", style = MaterialTheme.typography.titleMedium)
            Text("Sembra " + (ordinal?.let { "il n. $it di " } ?: "parte di ") + "«${s.series!!.name}». Una sola fonte lo conferma (affidabilità ${l.score}/100).",
                style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = onYes) { Text("Sì, è questa") }; Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onNo) { Text("No") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoSagaCard(l: BookLink?, onRetry: () -> Unit, onAsk: (String) -> Unit, onNone: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Saga non identificata", style = MaterialTheme.typography.titleMedium)
            Text(when (l?.status) {
                "no_saga" -> "L’opera è stata riconosciuta, ma nessuna fonte indica una saga. Non per questo è un libro autoconclusivo."
                null -> "Non è stato possibile cercare la saga. Controlla la connessione e riprova."
                else -> "Nessuna fonte ha riconosciuto l’opera con sicurezza: non è stata associata nessuna saga."
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onRetry, label = { Text("Riprova") })
                AssistChip(onClick = { onAsk("title") }, label = { Text("Cerca per titolo originale") })
                AssistChip(onClick = { onAsk("pick") }, label = { Text("Scegli una saga") })
                AssistChip(onClick = { onAsk("create") }, label = { Text("Crea una saga") })
                AssistChip(onClick = onNone, label = { Text("Lascia senza saga") })
            }
        }
    }
}

@Composable
private fun SagaCarousel(s: SagaShelf, book: Book, host: SagaHost, onFull: () -> Unit) {
    val others = s.items.filter { it.ownedBookId != book.id }
    Column {
        Text("Altri libri della saga", style = MaterialTheme.typography.titleMedium)
        Text("${s.series!!.name} · ${s.owned} di ${s.total} in biblioteca" + (s.universe?.let { " · universo $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.series.possiblyIncomplete) Text("Elenco potenzialmente incompleto", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        if (others.isNotEmpty()) Shelf(others, host)
        TextButton(onClick = onFull) { Text("Visualizza saga completa") }
    }
}

@Composable
private fun Carousel(title: String, items: List<ShelfItem>, host: SagaHost, note: String? = null) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Shelf(items, host)
    }
}

@Composable
private fun LoadingRow(title: String) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) { Surface(Modifier.size(96.dp, 144.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {} }
        }
    }
}

@Composable
private fun Shelf(items: List<ShelfItem>, host: SagaHost) {
    LazyRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.ownedBookId ?: (it.title + it.ordinal) }) { ShelfCard(it, host) }
    }
}

@Composable
private fun ShelfCard(item: ShelfItem, host: SagaHost) {
    val open = { if (item.ownedBookId != null) host.openBook(item.ownedBookId) else host.searchOnline(item.query) }
    val state = when { item.read -> "Letto"; item.ownedBookId != null -> "In biblioteca"; else -> "Non in biblioteca" }
    Column(Modifier.width(96.dp).clickable(onClick = open).semantics { contentDescription = "${item.title}, $state" }) {
        Cover(item, host, Modifier.size(96.dp, 144.dp).alpha(if (item.ownedBookId == null) 0.75f else 1f))
        Text((item.ordinal?.let { "#$it  " } ?: "") + item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
        Text(state, style = MaterialTheme.typography.labelSmall,
            color = if (item.ownedBookId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private object CoverMemory : LruCache<String, ImageBitmap>(48)

@Composable
private fun Cover(item: ShelfItem, host: SagaHost, modifier: Modifier) {
    val key = item.ownedBookId ?: item.coverUrl ?: item.coverWork ?: item.title
    var image by remember(key) { mutableStateOf(CoverMemory.get(key)) }
    LaunchedEffect(key) {
        if (image == null) image = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = when {
                    item.ownedBookId != null -> host.coverFile(item.ownedBookId)?.let { Covers.decode(it, null, 320) }
                    else -> {
                        val url = item.coverUrl ?: item.coverWork?.let { w -> SuggestionCovers.forWork(w) }
                        url?.let { u -> OnlineCatalog.request(u, 2 * 1024 * 1024).let { b -> Covers.decode(null, b, 320) } }
                    }
                }
                bitmap?.asImageBitmap()?.also { CoverMemory.put(key, it) }
            }.getOrNull()
        }
    }
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (item.ownedBookId == null) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null) {
        val img = image
        if (img != null) Image(img, null, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Text(item.title, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Covers of saga volumes known only by their Open Library work (set by BookSagaView). */
object SuggestionCovers {
    @Volatile var suggestions: Suggestions? = null
    suspend fun forWork(olWork: String): String? = suggestions?.workCover(olWork)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreen(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { TextButton(onClick = onClose) { Text("Chiudi") } })
                content()
            }
        }
    }
}

@Composable
private fun FullSaga(s: SagaShelf, book: Book, host: SagaHost, onClose: () -> Unit) {
    FullScreen(s.series!!.name, onClose) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("${s.owned} di ${s.total} in biblioteca · ${s.items.count { it.read }} letti", style = MaterialTheme.typography.bodyMedium)
                s.universe?.let { Text("Fa parte dell’universo $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (s.subseries.isNotEmpty()) Text("Sotto-serie: " + s.subseries.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.series.possiblyIncomplete) Text("Elenco potenzialmente incompleto: la fonte non numera tutti i volumi o mancano dei numeri.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            items(s.items) { v -> VolumeRow(v, v.ownedBookId == book.id, host) }
        }
    }
}

@Composable
private fun FullList(title: String, items: List<ShelfItem>, host: SagaHost, onClose: () -> Unit) {
    FullScreen(title, onClose) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items) { VolumeRow(it, false, host) }
        }
    }
}

@Composable
private fun VolumeRow(v: ShelfItem, current: Boolean, host: SagaHost) {
    val color = when { v.read -> MaterialTheme.colorScheme.primary; v.ownedBookId != null -> MaterialTheme.colorScheme.secondary; else -> MaterialTheme.colorScheme.outline }
    Row(Modifier.fillMaxWidth().clickable { if (v.ownedBookId != null) host.openBook(v.ownedBookId) else host.searchOnline(v.query) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(36.dp), shape = CircleShape, color = if (v.ownedBookId != null) color else MaterialTheme.colorScheme.surface, border = BorderStroke(2.dp, color)) {
            Box(contentAlignment = Alignment.Center) {
                Text(v.ordinal ?: "–", style = MaterialTheme.typography.labelLarge,
                    color = if (v.ownedBookId != null) MaterialTheme.colorScheme.onPrimary else color)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title + if (current) "  · questo libro" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
            Text(listOfNotNull(v.author.ifBlank { null }, v.year, when { v.read -> "Letto"; v.ownedBookId != null -> "In biblioteca"; else -> "Mancante" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TextAsk(title: String, label: String, action: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onDone(text.trim()) }) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

@Composable
private fun PickSaga(dao: app.atlante.library.saga.db.SagaDao, onDismiss: () -> Unit, onPick: (SeriesSummary) -> Unit) {
    var all by remember { mutableStateOf<List<SeriesSummary>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { all = dao.allSeries() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Scegli una saga") },
        text = {
            Column {
                OutlinedTextField(filter, { filter = it }, label = { Text("Cerca") }, singleLine = true)
                val shown = all.filter { filter.isBlank() || it.series.name.contains(filter, true) }
                if (shown.isEmpty()) Text("Nessuna saga salvata. Puoi crearne una.", Modifier.padding(top = 12.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown) { s ->
                        Text("${s.series.name}  (${s.volumes})", Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}
