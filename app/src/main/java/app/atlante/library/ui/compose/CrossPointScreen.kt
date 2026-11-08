package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.atlante.library.R
import app.atlante.library.crosspoint.CrossPointClient
import app.atlante.library.ui.ThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** What the CrossPoint page needs from the app: the remembered address, the library's files, the import. */
interface CrossPointHost {
    fun savedHost(): String?
    fun saveHost(host: String?)
    /** Books of the library with their own file (title → file), to send to the reader. */
    fun libraryFiles(): List<Pair<String, File>>
    /** Is a book with this file name already in the library? */
    fun inLibrary(fileName: String): Boolean
    /** Imports downloaded files with the usual import (preview, duplicates, existing records). */
    fun importFiles(files: List<File>)
    fun cacheDir(): File
    /** Binds CrossPoint sockets to the Wi-Fi (the reader's own Wi-Fi has no internet). */
    fun binder(): CrossPointClient.Binder?
    /** Joins the reader's Wi-Fi "CrossPoint-Reader"; [onResult] true when connected. */
    fun joinReaderWifi(onResult: (Boolean) -> Unit)
    fun leaveReaderWifi()
}

object CrossPointView {
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, host: CrossPointHost): View = ComposeView(activity).apply {
        setContent { AtlanteTheme(palette) { CrossPointScreen(host) } }
    }
}

private val Round20 = RoundedCornerShape(20.dp)
private const val BOOKS = "EPUB|TXT|CBZ|AZW3"

private fun size(bytes: Long) = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ITALIAN, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes byte"
}
private fun child(path: String, name: String) = (if (path.endsWith("/")) path else "$path/") + name

/**
 * CrossPoint: the e-ink reader's files, managed from the app over the home Wi-Fi (the reader's file transfer
 * screen must be open). Find the reader or type its address, browse folders, send books of the library, download
 * books into Atlante, new folder, rename, move, delete (with a confirmation).
 */
@Composable
fun CrossPointScreen(host: CrossPointHost) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var address by remember { mutableStateOf(host.savedHost()) }
    var status by remember { mutableStateOf<CrossPointClient.Status?>(null) }
    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<CrossPointClient.Entry>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var failed by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<CrossPointClient.Entry?>(null) }

    fun client() = CrossPointClient(address!!, binder = host.binder())
    fun say(text: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }
    fun load() { val a = address ?: return
        scope.launch {
            busy = "Collegamento…"; failed = null
            try {
                val (s, list) = withContext(Dispatchers.IO) { val c = CrossPointClient(a, binder = host.binder()); (if (status == null) c.status() else status) to c.list(path) }
                status = s; entries = list
            } catch (e: Exception) { failed = e.message ?: "CrossPoint non risponde"; entries = null }
            busy = null
        } }
    /** Runs an operation on the reader off the main thread, then reloads the folder. */
    fun run(label: String, done: String, work: suspend (CrossPointClient) -> Unit) {
        scope.launch {
            busy = label; progress = -1f
            try { withContext(Dispatchers.IO) { work(client()) }; say(done) } catch (e: Exception) { say(e.message ?: "Operazione non riuscita") }
            busy = null; progress = -1f; load()
        }
    }
    LaunchedEffect(address, path) { if (address != null) load() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp)) {
            if (address == null) ConnectCard(host, onAddress = { a -> address = a; host.saveHost(a); path = "/"; status = null })
            else {
                DeviceCard(address!!, status, failed, onJoin = { host.joinReaderWifi { ok -> if (ok) load() } }, onRetry = { load() }, onForget = { host.leaveReaderWifi(); host.saveHost(null); address = null; status = null; entries = null; failed = null })
                if (entries != null) {
                    // Where you are, as a path you can tap back through.
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val parts = path.split('/').filter { it.isNotEmpty() }
                        Text("Lettore", Modifier.clickable { path = "/" }.padding(vertical = 8.dp, horizontal = 4.dp), style = MaterialTheme.typography.titleMedium,
                            color = if (parts.isEmpty()) colors.onSurface else colors.primary)
                        parts.forEachIndexed { i, p ->
                            Text(" › ", color = colors.onSurfaceVariant)
                            Text(p, Modifier.clickable { path = "/" + parts.take(i + 1).joinToString("/") }.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium,
                                color = if (i == parts.lastIndex) colors.onSurface else colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { dialog = "send" }, Modifier.weight(1f)) {
                            Icon(painterResource(R.drawable.ic_upload), null, Modifier.size(18.dp)); Text("Invia libri", Modifier.padding(start = 8.dp)) }
                        OutlinedButton(onClick = { dialog = "mkdir" }, Modifier.weight(1f)) {
                            Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(18.dp)); Text("Nuova cartella", Modifier.padding(start = 8.dp)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    val list = entries!!
                    OutlinedCard(Modifier.fillMaxWidth(), shape = Round20, colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainer)) {
                        if (path != "/") EntryRow(R.drawable.ic_back, "Cartella superiore", "", false, false) { path = path.substringBeforeLast('/').ifEmpty { "/" } }
                        if (list.isEmpty()) Text("Cartella vuota.", Modifier.padding(18.dp), color = colors.onSurfaceVariant)
                        list.forEachIndexed { i, e ->
                            if (i > 0 || path != "/") HorizontalDivider(color = colors.outlineVariant)
                            val book = e.name.substringAfterLast('.', "").uppercase(Locale.ROOT).matches(Regex(BOOKS))
                            EntryRow(if (e.isDirectory) R.drawable.ic_folder else R.drawable.ic_book, e.name,
                                if (e.isDirectory) "Cartella" else size(e.size), !e.isDirectory && book && host.inLibrary(e.name), true,
                                onMore = { target = e; dialog = "actions" }) {
                                if (e.isDirectory) path = child(path, e.name) else { target = e; dialog = "actions" }
                            }
                        }
                    }
                    Text("Sul lettore lascia aperta la schermata di trasferimento file finché lavori qui.", Modifier.padding(start = 6.dp, top = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        busy?.let { label ->
            Surface(Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.surfaceContainer, shadowElevation = 3.dp) {
                Column(Modifier.padding(14.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    if (progress >= 0f) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    val e = target
    when (dialog) {
        "mkdir" -> NameDialog("Nuova cartella", "", "Crea", { dialog = null }) { name -> dialog = null; run("Creo la cartella…", "Cartella «$name» creata") { it.mkdir(path, name) } }
        "actions" -> if (e != null) {
            val book = e.name.substringAfterLast('.', "").uppercase(Locale.ROOT).matches(Regex(BOOKS))
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = { Column {
                    if (e.isDirectory) ActionRow(R.drawable.ic_folder, "Apri") { dialog = null; path = child(path, e.name) }
                    if (!e.isDirectory && book) ActionRow(R.drawable.ic_download, "Scarica in Atlante") {
                        dialog = null
                        scope.launch {
                            busy = "Scarico «${e.name}»…"; progress = 0f
                            try {
                                val dir = File(host.cacheDir(), "crosspoint").apply { mkdirs() }; val dest = File(dir, e.name)
                                withContext(Dispatchers.IO) { client().download(child(path, e.name), dest) { d, t -> if (t > 0) progress = d.toFloat() / t } }
                                busy = null; host.importFiles(listOf(dest))
                            } catch (x: Exception) { busy = null; say(x.message ?: "Scaricamento non riuscito") }
                            progress = -1f
                        }
                    }
                    ActionRow(R.drawable.ic_edit, "Rinomina") { dialog = "rename" }
                    if (!e.isDirectory) ActionRow(R.drawable.ic_chevron_right, "Sposta in un’altra cartella") { dialog = "move" }
                    ActionRow(R.drawable.ic_close, "Elimina") { dialog = "delete" }
                } },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Chiudi") } })
        }
        "rename" -> if (e != null) NameDialog("Rinomina", e.name, "Rinomina", { dialog = null }) { name ->
            dialog = null; run("Rinomino…", "Rinominato in «$name»") { it.rename(child(path, e.name), name) } }
        "move" -> if (e != null) MoveDialog(path, entries.orEmpty().filter { it.isDirectory }.map { child(path, it.name) }, { dialog = null }) { dest ->
            dialog = null; run("Sposto…", "Spostato in $dest") { it.move(child(path, e.name), dest) } }
        "delete" -> if (e != null) AlertDialog(onDismissRequest = { dialog = null },
            title = { Text(if (e.isDirectory) "Eliminare la cartella?" else "Eliminare il libro dal lettore?") },
            text = { Text("«${e.name}» viene cancellato dalla scheda del lettore e non si può recuperare." + if (e.isDirectory) " Il lettore elimina solo cartelle vuote." else " In Atlante non cambia nulla.") },
            confirmButton = { TextButton(onClick = { dialog = null; run("Elimino…", "«${e.name}» eliminato") { it.delete(child(path, e.name), e.isDirectory) } }) { Text("Elimina", color = colors.error) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annulla") } })
        "send" -> SendDialog(host.libraryFiles(), { dialog = null }) { chosen ->
            dialog = null
            scope.launch {
                var sent = 0; var error: String? = null
                for ((i, pair) in chosen.withIndex()) {
                    val (title, file) = pair
                    busy = "Invio ${i + 1} di ${chosen.size}: $title"; progress = 0f
                    try { withContext(Dispatchers.IO) { client().upload(path, file, title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim() + "." + file.extension) { d, t -> if (t > 0) progress = d.toFloat() / t } }; sent++ }
                    catch (x: Exception) { error = x.message; break }
                }
                busy = null; progress = -1f
                say(if (error == null) "$sent " + (if (sent == 1) "libro inviato" else "libri inviati") + " a CrossPoint" else "Inviati $sent: $error")
                load()
            }
        }
    }
}

@Composable private fun ConnectCard(host: CrossPointHost, onAddress: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var joinFailed by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<CrossPointClient.Device>?>(null) }
    var typed by remember { mutableStateOf("") }
    OutlinedCard(Modifier.fillMaxWidth(), shape = Round20, colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainer)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(lerp(colors.surfaceContainer, colors.primary, .14f)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_ereader), null, Modifier.size(24.dp), tint = colors.primary) }
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Collega CrossPoint", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif))
                    Text("Gestisci i libri del lettore da qui.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            Text("Sul lettore apri il trasferimento file: crea la rete Wi-Fi «${CrossPointClient.READER_WIFI}». Il telefono ci si collega; la rete non ha internet, è normale.",
                Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Button(onClick = {
                joining = true; joinFailed = false
                host.joinReaderWifi { ok -> joining = false; if (ok) onAddress(CrossPointClient.READER_ADDRESS) else joinFailed = true }
            }, Modifier.fillMaxWidth().padding(top = 14.dp).height(48.dp), enabled = !joining) {
                Icon(painterResource(R.drawable.ic_ereader), null, Modifier.size(20.dp))
                Text(if (joining) "Mi collego a «${CrossPointClient.READER_WIFI}»…" else "Collegati al Wi-Fi del lettore", Modifier.padding(start = 8.dp)) }
            if (joinFailed) Text("Non collegato. Scegli «${CrossPointClient.READER_WIFI}» nelle impostazioni Wi-Fi del telefono, poi torna qui.",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = colors.tertiary)
            TextButton(onClick = { onAddress(CrossPointClient.READER_ADDRESS) }, Modifier.fillMaxWidth()) { Text("Il telefono è già sul Wi-Fi del lettore") }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.outlineVariant)
            Text("Lettore collegato al Wi-Fi di casa", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = {
                searching = true; found = null
                scope.launch { found = withContext(Dispatchers.IO) { runCatching { CrossPointClient.discover(binder = host.binder()) }.getOrDefault(emptyList()) }; searching = false
                    found?.singleOrNull()?.let { onAddress(it.host) } }
            }, Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp), enabled = !searching) {
                Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp)); Text(if (searching) "Cerco sulla rete…" else "Cerca CrossPoint", Modifier.padding(start = 8.dp)) }
            found?.let { list ->
                if (list.isEmpty()) Text("Nessun lettore trovato. Controlla che il trasferimento file sia aperto, oppure scrivi l’indirizzo che mostra.",
                    Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                else if (list.size > 1) list.forEach { d -> TextButton(onClick = { onAddress(d.host) }) { Text("${d.name} · ${d.host}") } }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = colors.outlineVariant)
            Text("Oppure l’indirizzo che il lettore mostra", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(typed, { typed = it.trim() }, Modifier.weight(1f), singleLine = true, placeholder = { Text("192.168.1.20") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = typed.isNotEmpty() && !CrossPointClient.isLocal(typed.removePrefix("http://").trimEnd('/')),
                    supportingText = { if (typed.isNotEmpty() && !CrossPointClient.isLocal(typed.removePrefix("http://").trimEnd('/'))) Text("Un indirizzo della rete di casa") })
                FilledTonalButton(onClick = { onAddress(typed.removePrefix("http://").trimEnd('/')) }, Modifier.padding(start = 8.dp),
                    enabled = CrossPointClient.isLocal(typed.removePrefix("http://").trimEnd('/'))) { Text("Collega") }
            }
        }
    }
}

@Composable private fun DeviceCard(address: String, status: CrossPointClient.Status?, failed: String?, onJoin: () -> Unit, onRetry: () -> Unit, onForget: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(Modifier.fillMaxWidth(), shape = Round20, colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainer)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tint = if (failed != null) colors.tertiary else colors.primary
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(lerp(colors.surfaceContainer, tint, .14f)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (failed != null) R.drawable.ic_warning else R.drawable.ic_ereader), null, Modifier.size(24.dp), tint = tint) }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(if (failed != null) "Non raggiungibile" else if (status == null) "Collegamento…" else "CrossPoint collegato", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif))
                    Text(when {
                        failed != null -> failed
                        status == null -> address
                        else -> "$address · versione ${status.version}" + if (status.mode == "STA") " · segnale " + signal(status.rssi) else " · Wi-Fi del lettore"
                    }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed != null && address == CrossPointClient.READER_ADDRESS) FilledTonalButton(onClick = onJoin) { Text("Collegati al Wi-Fi del lettore") }
                if (failed != null) TextButton(onClick = onRetry) { Text("Riprova") }
                TextButton(onClick = onForget) { Text("Cambia lettore") }
            }
        }
    }
}

private fun signal(rssi: Int) = when { rssi >= -55 -> "ottimo"; rssi >= -67 -> "buono"; rssi >= -75 -> "discreto"; else -> "debole" }

@Composable private fun EntryRow(icon: Int, title: String, detail: String, inLibrary: Boolean, more: Boolean, onMore: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
        .semantics(mergeDescendants = true) { contentDescription = title + (if (detail.isEmpty()) "" else ", $detail") + if (inLibrary) ", già in Atlante" else "" },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(lerp(colors.surfaceContainer, colors.primary, .12f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(22.dp), tint = colors.primary) }
        Column(Modifier.padding(start = 14.dp, end = 8.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (inLibrary) Text("  · In Atlante", style = MaterialTheme.typography.bodySmall, color = colors.primary)
            }
        }
        if (more && onMore != null) IconButton(onClick = onMore) { Icon(painterResource(R.drawable.ic_more), "Azioni su $title", tint = colors.onSurfaceVariant) }
    }
}

@Composable private fun ActionRow(icon: Int, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onName: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank() && !name.contains('/') && !name.startsWith(".")
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nome") }, isError = name.isNotEmpty() && !valid,
            supportingText = { if (name.isNotEmpty() && !valid) Text("Senza «/» e senza punto iniziale") }) },
        confirmButton = { TextButton(onClick = { onName(name.trim()) }, enabled = valid) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

@Composable private fun MoveDialog(path: String, subfolders: List<String>, onDismiss: () -> Unit, onDest: (String) -> Unit) {
    var dest by remember { mutableStateOf(if (path == "/") "/" else path.substringBeforeLast('/').ifEmpty { "/" }) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sposta in") },
        text = { Column {
            OutlinedTextField(dest, { dest = it }, singleLine = true, label = { Text("Cartella") }, supportingText = { Text("Per esempio /Libri/Fantasy") })
            if (path != "/") TextButton(onClick = { dest = "/" }) { Text("Cartella principale /") }
            subfolders.take(8).forEach { f -> TextButton(onClick = { dest = f }) { Text(f, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        } },
        confirmButton = { TextButton(onClick = { onDest(dest.trim().ifEmpty { "/" }) }, enabled = dest.trim().startsWith("/") && dest.trim() != path) { Text("Sposta") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

@Composable private fun SendDialog(books: List<Pair<String, File>>, onDismiss: () -> Unit, onSend: (List<Pair<String, File>>) -> Unit) {
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(setOf<File>()) }
    val shown = books.filter { query.isBlank() || it.first.contains(query.trim(), true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Invia a CrossPoint") },
        text = { Column {
            if (books.isEmpty()) Text("Nessun libro con il suo file in Atlante: importa prima un EPUB (Impostazioni › Libreria e catalogo › Importa eBook).")
            else {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Cerca nella biblioteca") }, modifier = Modifier.fillMaxWidth())
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    shown.forEach { (title, file) ->
                        val on = file in chosen
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { chosen = if (on) chosen - file else chosen + file }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(file.extension.uppercase(Locale.ROOT) + " · " + size(file.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        } },
        confirmButton = { TextButton(onClick = { onSend(books.filter { it.second in chosen }) }, enabled = chosen.isNotEmpty()) { Text(if (chosen.isEmpty()) "Invia" else "Invia ${chosen.size}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}
