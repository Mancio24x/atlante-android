package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import app.atlante.library.catalog.RepairWorker
import app.atlante.library.saga.db.RepairJob
import app.atlante.library.ui.ThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the "Ripara libreria" page asks of MainActivity. */
interface RepairLibraryHost {
    fun start(type: String, everything: Boolean)
    fun cancel(jobId: String)
    /** "Operazioni in background": results and the proposals to decide. */
    fun operations()
    fun quality()
    fun recognize()
    /** Null when there was nothing to undo, otherwise what happened. */
    fun undoLastMerge(): String?
    fun hardcover(): Boolean
}

/**
 * Impostazioni › Ripara libreria: every repair on one page, each with its own state read from the same record as
 * the notification (queued, running with its percentage, done with merges and decisions left, failed).
 * Every repair runs in the background and goes on if you leave the page or the app.
 */
object RepairLibraryView {
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, host: RepairLibraryHost): View = ComposeView(activity).apply {
        val dao = app.atlante.library.saga.db.AtlanteDatabase.get(activity).saga()
        setContent { AtlanteTheme(palette) { val jobs by dao.jobs().collectAsState(emptyList()); RepairLibrary(jobs, host) } }
    }
}

private class Repair(val type: String, val title: String, val text: String)

private val REPAIRS = listOf(
    Repair(RepairWorker.LIBRARY, "Ripara duplicati", "Unisce le schede dello stesso libro: stesso ISBN o file, stessa opera nel catalogo, o stesso titolo e stesso autore. Note, sessioni e voto restano; i casi dubbi restano da decidere."),
    Repair(RepairWorker.HARDCOVER, "Duplicati di Hardcover", "Le copie create da Sync che Hardcover lega ai tuoi libri (per esempio l’edizione inglese di un libro italiano)."),
    Repair(RepairWorker.SAGAS, "Ripara saghe", "Ricontrolla saga, numero e universo: catalogo offline, poi Hardcover, Wikidata e Open Library. Le tue scelte non vengono toccate."),
    Repair(RepairWorker.MAPS, "Ripara mappe", "Ricostruisce l’ordine delle mappe delle saghe secondo le fonti, senza inventare numeri."),
    Repair(RepairWorker.METADATA, "Completa metadati", "Riempie solo i campi vuoti (autore, editore, anno, pagine, ISBN): quello che hai scritto resta."),
    Repair(RepairWorker.COVERS, "Copertine", "Scarica le copertine mancanti e sostituisce quelle piccole o sfocate. Le copertine scelte da te non vengono toccate."),
    Repair(RepairWorker.GENRES, "Indicizza i generi", "Dal catalogo offline, da Hardcover o dalla classificazione delle biblioteche; mai inventato."),
)

private fun status(j: RepairJob?, running: RepairJob?): String? = when {
    j == null -> null
    j.state == "queued" && running != null && running.id != j.id -> "In coda dopo «${RepairWorker.title(running.type)}»"
    j.state == "queued" -> "In attesa"
    j.state == "running" -> listOfNotNull(j.phase.ifEmpty { "In corso" }, if (j.total > 0) "${j.done * 100 / j.total}%" else null).joinToString(" · ")
    j.state == "done" -> listOfNotNull("Completata", j.applied.takeIf { it > 0 }?.let { "$it modifiche applicate" }, j.duplicates.takeIf { it > 0 && j.applied == 0 }?.let { "$it duplicati" },
        j.proposals.takeIf { it > 0 }?.let { "$it da decidere" }).joinToString(" · ")
    j.state == "failed" -> "Non riuscita" + (if (j.message.isNotEmpty()) ": ${j.message}" else "")
    j.state == "cancelled" -> "Annullata"
    else -> null
}

@Composable
fun RepairLibrary(jobs: List<RepairJob>, host: RepairLibraryHost) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var everything by remember { mutableStateOf(false) }
    val running = jobs.firstOrNull { it.state == "running" }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ogni riparazione lavora in background: puoi uscire da questa pagina o dall’app, l’avanzamento resta nella notifica.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        REPAIRS.filter { it.type != RepairWorker.HARDCOVER || host.hardcover() }.forEach { r ->
            val last = jobs.filter { it.type == r.type }.maxByOrNull { it.startedAt }
            val active = last != null && (last.state == "running" || last.state == "queued")
            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium)
                    Text(r.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    if (r.type == RepairWorker.SAGAS) Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(everything, { everything = it })
                        Text("Ricontrolla anche i libri già riconosciuti", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    status(last, running)?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = if (last?.state == "failed") colors.error else colors.primary) }
                    if (last?.state == "running" && last.total > 0) LinearProgressIndicator(progress = { last.done.toFloat() / last.total }, Modifier.fillMaxWidth())
                    else if (active) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (active) OutlinedButton(onClick = { host.cancel(last!!.id) }) { Text("Annulla") }
                        else Button(onClick = { host.start(r.type, r.type == RepairWorker.SAGAS && everything || r.type == RepairWorker.COVERS) }) { Text("Avvia") }
                        if (last?.state == "done" && last.proposals > 0) OutlinedButton(onClick = { host.operations() }) { Text("Decidi (${last.proposals})") }
                    }
                }
            }
        }
        OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Senza connessione", style = MaterialTheme.typography.titleMedium)
                Text("Proposte rapide dai titoli e dai metadati già presenti, senza connessione:", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                OutlinedButton(onClick = { host.recognize() }, Modifier.fillMaxWidth()) { Text("Riconosci saghe e ordine di lettura") }
            }
        }
        OutlinedButton(onClick = { host.quality() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Qualità del catalogo") }
        OutlinedButton(onClick = { host.operations() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Tutte le operazioni e i risultati") }
        TextButton(onClick = { scope.launch { notice = withContext(Dispatchers.IO) { host.undoLastMerge() } } }) { Text("Annulla l’ultima unione") }
        notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(8.dp))
    }
}
