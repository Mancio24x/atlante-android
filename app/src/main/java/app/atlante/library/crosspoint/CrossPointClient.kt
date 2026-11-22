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

        /** The name the reader announces on the home Wi-Fi (multicast DNS), shown on its file-transfer screen. */
        const val MDNS_NAME = "crosspoint.local"
        private const val MDNS_PORT = 5353

        /** A multicast-DNS name of the home network ("crosspoint.local"): one label, then ".local". */
        @JvmStatic fun isMdnsName(host: String): Boolean = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.local\\.?$", RegexOption.IGNORE_CASE).matches(host.trim())

        /** What the user typed ("http://crosspoint.local/", " 192.168.1.20 ") as a bare host. */
        @JvmStatic fun bareHost(typed: String): String = typed.trim().removePrefix("http://").removePrefix("HTTP://").substringBefore('/').substringBefore(':').trim()

        /** A query for the A record (IPv4) of [name], asking for a unicast answer (RFC 6762 §5.4). */
        @JvmStatic fun mdnsQuery(name: String, id: Int = 0): ByteArray {
            val out = ByteArrayOutputStream()
            fun u16(v: Int) { out.write(v shr 8 and 0xff); out.write(v and 0xff) }
            u16(id); u16(0); u16(1); u16(0); u16(0); u16(0)
            for (label in name.trim().trimEnd('.').split('.')) { val b = label.toByteArray(Charsets.UTF_8); out.write(b.size); out.write(b) }
            out.write(0); u16(1); u16(0x8001)                                   // type A, class IN with the "unicast response" bit
            return out.toByteArray()
        }

        /** The IPv4 address an mDNS answer gives for [name], or null (another name, no A record, a broken packet). */
        @JvmStatic fun mdnsAnswer(packet: ByteArray, length: Int, name: String): String? = runCatching {
            fun u16(at: Int) = (packet[at].toInt() and 0xff shl 8) or (packet[at + 1].toInt() and 0xff)
            fun readName(start: Int): Pair<String, Int> {           // name and the offset after it (compression pointers followed)
                val labels = ArrayList<String>(); var at = start; var end = -1; var jumps = 0
                while (true) {
                    require(at < length); val len = packet[at].toInt() and 0xff
                    when {
                        len == 0 -> { if (end < 0) end = at + 1; break }
                        len and 0xc0 == 0xc0 -> { require(++jumps < 20); if (end < 0) end = at + 2; at = ((len and 0x3f) shl 8) or (packet[at + 1].toInt() and 0xff) }
                        else -> { require(at + 1 + len <= length); labels += String(packet, at + 1, len, Charsets.UTF_8); at += 1 + len }
                    }
                }
                return labels.joinToString(".") to end
            }
            require(length >= 12 && u16(2) and 0x8000 != 0)                     // a response
            val questions = u16(4); val records = u16(6) + u16(8) + u16(10)
            var at = 12
            repeat(questions) { at = readName(at).second + 4 }
            val wanted = name.trim().trimEnd('.')
            repeat(records) {
                val (n, after) = readName(at); val type = u16(after); val size = u16(after + 8); val data = after + 10
                require(data + size <= length)
                if (type == 1 && size == 4 && n.equals(wanted, ignoreCase = true))
                    return@runCatching (0 until 4).joinToString(".") { i -> (packet[data + i].toInt() and 0xff).toString() }
                at = data + size
            }
            null
        }.getOrNull()

        /**
         * Finds the address of [name] ("crosspoint.local") on the Wi-Fi with a multicast-DNS question; only a private
         * address of the home network is accepted. Null when nobody answers within [millis].
         */
        @JvmStatic @JvmOverloads fun resolveLocal(name: String = MDNS_NAME, millis: Int = 2000, binder: Binder? = null): String? {
            if (!isMdnsName(name)) return null
            DatagramSocket().use { s ->
                binder?.bind(s); s.soTimeout = 300
                val query = mdnsQuery(name)
                val group = InetAddress.getByName("224.0.0.251"); val buffer = ByteArray(1500)
                val end = System.currentTimeMillis() + millis; var next = 0L
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() >= next) { runCatching { s.send(DatagramPacket(query, query.size, group, MDNS_PORT)) }; next = System.currentTimeMillis() + 700 }
                    val p = DatagramPacket(buffer, buffer.size)
                    try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                    val ip = mdnsAnswer(p.data, p.length, name) ?: continue
                    if (isLocal(ip)) return ip
                }
            }
            return null
        }

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
            // Routers that drop broadcasts still pass multicast DNS: ask for "crosspoint.local" too.
            if (found.isEmpty()) runCatching { resolveLocal(MDNS_NAME, 1500, binder) }.getOrNull()?.let { found[it] = Device(it, MDNS_NAME) }
            return found.values.toList()
        }
    }
}
