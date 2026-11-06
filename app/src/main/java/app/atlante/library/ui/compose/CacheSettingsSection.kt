package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import app.atlante.library.saga.CacheManager
import app.atlante.library.saga.SagaRefreshWorker
import app.atlante.library.ui.ThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

object CacheSettingsView {
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette): View = ComposeView(activity).apply {
        setContent { AtlanteTheme(palette) { CacheSettingsSection(CacheManager.get(activity)) { SagaRefreshWorker.schedule(activity) } } }
    }
}

private fun size(bytes: Long) = when {
    bytes >= 1024L * 1024 -> String.format(Locale.ITALIAN, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** "Cache e dati temporanei": space used, limits and cleanup. The library and personal notes are never touched. */
@Composable
fun CacheSettingsSection(cache: CacheManager, onRefreshChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<CacheManager.Usage?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) { usage = withContext(Dispatchers.IO) { runCatching { cache.usage() }.getOrNull() } }
    fun act(done: String, block: suspend () -> Unit) = scope.launch { withContext(Dispatchers.IO) { runCatching { block() } }; message = done; tick++ }

    Column(Modifier.fillMaxWidth()) {
        Text("Copertine dei suggerimenti, risposte dei cataloghi e saghe scaricate. Svuotarli non tocca libri, note, voti né avanzamento.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val u = usage
        if (u == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
        else Column(Modifier.padding(vertical = 8.dp)) {
            UsageRow("Copertine dei suggerimenti", u.covers, cache.settings.coverMb * 1048576L)
            UsageRow("Risposte dei cataloghi", u.catalog, null)
            UsageRow("Suggerimenti in cache", u.suggestions, null)
            UsageRow("Database delle saghe", u.graph, null)
            Text("Totale: " + size(u.covers + u.catalog + u.suggestions + u.graph), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        }
        Stepper("Spazio massimo per le copertine", cache.settings.coverMb, 20f..1000f, 10, { "$it MB" }) { cache.settings.coverMb = it; act("Limite aggiornato") { cache.covers.trim() } }
        Stepper("Suggerimenti validi per", cache.settings.suggestionDays, 1f..90f, 1, { if (it == 1) "1 giorno" else "$it giorni" }) { cache.settings.suggestionDays = it }
        Stepper("Aggiorna le saghe ogni", cache.settings.sagaRefreshDays, 1f..30f, 1, { if (it == 1) "giorno" else "$it giorni" }) { cache.settings.sagaRefreshDays = it; onRefreshChanged() }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { act("Copertine eliminate") { cache.clearCovers() } }, Modifier.weight(1f)) { Text("Svuota copertine") }
            OutlinedButton(onClick = { act("Suggerimenti eliminati") { cache.clearSuggestions(); cache.clearCatalog() } }, Modifier.weight(1f)) { Text("Svuota suggerimenti") }
        }
        Button(onClick = { act("Cache svuotata") { cache.clearCovers(); cache.clearSuggestions(); cache.clearCatalog() } }, Modifier.fillMaxWidth()) { Text("Svuota tutta la cache") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun UsageRow(label: String, bytes: Long, limit: Long?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(size(bytes) + (limit?.let { " di " + size(it) } ?: ""), style = MaterialTheme.typography.bodyMedium)
    }
    if (limit != null) LinearProgressIndicator(progress = { (bytes.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
}

@Composable
/** Continuous slider (no tick dots) whose value is rounded to a multiple of [round]. */
private fun Stepper(label: String, initial: Int, range: ClosedFloatingPointRange<Float>, round: Int, format: (Int) -> String, onDone: (Int) -> Unit) {
    var value by remember { mutableFloatStateOf(initial.toFloat()) }
    fun snap(v: Float) = ((v / round).roundToInt() * round).coerceIn(range.start.roundToInt(), range.endInclusive.roundToInt())
    Column(Modifier.padding(top = 8.dp)) {
        Text("$label: ${format(snap(value))}", style = MaterialTheme.typography.bodyMedium)
        Slider(value, { value = it }, valueRange = range, onValueChangeFinished = { value = snap(value).toFloat(); onDone(snap(value)) })
    }
}
