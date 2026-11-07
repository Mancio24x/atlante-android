package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.atlante.library.catalog.RepairWorker
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.saga.db.RepairJob
import app.atlante.library.ui.ThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** What the operations screen asks of the app (merges and corrections run off the main thread there). */
interface OperationsHost {
    fun start(type: String)
    fun cancel(jobId: String)
    fun openBook(bookId: String)
    /** Merges [other] into [keep]; null when done, otherwise the reason it could not. */
    fun merge(keep: String, other: String, ratingFromOther: Boolean, reviewFromOther: Boolean): String?
    fun different(keep: String, other: String)
    fun applyNumber(bookId: String, series: String, ordinal: String)
    fun refuseNumber(seriesId: Long, bookId: String, ordinal: String)
    /** A value from the catalog replaces yours ("originalTitle", "number"). */
    fun applyField(bookId: String, field: String, value: String, series: String?)
    /** "Tieni il mio": this field of this book is not proposed again. */
    fun keepMine(bookId: String, field: String)
    /** Undoes the last merge; the message to show. */
    fun undoLastMerge(): String
}

object RepairViews {
    /** The bar over the bottom navigation, on every page, while a repair runs or waits. */
    @JvmStatic fun bar(activity: ComponentActivity, palette: ThemePalette, onOpen: Runnable): View = ComposeView(activity).apply {
        val dao = AtlanteDatabase.get(activity).saga()
        setContent { AtlanteTheme(palette) { val jobs by dao.activeJobs().collectAsState(emptyList()); RepairBar(jobs) { onOpen.run() } } }
    }

    @JvmStatic fun operations(activity: ComponentActivity, palette: ThemePalette, host: OperationsHost, onClose: Runnable): View = ComposeView(activity).apply {
        val dao = AtlanteDatabase.get(activity).saga()
        setContent { AtlanteTheme(palette) { val jobs by dao.jobs().collectAsState(emptyList()); Operations(jobs, host, dao) { onClose.run() } } }
    }
}

private fun percent(j: RepairJob) = if (j.total > 0) j.done * 100 / j.total else null

@Composable
fun RepairBar(jobs: List<RepairJob>, onOpen: () -> Unit) {
    val j = jobs.firstOrNull { it.state == "running" } ?: jobs.firstOrNull() ?: return
    var small by remember { mutableStateOf(false) }
    val p = percent(j)
    val text = "${RepairWorker.title(j.type)}" + (p?.let { " — $it%" } ?: "") + if (jobs.size > 1) " · altre ${jobs.size - 1} in coda" else ""
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable(onClick = onOpen)
        .semantics(mergeDescendants = true) { contentDescription = "$text. ${j.done} di ${j.total} libri analizzati. Tocca per i dettagli" },
        shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp, shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = if (small) 6.dp else 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                TextButton(onClick = { small = !small }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (small) "Mostra" else "Riduci") }
            }
            // Real progress: the number of books analysed, or an indeterminate bar while the total is not known.
            if (p != null) LinearProgressIndicator(progress = { j.done.toFloat() / j.total }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!small) Text(listOfNotNull(j.phase.ifEmpty { null }, if (j.total > 0) "${j.done} di ${j.total} libri analizzati" else null,
                j.duplicates.takeIf { it > 0 }?.let { "duplicati individuati: $it" }, j.found.takeIf { it > 0 && j.duplicates == 0 }?.let { "trovati: $it" },
                j.errors.takeIf { it > 0 }?.let { "errori: $it" }).joinToString(" · "),
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun stateLabel(s: String) = when (s) { "queued" -> "In attesa"; "running" -> "In corso"; "done" -> "Completata"; "failed" -> "Non riuscita"; "cancelled" -> "Annullata"; else -> s }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Operations(jobs: List<RepairJob>, host: OperationsHost, dao: app.atlante.library.saga.db.SagaDao, onClose: () -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                TopAppBar(title = { Text("Operazioni in background") }, navigationIcon = { TextButton(onClick = onClose) { Text("Chiudi") } })
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("Avvia una riparazione: continua anche se chiudi questa pagina o l’app, con la notifica dell’avanzamento.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        listOf(RepairWorker.LIBRARY, RepairWorker.HARDCOVER, RepairWorker.SAGAS, RepairWorker.MAPS, RepairWorker.METADATA, RepairWorker.COVERS).forEach { t ->
                            OutlinedButton(onClick = { host.start(t) }, Modifier.fillMaxWidth()) { Text(RepairWorker.title(t)) }
                        }
                        TextButton(onClick = { scope.launch { notice = withContext(Dispatchers.IO) { host.undoLastMerge() } } }) { Text("Annulla l’ultima unione") }
                        notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                    if (jobs.isEmpty()) item { Text("Nessuna operazione finora.", style = MaterialTheme.typography.bodyLarge) }
                    items(jobs, key = { it.id }) { j -> JobCard(j, open == j.id, host, { open = if (open == j.id) null else j.id }) { updated ->
                        scope.launch(Dispatchers.IO) { dao.putJob(updated) } } }
                }
            }
        }
    }
}

@Composable
private fun JobCard(j: RepairJob, expanded: Boolean, host: OperationsHost, onToggle: () -> Unit, onUpdate: (RepairJob) -> Unit) {
    val p = percent(j)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(RepairWorker.title(j.type), style = MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(stateLabel(j.state), j.phase.takeIf { it.isNotEmpty() && j.state == "running" }, p?.let { "$it%" },
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(j.startedAt))).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (j.state == "running" || j.state == "queued") {
                if (p != null) LinearProgressIndicator(progress = { j.done.toFloat() / j.total }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(listOfNotNull(if (j.total > 0) "${j.done} di ${j.total} libri analizzati" else null, j.offline.takeIf { it > 0 }?.let { "$it riconosciuti offline dal catalogo" },
                j.duplicates.takeIf { it > 0 }?.let { "$it duplicati" }, j.applied.takeIf { it > 0 }?.let { "$it modifiche applicate" },
                j.proposals.takeIf { it > 0 }?.let { "$it da decidere" }, j.errors.takeIf { it > 0 }?.let { "$it errori" }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium)
            if (j.message.isNotEmpty()) Text(j.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (j.state == "running" || j.state == "queued") OutlinedButton(onClick = { host.cancel(j.id) }) { Text("Annulla") }
                if (j.state == "failed" || j.state == "cancelled") OutlinedButton(onClick = { host.start(j.type) }) { Text("Riprova") }
                if (j.result.isNotEmpty()) TextButton(onClick = onToggle) { Text(if (expanded) "Nascondi" else "Risultati") }
            }
            if (expanded) Proposals(j, host, onUpdate)
        }
    }
}

/** The proposals of a finished analysis: merge / different / later; numbers to apply or refuse. Each answer is kept. */
@Composable
private fun Proposals(j: RepairJob, host: OperationsHost, onUpdate: (RepairJob) -> Unit) {
    val result = remember(j.result) { runCatching { JSONObject(j.result) }.getOrDefault(JSONObject()) }
    val merges = result.optJSONArray("merges") ?: JSONArray()
    val maps = result.optJSONArray("maps") ?: JSONArray()
    val fields = result.optJSONArray("fields") ?: JSONArray()
    var errors by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun drop(key: String, index: Int) {
        val list = result.optJSONArray(key) ?: return
        val rest = JSONArray(); for (i in 0 until list.length()) if (i != index) rest.put(list.get(i))
        result.put(key, rest)
        onUpdate(j.copy(result = result.toString(), proposals = (result.optJSONArray("merges")?.length() ?: 0) + (result.optJSONArray("fields")?.length() ?: 0) + (result.optJSONArray("maps")?.let { m -> (0 until m.length()).count { !m.getJSONObject(it).isNull("bookId") } } ?: 0)))
    }
    if (merges.length() == 0 && maps.length() == 0 && fields.length() == 0) Text("Niente da decidere ✓", style = MaterialTheme.typography.bodyMedium)
    errors?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    for (i in 0 until merges.length()) {
        val m = merges.getJSONObject(i)
        val conflicts = m.optJSONArray("conflicts") ?: JSONArray()
        var ratingOther by remember(m.toString()) { mutableStateOf(false) }
        var reviewOther by remember(m.toString()) { mutableStateOf(false) }
        // Which record is the main edition (its title, ISBN, file and cover stay first); the other joins it.
        var swapped by remember(m.toString()) { mutableStateOf(false) }
        val keepId = if (swapped) m.getString("other") else m.getString("keep")
        val otherId = if (swapped) m.getString("keep") else m.getString("other")
        HorizontalDivider()
        Text("Corrispondenza ${m.optString("confidence")}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text("Edizione principale", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!swapped, { swapped = false }, label = { Text("Prima", maxLines = 1) })
            FilterChip(swapped, { swapped = true }, label = { Text("Seconda", maxLines = 1) })
        }
        Text("Prima: " + m.optString("keepLine") + if (!swapped) "  ← resta" else "", Modifier.clickable { host.openBook(m.getString("keep")) }, style = MaterialTheme.typography.bodyMedium)
        Text("Seconda: " + m.optString("otherLine") + if (swapped) "  ← resta" else "", Modifier.clickable { host.openBook(m.getString("other")) }, style = MaterialTheme.typography.bodyMedium)
        Text("Dall’altra prende tutto ciò che manca: sessioni, note, voto, ISBN, file e copertina come edizione e copertina alternative.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Perché: " + (0 until (m.optJSONArray("reasons")?.length() ?: 0)).joinToString("; ") { m.getJSONArray("reasons").getString(it) },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (conflicts.length() > 0) {
            Text("Da scegliere: " + (0 until conflicts.length()).joinToString("; ") { conflicts.getString(it) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            val text = conflicts.toString()
            if (text.contains("voto")) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ratingOther, { ratingOther = it }); Text("Tieni il voto dell’altra scheda", style = MaterialTheme.typography.bodySmall) }
            if (text.contains("recension")) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(reviewOther, { reviewOther = it }); Text("Tieni la recensione dell’altra scheda", style = MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { val e = withContext(Dispatchers.IO) { host.merge(keepId, otherId, ratingOther, reviewOther) }
                if (e == null) drop("merges", i) else errors = e } }) { Text("Unisci") }
            OutlinedButton(onClick = { host.different(m.getString("keep"), m.getString("other")); drop("merges", i) }) { Text("Non unire") }
            TextButton(onClick = { }) { Text("Dopo") }
        }
    }
    for (i in 0 until fields.length()) {
        val f = fields.getJSONObject(i)
        HorizontalDivider()
        Text(f.optString("text"), Modifier.clickable { host.openBook(f.getString("bookId")) }, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { host.applyField(f.getString("bookId"), f.getString("field"), f.getString("value"), f.optString("series").ifEmpty { null }); drop("fields", i) }) { Text("Usa quello del catalogo") }
            OutlinedButton(onClick = { host.keepMine(f.getString("bookId"), f.getString("field")); drop("fields", i) }) { Text("Tieni il mio") }
        }
    }
    for (i in 0 until maps.length()) {
        val m = maps.getJSONObject(i)
        HorizontalDivider()
        Text(m.optString("series"), style = MaterialTheme.typography.labelLarge)
        Text(m.optString("text"), style = MaterialTheme.typography.bodyMedium)
        if (!m.isNull("bookId") && !m.isNull("ordinal")) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { host.applyNumber(m.getString("bookId"), m.getString("series"), m.getString("ordinal")); drop("maps", i) }) { Text("Usa il n. ${m.getString("ordinal")}") }
            OutlinedButton(onClick = { host.refuseNumber(m.getLong("seriesId"), m.getString("bookId"), m.getString("ordinal")); drop("maps", i) }) { Text("No, tengo il mio") }
        }
    }
}
