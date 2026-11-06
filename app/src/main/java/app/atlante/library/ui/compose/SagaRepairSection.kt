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
import app.atlante.library.saga.CacheManager
import app.atlante.library.saga.SagaRepair
import app.atlante.library.saga.SagaRepository
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.ui.ThemePalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object SagaRepairView {
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, host: SagaHost): View = ComposeView(activity).apply {
        val dao = AtlanteDatabase.get(activity).saga()
        val repo = SagaRepository(dao).apply { refreshDays = CacheManager.get(activity).settings.sagaRefreshDays }
        setContent { AtlanteTheme(palette) { SagaRepairSection(SagaRepair(dao, repo), host) } }
    }
}

/** "Ripara saghe" in the settings: progress, stop, and a summary of what changed. */
@Composable
fun SagaRepairSection(repair: SagaRepair, host: SagaHost) {
    val scope = rememberCoroutineScope()
    var everything by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var fraction by remember { mutableFloatStateOf(0f) }
    var report by remember { mutableStateOf<SagaRepair.Report?>(null) }
    val running = job?.isActive == true

    Column(Modifier.fillMaxWidth()) {
        Text("Ricontrolla le saghe con Wikidata e Open Library: ricostruisce gli elenchi, completa la saga dei libri che non ce l’hanno e toglie i collegamenti ai libri rimossi. Le tue scelte non vengono toccate.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Switch(everything, { everything = it }, enabled = !running)
            Text("Ricontrolla anche i libri già riconosciuti", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        if (running) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Circa un libro ogni pochi secondi, per rispettare i servizi pubblici. Resta su questa pagina.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { job?.cancel(); status = "Interrotto" }, Modifier.fillMaxWidth()) { Text("Interrompi") }
        } else Button(onClick = {
            report = null
            job = scope.launch {
                report = repair.run(host.library(), everything, { b, name, ordinal -> host.applySeries(b.id, name, ordinal, false); true }) { i, n, b ->
                    fraction = if (n == 0) 1f else i.toFloat() / n; status = "${i + 1} di $n · ${b.title}"
                }
            }
        }, Modifier.fillMaxWidth()) { Text("Ripara saghe") }
        report?.let { r ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Fatto: ${r.checked} libri controllati", style = MaterialTheme.typography.titleSmall)
                    listOf("con saga sicura" to r.linked, "da confermare (apri il libro)" to r.toConfirm, "senza saga nota" to r.noSaga,
                        "non identificati" to r.unidentified, "saga aggiunta alla scheda" to r.filled, "scelte tue lasciate com’erano" to r.keptByUser,
                        "collegamenti a libri rimossi eliminati" to r.removed, "errori di rete" to r.failed)
                        .filter { it.second > 0 }.forEach { (label, n) -> Text("$n $label", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
