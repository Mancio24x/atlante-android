package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.atlante.library.saga.CacheManager
import app.atlante.library.saga.Discover
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.ui.ThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

object DiscoverView {
    fun interface Link { fun open(url: String) }

    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, host: SagaHost, onLink: Link, onCollections: Runnable): View = ComposeView(activity).apply {
        val discover = Discover(AtlanteDatabase.get(activity).saga(), host::library)
        SuggestionCovers.cache = CacheManager.get(activity).covers
        setContent { AtlanteTheme(palette) { DiscoverScreen(discover, host, onLink::open, onCollections::run) } }
    }
}

/**
 * What Scopri showed during this session: coming back from a book (or switching genre back) shows it at once,
 * with the same genre, page and scroll position, instead of the empty grid.
 */
private object DiscoverSession {
    var genre: Discover.Genre? = null
    var page = 0
    var scroll = 0
    val shown = android.util.LruCache<Pair<String?, Int>, List<Discover.Pick>>(16)
}

/**
 * Scopri: a grid of six covers, the most read books (with an Italian edition) or, with a genre chosen,
 * six books of that genre. Tapping a cover opens the book's sheet (found online): nothing is added by itself.
 */
@Composable
fun DiscoverScreen(discover: Discover, host: SagaHost, onLink: (String) -> Unit, onCollections: () -> Unit) {
    var genre by remember { mutableStateOf(DiscoverSession.genre) }
    var page by remember { mutableIntStateOf(DiscoverSession.page) }
    var picks by remember { mutableStateOf(DiscoverSession.shown.get(genre?.subject to page)) }
    var attempt by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState(DiscoverSession.scroll)
    DisposableEffect(Unit) { onDispose { DiscoverSession.scroll = scroll.value } }
    LaunchedEffect(genre, page, attempt) {
        DiscoverSession.genre = genre; DiscoverSession.page = page
        val key = genre?.subject to page
        // Already seen in this session: shown at once, no request (a new genre cancels the old one's work).
        DiscoverSession.shown.get(key)?.let { picks = it; return@LaunchedEffect }
        picks = null
        DiscoverPerf.start()
        val found = try { discover.picks(genre, page) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
        picks = found
        DiscoverPerf.list(found.size)
        if (found.isNotEmpty()) DiscoverSession.shown.put(key, found)
        // Most picks already carry their Italian edition; the rest look for it one by one (children of this
        // effect: a new genre or page cancels them).
        coroutineScope {
            // The next six are ready before "Altri 6" is tapped: their list comes from the same cached answer,
            // their covers go to the disk cache (a few at a time, after this page's covers have started).
            if (discover.hasMore(found.size, page)) launch(Dispatchers.IO) {
                val next = runCatching { discover.picks(genre, page + 1) }.getOrDefault(emptyList())
                next.mapNotNull { it.coverUrl }.forEach { u -> runCatching { SuggestionCovers.bytes(u) } }
            }
            found.forEachIndexed { i, p -> if (!p.italian) launch {
                val it = try { discover.italian(p) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { p }
                if (it == p) return@launch
                picks = picks?.toMutableList()?.also { list -> if (i < list.size && list[i].workKey == p.workKey) list[i] = it }
                picks?.let { list -> DiscoverSession.shown.put(key, list) }
            } }
        }
    }

    val colors = MaterialTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Bookmarks shown on: saved here, or already in the library (so a tap never takes a library book away).
    var saved by remember { mutableStateOf(setOf<String>()) }
    var unsaved by remember { mutableStateOf(setOf<String>()) }
    val isSaved: (Discover.Pick) -> Boolean = { p -> p.workKey in saved || (p.workKey !in unsaved && host.inLibrary(p.title, p.author)) }
    val setSaved: (Discover.Pick, Boolean) -> Unit = { p, on -> if (on) { saved = saved + p.workKey; unsaved = unsaved - p.workKey } else { saved = saved - p.workKey; unsaved = unsaved + p.workKey } }
    val toggle: (Discover.Pick) -> Unit = { p ->
        val wasInLibrary = host.inLibrary(p.title, p.author)
        val on = host.setToRead(p.title, p.author, p.coverUrl, !isSaved(p))
        setSaved(p, on)
        if (on && !wasInLibrary) scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar("«${p.title}» in Da leggere", "Annulla", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed)
                setSaved(p, host.setToRead(p.title, p.author, p.coverUrl, false))
        }
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 28.dp)) {
        // Design 4b: a real search field first.
        Surface(onClick = { host.searchOnline("") }, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = colors.surfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp)) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.ui.res.painterResource(app.atlante.library.R.drawable.ic_search), null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                Text("Cerca titolo, autore o ISBN", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
        LazyRow(Modifier.padding(top = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
            item { GenreChip("Popolari", genre == null) { genre = null; page = 0 } }
            items(Discover.GENRES) { g -> GenreChip(g.label, genre == g) { genre = g; page = 0 } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(genre?.label ?: "I più popolari", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif))
            Text("pagina ${page + 1}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Text(if (genre == null) "Popolari tra i lettori di Open Library e Hardcover, con un’edizione italiana. Non sono classifiche di vendita." else "I più popolari del genere tra i lettori, un libro per autore.",
            Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 14.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)

        // The fade is between loading / empty / grid only: a cover switching to its Italian edition must not
        // fade (and recompose) the whole grid.
        val list = picks
        Crossfade(when { list == null -> 0; list.isEmpty() -> 1; else -> 2 }, label = "grid") { state ->
            when (state) {
                0 -> Grid(List(6) { null }, host, isSaved, toggle)
                1 -> Column {
                    Text("Nessun consiglio disponibile: serve la connessione a Internet.", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { attempt++ }) { Text("Riprova") }
                }
                else -> Grid(picks ?: emptyList(), host, isSaved, toggle)
            }
        }
        val more = picks?.let { discover.hasMore(it.size, page) } == true
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page > 0) OutlinedIconButton(onClick = { page-- }, Modifier.size(48.dp).semantics { contentDescription = "Precedenti" }) { Text("‹", style = MaterialTheme.typography.titleLarge, color = colors.primary) }
            if (more) FilledTonalButton(onClick = { page++ }, Modifier.weight(1f).height(48.dp)) { Text("Mostra altri 6") }
        }

        // Charts and collections as rows.
        OutlinedCard(Modifier.fillMaxWidth().padding(top = 24.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainer)) {
            Text("CLASSIFICHE ITALIANE", Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = colors.primary)
            listOf(Triple("IBS", "Classifica libri, aggiornata ogni giorno", "https://www.ibs.it/classifica/libri"),
                Triple("laFeltrinelli", "I più venduti in libreria", "https://www.lafeltrinelli.it/classifica/libri")).forEach { (name, sub, url) ->
                HorizontalDivider(color = colors.outlineVariant)
                Row(Modifier.fillMaxWidth().clickable { onLink(url) }.padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 40.dp)
                    .semantics { contentDescription = "$name ↗, $sub" }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(name, style = MaterialTheme.typography.titleSmall); Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                    Icon(androidx.compose.ui.res.painterResource(app.atlante.library.R.drawable.ic_open_in_new), null, Modifier.size(20.dp), tint = colors.primary)
                }
            }
        }
        OutlinedCard(onClick = onCollections, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainer)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Le tue collezioni", style = MaterialTheme.typography.titleMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif))
                    Text(if (saved.isEmpty()) "Saghe, autori e liste che hai creato" else "${saved.size} " + (if (saved.size == 1) "libro salvato" else "libri salvati") + " da Scopri",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Icon(androidx.compose.ui.res.painterResource(app.atlante.library.R.drawable.ic_chevron_right), null, tint = colors.primary)
            }
        }
        Text("Consigli dai lettori di Open Library. Niente viene aggiunto da solo.", Modifier.fillMaxWidth().padding(top = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/** A genre chip: filled with the ink colour when chosen, a hairline otherwise. */
@Composable
private fun GenreChip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = if (on) colors.onSurface else androidx.compose.ui.graphics.Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) colors.onSurface else colors.outlineVariant), modifier = Modifier.height(36.dp)
            .semantics { contentDescription = label + if (on) ", selezionato" else "" }) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) colors.surface else colors.onSurface)
        }
    }
}

@Composable
private fun Grid(items: List<Discover.Pick?>, host: SagaHost, isSaved: (Discover.Pick) -> Boolean, onSave: (Discover.Pick) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { p -> PickCard(p, host, p != null && isSaved(p), onSave, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PickCard(p: Discover.Pick?, host: SagaHost, saved: Boolean, onSave: (Discover.Pick) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            val url = p?.coverUrl
            // Same book in this place (workKey): the Italian cover replaces the first one only when it is ready.
            BookCover(p?.workKey, url, p?.title ?: "", Modifier.fillMaxSize().then(if (p != null) Modifier.clickable { host.openWork(p.title, p.author, p.workKey) }
                .semantics { contentDescription = "${p.title} di ${p.author}" } else Modifier)) {
                url?.let { u -> SuggestionCovers.bytes(u)?.let { CoverImages.decode(it, null, 360) } }?.also { DiscoverPerf.cover() }
            }
            if (p != null) Box(Modifier.align(Alignment.BottomEnd).size(44.dp).clickable { onSave(p) }
                .semantics { contentDescription = if (saved) "Togli da Da leggere" else "Aggiungi a Da leggere" }, contentAlignment = Alignment.Center) {
                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = if (saved) colors.primary else colors.surfaceContainer, shadowElevation = 2.dp, modifier = Modifier.size(32.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(androidx.compose.ui.res.painterResource(if (saved) app.atlante.library.R.drawable.ic_bookmark_filled else app.atlante.library.R.drawable.ic_bookmark), null,
                            Modifier.size(18.dp), tint = if (saved) colors.onPrimary else colors.primary)
                    }
                }
            }
        }
        if (p != null) {
            Text(p.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(p.author, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Real timings of Scopri on the phone (logcat, tag AtlantePerf): list, first cover, six and twelve covers, and the
 * cover cache's counters. Read by scripts/capture-perf.py on the emulator; a few log lines, nothing else.
 */
object DiscoverPerf {
    @Volatile private var t0 = 0L
    private val covers = java.util.concurrent.atomic.AtomicInteger()
    fun start() { t0 = System.nanoTime(); covers.set(0) }
    fun list(n: Int) { if (t0 > 0) android.util.Log.i("AtlantePerf", "discover list ${ms()} ms ($n libri)") }
    fun cover() {
        if (t0 == 0L) return
        val n = covers.incrementAndGet()
        if (n == 1 || n == 6 || n == 12) android.util.Log.i("AtlantePerf", "discover cover $n ${ms()} ms · cache: ${CoverImages.hits} in memoria, ${CoverImages.loads} caricate, ${CoverImages.misses} non trovate")
    }
    private fun ms() = (System.nanoTime() - t0) / 1_000_000
}
