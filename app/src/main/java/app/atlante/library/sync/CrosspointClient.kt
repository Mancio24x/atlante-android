package app.atlante.library.sync

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends books to a CrossPoint Reader (e-ink firmware) on the same Wi-Fi: its file manager answers at
 * http://crosspoint.local (or the device's IP) and accepts a multipart upload on /upload.
 * Local network only, plain HTTP, no account: nothing about the library leaves the phone except the file chosen.
 */
object CrosspointClient {
    const val DEFAULT_HOST = "crosspoint.local"

    /** "crosspoint.local", "192.168.1.20" or a full http:// address -> base URL without trailing slash; anything else is rejected. */
    fun baseUrl(input: String): String {
        val raw = input.trim().ifEmpty { DEFAULT_HOST }
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        val url = runCatching { java.net.URI(withScheme) }.getOrNull()
        if (url == null || !"http".equals(url.scheme, true) || url.host.isNullOrEmpty() || url.userInfo != null || url.query != null || url.fragment != null)
            throw IOException("Indirizzo CrossPoint non valido: usa ad esempio $DEFAULT_HOST o l’IP mostrato sul lettore")
        return "http://" + url.host + (if (url.port > 0) ":${url.port}" else "")
    }

    /** True when the reader answers; false on any network error (never throws). */
    fun reachable(host: String): Boolean = runCatching {
        val c = URL(baseUrl(host) + "/").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        try { c.responseCode in 200..399 } finally { c.disconnect() }
    }.getOrDefault(false)

    /** Uploads [file] under [name] (the device wants plain file names) into [folder] of the reader. */
    fun upload(host: String, file: File, name: String = file.name, folder: String = "/") {
        if (!file.isFile) throw IOException("Il file del libro non esiste")
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").take(120).ifEmpty { "libro" }
        val boundary = "----atlante" + System.nanoTime()
        val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safe\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val path = java.net.URLEncoder.encode(folder, "UTF-8")
        val c = URL(baseUrl(host) + "/upload?path=$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 60000
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setFixedLengthStreamingMode(head.size + file.length() + tail.size)
            c.outputStream.use { o -> o.write(head); file.inputStream().use { it.copyTo(o) }; o.write(tail) }
            if (c.responseCode !in 200..299) throw IOException("CrossPoint ha risposto HTTP ${c.responseCode}")
        } catch (e: java.net.UnknownHostException) {
            throw IOException("CrossPoint non trovato: accendi il Wi-Fi del lettore (modalità trasferimento file) e usa la stessa rete")
        } finally { c.disconnect() }
    }
}
