package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.atlante.library.catalog.UnifiedSearch
import app.atlante.library.ui.ThemePalette
import kotlinx.coroutines.delay

/**
 * Under the one search bar: the works of the offline catalog that match and are not in the library yet
 * (Italian title when known, author, saga and number). A tap looks the work up online to add it.
 */
object CatalogStripView {
    class Handle(val view: View, private val query: MutableState<String>) { fun update(text: String) { query.value = text } }

    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, host: SagaHost): Handle {
        val query = mutableStateOf("")
        val view = ComposeView(activity).apply { setContent { AtlanteTheme(palette) { CatalogStrip(query.value, host) } } }
        return Handle(view, query)
    }
}

@Composable
fun CatalogStrip(query: String, host: SagaHost) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var results by remember { mutableStateOf<List<UnifiedSearch.CatalogResult>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.trim().length < 3) { results = emptyList(); return@LaunchedEffect }
        delay(120)
        results = runCatching { UnifiedSearch.search(context, query, host.library(), 15).catalog }.getOrDefault(emptyList())
    }
    if (results.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("Dal catalogo offline · ${results.size}", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelLarge)
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(results, key = { it.work.id }) { r ->
                val w = r.work
                val saga = r.series?.let { s -> s + (r.ordinal?.let { " · n. $it" } ?: "") }
                Column(Modifier.width(92.dp).clickable { host.searchOnline("${w.title} ${w.authors.substringBefore(" & ")}") }
                    .semantics(mergeDescendants = true) { contentDescription = "${w.title} di ${w.authors}" + (saga?.let { ", $it" } ?: "") + ", dal catalogo: tocca per cercarlo online" }) {
                    BookCover("cat:${w.id}", w.coverUrl, w.title, Modifier.width(92.dp).aspectRatio(0.66f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        w.coverUrl?.let { u -> SuggestionCovers.bytes(u)?.let { CoverImages.decode(it, null, 280) } }
                    }
                    Text(w.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(w.authors.substringBefore(" & "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    saga?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}
