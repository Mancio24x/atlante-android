package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import app.atlante.library.model.Book
import app.atlante.library.saga.SagaShelf
import app.atlante.library.saga.Suggestions
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.saga.db.SeriesSummary
import app.atlante.library.saga.db.UniverseEntity
import app.atlante.library.ui.ThemePalette
import kotlinx.coroutines.launch

/** "Universi": narrative universes with their sagas. A universe (Cosmere) groups sagas and is never a saga itself. */
object UniverseIndexView {
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, query: String, host: SagaHost): View = ComposeView(activity).apply {
        val dao = AtlanteDatabase.get(activity).saga()
        val suggestions = Suggestions(dao, host::library)
        setContent {
            AtlanteTheme(palette) {
                var data by remember { mutableStateOf<List<Pair<UniverseEntity, List<SeriesSummary>>>?>(null) }
                var open by remember { mutableStateOf<SagaShelf?>(null) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(query) {
                    val all = dao.allSeries()
                    data = dao.universes().map { u -> u to all.filter { it.series.universeId == u.id } }
                        .filter { (u, sagas) -> query.isBlank() || u.name.contains(query, true) || sagas.any { it.series.name.contains(query, true) } }
                }
                UniverseIndex(data, host.library()) { id -> scope.launch { open = suggestions.series(id) } }
                open?.let { FullSaga(it, Book().apply { id = "" }, host) { open = null } }
            }
        }
    }
}

/** Design 7d: each universe as a card with a ring (books read) and its sagas, each with a bar per volume. */
@Composable
fun UniverseIndex(data: List<Pair<UniverseEntity, List<SeriesSummary>>>?, library: List<Book>, onSaga: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        Text("Un universo raccoglie più saghe dello stesso mondo. Non è una saga: ognuna ha il suo ordine.",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp))
        when {
            data == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            data.isEmpty() -> Text("Nessun universo per ora. Si riconoscono aprendo i libri delle tue saghe.", style = MaterialTheme.typography.bodyLarge)
            else -> data.forEach { (universe, sagas) ->
                val read = sagas.sumOf { s -> readIn(library, s.series.name) }
                val total = sagas.sumOf { it.volumes }.coerceAtLeast(1)
                OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainer)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(universe.name, style = MaterialTheme.typography.titleLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif))
                            Text("${sagas.size} " + (if (sagas.size == 1) "saga" else "saghe") + " · ${sagas.sumOf { it.owned }} di ${sagas.sumOf { it.volumes }} in biblioteca",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Box(Modifier.size(44.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            CircularProgressIndicator(progress = { read.toFloat() / total }, modifier = Modifier.fillMaxSize(), strokeWidth = 5.dp,
                                trackColor = colors.outlineVariant, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                            Text("${read * 100 / total}%", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    sagas.forEach { s ->
                        HorizontalDivider(color = colors.outlineVariant)
                        Row(Modifier.fillMaxWidth().clickable { onSaga(s.series.id) }.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                val r = readIn(library, s.series.name)
                                Row { Text(s.series.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text("$r/${s.volumes} letti", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    repeat(s.volumes.coerceIn(1, 40)) { i ->
                                        val fill = when { i < r -> colors.primary; i < s.owned -> colors.primary.copy(alpha = .45f); else -> androidx.compose.ui.graphics.Color.Transparent }
                                        Box(Modifier.weight(1f).height(6.dp).then(if (i >= s.owned) Modifier.border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)) else Modifier)
                                            .background(fill, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
                                    }
                                }
                            }
                            Text("›", Modifier.padding(start = 10.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        Text("Si riconoscono dalle tue saghe, con Hardcover e Wikidata.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, top = 6.dp))
    }
}

private fun readIn(library: List<Book>, saga: String) = library.count { it.series.equals(saga, true) && it.status == Book.READ }
