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
 * six books of that genre. Tapping a cover opens the online search for it: nothing is added by itself.
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
        val found = try { discover.picks(genre, page) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
        picks = found
        if (found.isNotEmpty()) DiscoverSession.shown.put(key, found)
        // Most picks already carry their Italian edition; the rest look for it one by one (children of this
        // effect: a new genre or page cancels them).
        coroutineScope {
            found.forEachIndexed { i, p -> if (!p.italian) launch {
                val it = try { discover.italian(p) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { p }
                if (it == p) return@launch
                picks = picks?.toMutableList()?.also { list -> if (i < list.size && list[i].workKey == p.workKey) list[i] = it }
                picks?.let { list -> DiscoverSession.shown.put(key, list) }
            } }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 28.dp)) {
        Text("La prossima storia.", style = MaterialTheme.typography.headlineSmall)
        Text("Libri da scoprire, scelti dai lettori di Open Library. La scelta è tua: niente viene aggiunto da solo.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { host.searchOnline("") }, Modifier.fillMaxWidth()) { Text("Cerca un libro per titolo, autore o ISBN") }
        Spacer(Modifier.height(16.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(genre == null, { genre = null; page = 0 }, label = { Text("Più letti") }) }
            items(Discover.GENRES) { g -> FilterChip(genre == g, { genre = if (genre == g) null else g; page = 0 }, label = { Text(g.label) }) }
        }
        Spacer(Modifier.height(12.dp))
        Text(genre?.let { "Consigliati · ${it.label}" } ?: "I più letti", style = MaterialTheme.typography.titleLarge)
        Text(if (genre == null) "I libri più desiderati su Open Library, tra quelli con un’edizione italiana." else "I più letti di questo genere, uno per autore.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        // The fade is between loading / empty / grid only: a cover switching to its Italian edition must not
        // fade (and recompose) the whole grid.
        val list = picks
        Crossfade(when { list == null -> 0; list.isEmpty() -> 1; else -> 2 }, label = "grid") { state ->
            when (state) {
                0 -> Grid(List(6) { null }, host)
                1 -> Column {
                    Text("Nessun consiglio disponibile: serve la connessione a Internet.", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { attempt++ }) { Text("Riprova") }
                }
                else -> Grid(picks ?: emptyList(), host)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (page > 0) TextButton(onClick = { page-- }) { Text("‹ Precedenti") } else Spacer(Modifier)
            if (picks?.let { discover.hasMore(it.size, page) } == true) TextButton(onClick = { page++ }) { Text("Altri 6 ›") }
        }

        Spacer(Modifier.height(20.dp))
        Text("Classifiche italiane", style = MaterialTheme.typography.titleMedium)
        Text("Le classifiche aggiornate delle librerie, sul loro sito.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onLink("https://www.ibs.it/classifica/libri") }) { Text("IBS ↗") }
            OutlinedButton(onClick = { onLink("https://www.lafeltrinelli.it/classifica/libri") }) { Text("laFeltrinelli ↗") }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCollections) { Text("Le tue collezioni") }
    }
}

@Composable
private fun Grid(items: List<Discover.Pick?>, host: SagaHost) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { p -> PickCard(p, host, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PickCard(p: Discover.Pick?, host: SagaHost, modifier: Modifier) {
    Column(modifier.then(if (p != null) Modifier.clickable { host.searchOnline(p.query) }.semantics { contentDescription = "${p.title} di ${p.author}" } else Modifier)) {
        val url = p?.coverUrl
        // Same book in this place (workKey): the Italian cover replaces the first one only when it is ready.
        BookCover(p?.workKey, url, p?.title ?: "", Modifier.fillMaxWidth().aspectRatio(0.66f)) {
            url?.let { u -> SuggestionCovers.bytes(u)?.let { CoverImages.decode(it, null, 360) } }
        }
    if (p != null) {
            Text(p.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(p.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
