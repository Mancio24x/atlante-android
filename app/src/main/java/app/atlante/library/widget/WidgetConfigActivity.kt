package app.atlante.library.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import app.atlante.library.model.Book
import app.atlante.library.ui.ThemePalette
import app.atlante.library.ui.compose.AtlanteTheme
import kotlinx.coroutines.launch

/**
 * Settings of one widget (shown when it is added, and from its ⚙ button or a long press › Impostazioni):
 * which book it follows, the size of the "+ pagine" and "+ %" steps, and its colours. Each widget keeps its own.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Closing without saving cancels a widget being added (Android's rule for configuration screens).
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val books = Widgets.store(this)?.snapshot() ?: emptyList()
        val palette = Widgets.palette(this, "app") ?: ThemePalette.find("atlante")
        lifecycleScope.launch {
            val glanceId = runCatching { GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId) }.getOrNull()
            val saved = glanceId?.let { getAppWidgetState(this@WidgetConfigActivity, PreferencesGlanceStateDefinition, it) }
            setContent {
                AtlanteTheme(palette) {
                    WidgetSettings(books, saved?.get(Widgets.BOOK), saved?.get(Widgets.PAGE_STEP) ?: Widgets.DEFAULT_PAGES,
                        saved?.get(Widgets.PERCENT_STEP) ?: Widgets.DEFAULT_PERCENT, saved?.get(Widgets.COLORS) ?: "app") { book, pages, percent, colors ->
                        lifecycleScope.launch {
                            val id = glanceId ?: GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                            updateAppWidgetState(this@WidgetConfigActivity, id) {
                                if (book == null) it.remove(Widgets.BOOK) else it[Widgets.BOOK] = book
                                it[Widgets.PAGE_STEP] = pages; it[Widgets.PERCENT_STEP] = percent; it[Widgets.COLORS] = colors
                            }
                            Widgets.update(this@WidgetConfigActivity)
                            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                            finish()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetSettings(books: List<Book>, chosen0: String?, pages0: Int, percent0: Int, colors0: String, onSave: (String?, Int, Int, String) -> Unit) {
    val reading = remember(books) { Widgets.reading(books) }
    var showAll by remember { mutableStateOf(reading.isEmpty()) }
    var chosen by remember { mutableStateOf(chosen0) }
    var pages by remember { mutableIntStateOf(pages0) }
    var percent by remember { mutableIntStateOf(percent0) }
    var colors by remember { mutableStateOf(colors0) }
    val list = if (showAll) books.filter { it.status != Book.READ }.sortedBy { it.title.lowercase() } else reading
    Scaffold(topBar = { TopAppBar(title = { Text("Widget di lettura") }) }, bottomBar = {
        Button(onClick = { onSave(chosen, pages, percent, colors) }, Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) { Text("Salva") }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("Libro da mostrare", style = MaterialTheme.typography.titleMedium) }
            item { Choice("Il libro che stai leggendo ora (cambia da solo)", chosen == null) { chosen = null } }
            items(list, key = { it.id }) { b -> Choice("${b.title} · ${b.author}", chosen == b.id) { chosen = b.id } }
            item {
                if (reading.isEmpty()) Text("Nessun libro in lettura: puoi sceglierne uno qualsiasi.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Mostra solo i libri in lettura" else "Mostra tutti i libri") }
            }
            item { Spacer(Modifier.height(12.dp)); Text("Pulsante «+ pagine»", style = MaterialTheme.typography.titleMedium) }
            item { Steps(listOf(1, 5, 10, 20, 50), pages, " pag.") { pages = it } }
            item { Spacer(Modifier.height(8.dp)); Text("Pulsante «+ %»", style = MaterialTheme.typography.titleMedium) }
            item { Steps(listOf(1, 2, 5, 10), percent, "%") { percent = it } }
            item { Spacer(Modifier.height(8.dp)); Text("Colori", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Widgets.COLOR_MODES.forEach { (id, label) -> FilterChip(colors == id, { colors = id }, label = { Text(label) }) }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null); Spacer(Modifier.width(8.dp)); Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Steps(values: List<Int>, current: Int, unit: String, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { values.forEach { v -> FilterChip(current == v, { onPick(v) }, label = { Text("+$v$unit") }) } }
}
