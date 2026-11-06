package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
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
                UniverseIndex(data) { id -> scope.launch { open = suggestions.series(id) } }
                open?.let { FullSaga(it, Book().apply { id = "" }, host) { open = null } }
            }
        }
    }
}

@Composable
fun UniverseIndex(data: List<Pair<UniverseEntity, List<SeriesSummary>>>?, onSaga: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text("Universi narrativi", style = MaterialTheme.typography.headlineSmall)
        Text("Un universo raccoglie più saghe dello stesso mondo, come il Cosmere di Sanderson. Non è una saga: ogni saga ha il suo ordine.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        when {
            data == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            data.isEmpty() -> Text("Nessun universo per ora. Si riconoscono aprendo i libri delle tue saghe.", style = MaterialTheme.typography.bodyLarge)
            else -> data.forEach { (universe, sagas) ->
                ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(universe.name, style = MaterialTheme.typography.titleLarge)
                        Text("${sagas.size} " + (if (sagas.size == 1) "saga" else "saghe") + " · ${sagas.sumOf { it.owned }} di ${sagas.sumOf { it.volumes }} libri in biblioteca",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        sagas.forEach { s ->
                            Row(Modifier.fillMaxWidth().clickable { onSaga(s.series.id) }.padding(vertical = 10.dp)) {
                                Text(s.series.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text("${s.owned}/${s.volumes}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
