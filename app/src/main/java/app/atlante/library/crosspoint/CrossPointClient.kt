package app.atlante.library.crosspoint

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder

/**
 * The CrossPoint e-ink reader's file server (firmware's CrossPointWebServer): status, folders, upload, download,
 * new folder, rename, move, delete, and its discovery on the Wi-Fi ("hello" on UDP 8134).
 *
 * It speaks plain HTTP on the home network. Android blocks clear-text HTTP for the whole app (and it should stay
 * blocked towards the internet), so this client talks HTTP/1.1 over a socket and only to private addresses
 * (192.168.x.x, 10.x.x.x, 172.16-31.x.x): nothing leaves the home network unencrypted.
 */
class CrossPointClient @JvmOverloads constructor(val host: String, private val port: Int = 80, localOnly: Boolean = true, private val binder: Binder? = null) {
    /**
     * Puts a socket on a given network. The reader's own Wi-Fi ("CrossPoint-Reader") has no internet, so Android
     * would send the traffic over mobile data: the app binds its sockets to the Wi-Fi network instead.
     */
    interface Binder { fun bind(socket: Socket); fun bind(socket: DatagramSocket) }
    init { if (localOnly) require(isLocal(host)) { "Solo indirizzi della rete di casa, per esempio 192.168.1.20" } }

    data class Status(val version: String, val ip: String, val mode: String, val rssi: Int, val uptime: Long)
    data class Entry(val name: String, val size: Long, val isDirectory: Boolean, val isEpub: Boolean)
    data class Device(val host: String, val name: String)
    fun interface Progress { fun update(done: Long, total: Long) }

    fun status(): Status { val j = JSONObject(text(request("GET", "/api/status")))
        return Status(j.optString("version"), j.optString("ip", host), j.optString("mode"), j.optInt("rssi"), j.optLong("uptime")) }

    fun list(path: String): List<Entry> {
        val a = JSONArray(text(request("GET", "/api/files?path=" + enc(path))))
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Entry(it.optString("name"), it.optLong("size"), it.optBoolean("isDirectory"), it.optBoolean("isEpub")) }
            .sortedWith(compareBy<Entry>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun mkdir(parent: String, name: String) { form("/mkdir", "name" to name, "path" to parent) }
    fun rename(path: String, name: String) { form("/rename", "path" to path, "name" to name) }
    fun move(path: String, destFolder: String) { form("/move", "path" to path, "dest" to destFolder) }
    fun delete(path: String, folder: Boolean) { form("/delete", "path" to path, "type" to if (folder) "folder" else "file") }

    /** Downloads a file of the reader into [dest]. */
    fun download(path: String, dest: File, progress: Progress? = null) {
        open("GET", "/download?path=" + enc(path), null, 0) { out -> } .use { r ->
            FileOutputStream(dest).use { out -> r.body(out, progress) }
        }
    }

    /** Sends [file] to [folder] on the reader, under [name] (the server overwrites a file with the same name). */
    fun upload(folder: String, file: File, name: String = file.name, progress: Progress? = null) {
        val boundary = "AtlanteBoundary" + System.nanoTime()
        val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name.replace("\"", "'") + "\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val total = head.size + file.length() + tail.size
        open("POST", "/upload?path=" + enc(folder), "multipart/form-data; boundary=$boundary", total) { out ->
            out.write(head)
            FileInputStream(file).use { input -> val buffer = ByteArray(16384); var done = 0L; var n: Int
                while (input.read(buffer).also { n = it } != -1) { out.write(buffer, 0, n); done += n; progress?.update(done, file.length()) } }
            out.write(tail)
        }.use { r -> r.body(ByteArrayOutputStream(), null) }
    }

    private fun form(path: String, vararg fields: Pair<String, String>) {
        val body = fields.joinToString("&") { (k, v) -> k + "=" + enc(v) }.toByteArray(Charsets.UTF_8)
        open("POST", path, "application/x-www-form-urlencoded", body.size.toLong()) { it.write(body) }.use { it.body(ByteArrayOutputStream(), null) }
    }

    private fun request(method: String, path: String): ByteArray = open(method, path, null, 0) { }.use { r ->
        ByteArrayOutputStream().also { r.body(it, null) }.toByteArray() }

    /** One HTTP/1.1 exchange on its own connection ("Connection: close"); a status other than 2xx is an error with the server's text. */
    private fun open(method: String, path: String, type: String?, length: Long, write: (OutputStream) -> Unit): Response {
        val socket = Socket()
        try {
            binder?.bind(socket)
            socket.connect(InetSocketAddress(host, port), 4000); socket.soTimeout = 20000
            val out = socket.getOutputStream().buffered(32768)
            val headers = StringBuilder("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: Atlante\r\n")
            if (type != null) headers.append("Content-Type: $type\r\n")
            if (method == "POST") headers.append("Content-Length: $length\r\n")
            out.write(headers.append("\r\n").toString().toByteArray(Charsets.UTF_8)); write(out); out.flush()
            val r = Response(socket, BufferedInputStream(socket.getInputStream(), 32768))
            if (r.code !in 200..299) { val msg = text(ByteArrayOutputStream().also { r.body(it, null) }.toByteArray()); r.close(); throw IOException(msg.ifBlank { "CrossPoint ha risposto ${r.code}" }) }
            return r
        } catch (e: SocketTimeoutException) { socket.close(); throw IOException("CrossPoint non risponde: è acceso, con il trasferimento file aperto?") }
        catch (e: IOException) { socket.close(); throw e }
    }

    private class Response(private val socket: Socket, private val input: InputStream) : AutoCloseable {
        val code: Int; private val headers = HashMap<String, String>()
        init {
            val status = line(); code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Risposta non valida da CrossPoint")
            while (true) { val l = line(); if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
        }
        private fun line(): String { val b = ByteArrayOutputStream(); while (true) { val c = input.read(); if (c == -1 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }; return b.toString("UTF-8") }
        /** The body, plain or chunked (the server streams long lists in chunks). */
        fun body(out: OutputStream, progress: Progress?) {
            val buffer = ByteArray(32768); var done = 0L
            val total = headers["content-length"]?.toLongOrNull() ?: -1
            if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
                while (true) {
                    val size = line().substringBefore(';').trim().toLongOrNull(16) ?: break
                    if (size == 0L) break
                    var left = size
                    while (left > 0) { val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()); if (n == -1) throw IOException("Trasferimento interrotto")
                        out.write(buffer, 0, n); left -= n; done += n; progress?.update(done, total) }
                    line()
                }
            } else {
                var left = if (total >= 0) total else Long.MAX_VALUE
                while (left > 0) { val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()); if (n == -1) break
                    out.write(buffer, 0, n); left -= n; done += n; progress?.update(done, total) }
                if (total >= 0 && done < total) throw IOException("Trasferimento interrotto")
            }
        }
        override fun close() { runCatching { socket.close() } }
    }

    companion object {
        const val DISCOVERY_PORT = 8134
        /** The reader's own Wi-Fi (open, no internet) and its address there (firmware: softAP defaults). */
        const val READER_WIFI = "CrossPoint-Reader"
        const val READER_ADDRESS = "192.168.4.1"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun text(b: ByteArray) = String(b, Charsets.UTF_8)

        /** A private IPv4 address of the home network (no names: they could resolve outside it). */
        @JvmStatic fun isLocal(host: String): Boolean {
            val p = host.trim().split('.'); if (p.size != 4) return false
            val n = p.map { it.toIntOrNull() ?: return false }; if (n.any { it !in 0..255 }) return false
            return n[0] == 10 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1] in 16..31)
        }

        /** "crosspoint (on CrossPoint-ab12);81" → the device name. */
        @JvmStatic fun deviceName(reply: String): String? =
            Regex("^crosspoint \\(on (.+)\\);\\d+$").find(reply.trim())?.groupValues?.get(1)

        /** Readers on the Wi-Fi: a "hello" broadcast, every CrossPoint answers with its name; at most [millis]. */
        @JvmStatic @JvmOverloads fun discover(millis: Int = 2500, binder: Binder? = null): List<Device> {
            val found = LinkedHashMap<String, Device>()
            DatagramSocket().use { s ->
                binder?.bind(s)
                s.broadcast = true; s.soTimeout = 400
                val hello = "hello".toByteArray()
                runCatching { s.send(DatagramPacket(hello, hello.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)) }
                val end = System.currentTimeMillis() + millis; val buffer = ByteArray(256)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buffer, buffer.size)
                    try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                    val address = (p.address as? Inet4Address)?.hostAddress ?: continue
                    val name = deviceName(String(p.data, 0, p.length, Charsets.UTF_8)) ?: continue
                    if (isLocal(address)) found[address] = Device(address, name)
                }
            }
            return found.values.toList()
        }
    }
}
