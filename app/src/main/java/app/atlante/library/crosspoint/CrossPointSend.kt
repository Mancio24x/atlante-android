package app.atlante.library.crosspoint

import java.io.File
import java.io.IOException

/**
 * "Invia a CrossPoint" from a book's actions: sends the book's own file to the reader in one tap, to the same root
 * folder the CrossPoint page opens on. It uses the address saved by the CrossPoint page; if that does not answer
 * (the reader changed address, or is on its own Wi-Fi), it looks for the reader on the Wi-Fi once.
 */
object CrossPointSend {
    /** File name on the reader: the book's title (characters the SD card refuses taken out) and the file's extension. */
    @JvmStatic fun fileName(title: String, file: File): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(120)
        val ext = file.extension
        return (clean.ifEmpty { file.nameWithoutExtension }) + if (ext.isEmpty()) "" else ".$ext"
    }

    /** Sends [file] and returns the reader's address used (to remember it). Runs on a background thread. */
    @JvmStatic fun send(savedHost: String?, file: File, title: String, binder: CrossPointClient.Binder?, progress: CrossPointClient.Progress?): String {
        if (!file.isFile) throw IOException("Il file di questo libro non è sul telefono.")
        val host = reachable(savedHost, binder) ?: throw IOException(
            "CrossPoint non trovato. Sul lettore apri «Trasferimento file», poi riprova o apri la pagina CrossPoint per collegarti.")
        CrossPointClient(host, binder = binder).upload("/", file, fileName(title, file), progress)
        return host
    }

    private fun reachable(saved: String?, binder: CrossPointClient.Binder?): String? {
        if (!saved.isNullOrBlank() && CrossPointClient.isLocal(saved)) {
            if (runCatching { CrossPointClient(saved, binder = binder).status() }.isSuccess) return saved
        }
        return runCatching { CrossPointClient.discover(binder = binder) }.getOrDefault(emptyList()).firstOrNull()?.host
    }
}
