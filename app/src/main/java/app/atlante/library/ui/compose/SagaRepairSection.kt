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
import app.atlante.library.ui.ThemePalette

object SagaRepairView {
    fun interface Start { fun start(type: String, everything: Boolean) }

    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, start: Start, operations: Runnable): View = ComposeView(activity).apply {
        setContent { AtlanteTheme(palette) { SagaRepairSection({ t, e -> start.start(t, e) }) { operations.run() } } }
    }
}

/**
 * "Ripara saghe e mappe" in the settings: both run in the background (the bar in the app and the notification show
 * the progress; you can keep using the app); results and proposals in "Operazioni in background".
 */
@Composable
fun SagaRepairSection(start: (String, Boolean) -> Unit, operations: () -> Unit) {
    var everything by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text("Ricontrolla le saghe con il catalogo offline per primo, poi Hardcover (se collegato), Wikidata e Open Library: ricostruisce gli elenchi, completa la saga dei libri che non ce l’hanno e toglie i collegamenti ai libri rimossi. Le tue scelte non vengono toccate.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Switch(everything, { everything = it })
            Text("Ricontrolla anche i libri già riconosciuti", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = { start(RepairWorker.SAGAS, everything) }, Modifier.fillMaxWidth()) { Text("Ripara saghe") }
        OutlinedButton(onClick = { start(RepairWorker.MAPS, false) }, Modifier.fillMaxWidth()) { Text("Controlla le mappe") }
        Text("In background: segui l’avanzamento nella barra in basso o nella notifica.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = operations) { Text("Operazioni in background") }
    }
}
