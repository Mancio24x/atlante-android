package app.atlante.library.crosspoint

import java.io.File

/**
 * «Invia a CrossPoint» from a book's sheet: one book straight to the reader, without opening the CrossPoint page.
 * The reader is the saved address when it answers, otherwise the first one found on the Wi-Fi.
 */
object CrossPointSend {
    /** Folder on the reader's card where books sent from a sheet land (the root, like the reader's own uploads). */
    const val FOLDER = "/"

    /** File name on the reader: the title (characters a FAT card refuses become spaces) and the file's extension. */
    @JvmStatic fun fileName(title: String, file: File): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(120).trim()
        val stem = clean.ifEmpty { file.nameWithoutExtension }
        return if (file.extension.isEmpty()) stem else "$stem.${file.extension.lowercase()}"
    }

    /** Address of a reader that answers now: [saved] first, then discovery; null when none answers. */
    @JvmStatic @JvmOverloads fun reach(saved: String?, binder: CrossPointClient.Binder?, discover: (CrossPointClient.Binder?) -> List<CrossPointClient.Device> = { CrossPointClient.discover(2500, it) },
                                      answers: (String) -> Boolean = { h -> runCatching { CrossPointClient(h, binder = binder).status() }.isSuccess }): String? {
        val s = saved?.trim()
        if (!s.isNullOrEmpty() && CrossPointClient.isLocal(s) && answers(s)) return s
        return runCatching { discover(binder) }.getOrDefault(emptyList()).firstOrNull { CrossPointClient.isLocal(it.host) }?.host
    }

    /** Sends [file] as [title] to the reader at [host]; [progress] gets 0..100. Throws when the reader refuses it. */
    @JvmStatic fun send(host: String, file: File, title: String, binder: CrossPointClient.Binder?, progress: ((Int) -> Unit)?) {
        require(file.isFile) { "Il file di questo libro non è sul telefono" }
        CrossPointClient(host, binder = binder).upload(FOLDER, file, fileName(title, file)) { d, t -> if (t > 0) progress?.invoke((d * 100 / t).toInt()) }
    }
}
