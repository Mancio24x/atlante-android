package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.atlante.library.saga.CacheManager
import app.atlante.library.saga.Discover
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.ui.Covers
import app.atlante.library.ui.ThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * Scopri: a grid of six covers, the most read books (with an Italian edition) or, with a genre chosen,
 * six books of that genre. Tapping a cover opens the online search for it: nothing is added by itself.
 */
@Composable
fun DiscoverScreen(discover: Discover, host: SagaHost, onLink: (String) -> Unit, onCollections: () -> Unit) {
    var genre by remember { mutableStateOf<Discover.Genre?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var picks by remember { mutableStateOf<List<Discover.Pick>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(genre, page, attempt) {
        picks = null
        val found = runCatching { discover.picks(genre, page) }.getOrDefault(emptyList())
        picks = found
        // Progressive: each cover switches to its Italian edition when found.
        found.forEachIndexed { i, p -> scope.launch {
            val it = runCatching { discover.italian(p) }.getOrDefault(p)
            picks = picks?.toMutableList()?.also { list -> if (i < list.size && list[i].workKey == p.workKey) list[i] = it }
        } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 28.dp)) {
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

        Crossfade(picks, label = "grid") { list ->
            when {
                list == null -> Grid(List(6) { null }, host)
                list.isEmpty() -> Column {
                    Text("Nessun consiglio disponibile: serve la connessione a Internet.", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { attempt++ }) { Text("Riprova") }
                }
                else -> Grid(list, host)
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

private object PickCovers : android.util.LruCache<String, ImageBitmap>(24)

@Composable
private fun PickCard(p: Discover.Pick?, host: SagaHost, modifier: Modifier) {
    var image by remember(p?.coverUrl) { mutableStateOf(p?.coverUrl?.let { PickCovers.get(it) }) }
    LaunchedEffect(p?.coverUrl) {
        val url = p?.coverUrl ?: return@LaunchedEffect
        if (image == null) image = withContext(Dispatchers.IO) {
            runCatching { SuggestionCovers.bytes(url)?.let { Covers.decode(null, it, 360)?.asImageBitmap() } }.getOrNull()?.also { PickCovers.put(url, it) }
        }
    }
    Column(modifier.then(if (p != null) Modifier.clickable { host.searchOnline(p.query) }.semantics { contentDescription = "${p.title} di ${p.author}" } else Modifier)) {
        Surface(Modifier.fillMaxWidth().aspectRatio(0.66f), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
            val img = image
            if (img != null) Image(img, null, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            else if (p != null) Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
                Text(p.title, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
        if (p != null) {
            Text(p.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(p.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
