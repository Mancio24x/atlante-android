package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.atlante.library.R
import app.atlante.library.ui.ThemePalette

/** What Sync shows, read by MainActivity from its preferences and the library (no logic here). */
class SyncState(
    @JvmField val hardcover: Boolean, @JvmField val hardcoverUser: String, @JvmField val conflicts: Int,
    @JvmField val calibreUrl: String, @JvmField val koreader: Boolean, @JvmField val koreaderUser: String,
    @JvmField val last: String, @JvmField val books: Int, @JvmField val missingCovers: Int, @JvmField val isbndb: Boolean)

/** The actions of Sync: the same MainActivity methods the old View page called. */
interface SyncHost {
    fun syncAll(); fun resolveConflicts()
    fun runHardcover(); fun hardcoverSettings(); fun repairHardcover()
    fun runCalibre(); fun calibreSettings(); fun calibreHelp()
    fun runKoreader(); fun linkKoreader(); fun koreaderSettings()
    fun importClippings(); fun importCatalog(); fun exportBackup(); fun fetchCovers(); fun sourceKeys()
}

object SyncView {
    /** The row that is open, kept while the page is redrawn (as the old page did). */
    private var open = "hc"
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, state: SyncState, host: SyncHost): View = ComposeView(activity).apply {
        setContent { AtlanteTheme(palette) { SyncScreen(state, palette, host) } }
    }

    /**
     * Sync (design 5a): is everything up to date? One "Sincronizza tutto" with the conflicts, then the services as
     * rows that open with their actions, import and export, data sources, and what stays on the phone.
     */
    @Composable
    fun SyncScreen(s: SyncState, palette: ThemePalette, host: SyncHost) {
        val colors = MaterialTheme.colorScheme
        val services = listOf(s.hardcover, s.calibreUrl.isNotEmpty(), s.koreader).count { it }
        var opened by remember { mutableStateOf(open) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp)) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val warn = s.conflicts > 0
                    Tile(if (services == 0) R.drawable.ic_cloud else if (warn) R.drawable.ic_warning else R.drawable.ic_check, if (warn) palette.warmColor else colors.primary, 48, 24, round = true)
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(if (services == 0) "Nessun servizio collegato" else if (warn) "Quasi tutto aggiornato" else "Tutto aggiornato", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif))
                        Text(if (services == 0) "Collega Hardcover, Calibre o KOReader qui sotto." else (if (s.last.isEmpty()) "" else s.last + " · ") + "$services " + if (services == 1) "servizio" else "servizi",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                if (services > 0) Button(onClick = host::syncAll, Modifier.fillMaxWidth().padding(top = 16.dp).height(48.dp)) {
                    Icon(painterResource(R.drawable.ic_sync), null, Modifier.size(20.dp)); Text("Sincronizza tutto", Modifier.padding(start = 8.dp))
                }
                if (s.conflicts > 0) Row(Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                    .background(lerp(colors.surfaceContainer, palette.warmColor, .14f)).clickable(onClick = host::resolveConflicts).padding(start = 14.dp, end = 12.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Conflitti con Hardcover: ${s.conflicts}. Risolvi" }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_warning), null, Modifier.size(20.dp), tint = palette.warmColor)
                    Text("${s.conflicts} " + (if (s.conflicts == 1) "libro differisce" else "libri differiscono") + " da Hardcover", Modifier.padding(horizontal = 10.dp).weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("Risolvi", style = MaterialTheme.typography.labelLarge, color = palette.warmColor)
                }
            }
            Header("Servizi collegati")
            ListCard {
                Service("hc", "Hardcover", "H", colors.primary, s.hardcover, s.conflicts > 0,
                    if (s.hardcover) (if (s.hardcoverUser.isEmpty()) "Collegato" else "@" + s.hardcoverUser) + " · stato, pagina e voto" else "Non collegato · serve la tua chiave personale",
                    "Stato, pagina e voto, nei due sensi.", opened, { opened = it; open = it }, false,
                    if (s.hardcover) listOf("Sincronizza ora" to host::runHardcover, "Impostazioni" to host::hardcoverSettings, "Ripara duplicati" to host::repairHardcover)
                    else listOf("Collega Hardcover" to host::hardcoverSettings))
                Service("cal", "Calibre", "C", colors.onSurface, s.calibreUrl.isNotEmpty(), false,
                    s.calibreUrl.ifEmpty { "Non collegato · server di contenuti sulla rete di casa" },
                    "Stato, pagina e capitolo dal server di contenuti di Calibre.", opened, { opened = it; open = it }, true,
                    if (s.calibreUrl.isNotEmpty()) listOf("Sincronizza ora" to host::runCalibre, "Impostazioni" to host::calibreSettings, "Come prepararlo" to host::calibreHelp)
                    else listOf("Collega Calibre" to host::calibreSettings, "Come prepararlo" to host::calibreHelp))
                Service("ko", "KOReader", "K", palette.warmColor, s.koreader, false,
                    if (s.koreader) "Riceve · " + s.koreaderUser else "Non configurato · riceve la percentuale letta",
                    "Riceve la percentuale letta, anche senza EPUB sul telefono.", opened, { opened = it; open = it }, true,
                    listOf("Ricevi avanzamento" to host::runKoreader, "Collega un libro" to host::linkKoreader, "Impostazioni" to host::koreaderSettings))
            }
            Header("Importa ed esporta")
            ListCard {
                Row(R.drawable.ic_edit, "Evidenziazioni Kindle", "Da My Clippings.txt al taccuino del libro", false, host::importClippings)
                Row(R.drawable.ic_download, "Importa catalogo o backup", "Atlante, Goodreads, StoryGraph, Calibre ZIP", true, host::importCatalog)
                Row(R.drawable.ic_upload, "Esporta backup", "${s.books} libri, note e avanzamenti in un file ZIP", true, host::exportBackup)
                Row(R.drawable.ic_image, "Copertine mancanti", if (s.missingCovers == 0) "Ogni libro ha la sua copertina" else "${s.missingCovers} " + if (s.missingCovers == 1) "libro senza copertina" else "libri senza copertina", true, host::fetchCovers)
            }
            Header("Fonti dei dati")
            Card {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("SBN" to true, "Open Library" to true, "Internet Archive" to true, "Hardcover" to s.hardcover, "ISBNdb" to s.isbndb).forEach { (name, on) ->
                        Row(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) lerp(colors.surfaceContainer, colors.primary, .08f) else Color.Transparent)
                            .then(if (on) Modifier else Modifier.outline(colors.outlineVariant)).padding(horizontal = 12.dp)
                            .semantics(mergeDescendants = true) { contentDescription = name + if (on) ", attiva" else ", non attiva" }, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if (on) colors.primary else colors.outlineVariant))
                            Text(name, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = if (on) colors.onSurface else colors.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("ISBNdb è facoltativo, con la tua chiave.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    TextButton(onClick = host::sourceKeys) { Text("Chiavi") }
                }
            }
            Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 20.dp)) {
                Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Text("Chiavi e password restano su questo telefono. Ai servizi vanno solo stato, pagina, capitolo e voto dei libri collegati.",
                    Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }

    private fun Modifier.outline(c: Color) = this.border(BorderStroke(1.dp, c), RoundedCornerShape(16.dp))

    @Composable private fun Header(text: String) =
        Text(text, Modifier.padding(start = 4.dp, end = 4.dp, top = 26.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif))

    @Composable private fun Card(content: @Composable ColumnScope.() -> Unit) =
        OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(18.dp), content = content)
        }

    @Composable private fun ListCard(content: @Composable ColumnScope.() -> Unit) =
        OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), content = content)

    @Composable private fun Tile(icon: Int, color: Color, size: Int, iconSize: Int, round: Boolean = false) {
        Box(Modifier.size(size.dp).clip(if (round) CircleShape else RoundedCornerShape(12.dp)).background(lerp(MaterialTheme.colorScheme.surfaceContainer, color, .14f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(iconSize.dp), tint = color)
        }
    }

    @Composable private fun Row(icon: Int, title: String, detail: String, divider: Boolean, onClick: () -> Unit) {
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $detail" }, verticalAlignment = Alignment.CenterVertically) {
            Tile(icon, MaterialTheme.colorScheme.primary, 40, 22)
            Column(Modifier.padding(start = 14.dp, end = 8.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun Service(key: String, name: String, letter: String, tile: Color, connected: Boolean, warning: Boolean, status: String, what: String,
                                    opened: String, onOpen: (String) -> Unit, divider: Boolean, actions: List<Pair<String, () -> Unit>>) {
        val colors = MaterialTheme.colorScheme; val open = key == opened
        if (divider) HorizontalDivider(color = colors.outlineVariant)
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable { onOpen(if (open) "" else key) }.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tile), contentAlignment = Alignment.Center) {
                Text(letter, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif), color = colors.surface)
            }
            Column(Modifier.padding(start = 12.dp, end = 8.dp).weight(1f)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Box(Modifier.padding(start = 6.dp).size(7.dp).clip(CircleShape).background(if (warning) colors.tertiary else if (connected) colors.primary else colors.outlineVariant))
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(painterResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more), if (open) "Comprimi $name" else "Mostra azioni di $name", Modifier.size(22.dp), tint = colors.onSurfaceVariant)
        }
        if (open) Column(Modifier.padding(start = 68.dp, end = 16.dp, bottom = 16.dp)) {
            Text(what, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEachIndexed { i, (label, run) ->
                    val description = if (label == "Impostazioni") "Impostazioni di $name" else label
                    if (i == 0) FilledTonalButton(onClick = run, Modifier.semantics { contentDescription = description }) { Text(label) }
                    else TextButton(onClick = run, Modifier.semantics { contentDescription = description }) { Text(label) }
                }
            }
        }
    }
}
