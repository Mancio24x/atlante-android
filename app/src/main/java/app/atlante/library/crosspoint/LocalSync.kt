package app.atlante.library.crosspoint

import app.atlante.library.catalog.BibliographicEngine
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import app.atlante.library.sync.SyncCore
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * "Atlante locale": Atlante is the reading-progress sync server of the CrossPoint reader, on the same Wi-Fi, with
 * no account and no internet. The reader's own KOReader Sync (Impostazioni › KOReader Sync: server
 * `<phone IP>:7200`, user `atlante`, the PIN shown in the app) talks the standard KOSync protocol:
 *
 *   POST /users/create, GET /users/auth (headers x-auth-user / x-auth-key = MD5 of the password),
 *   PUT /syncs/progress {document, progress, percentage, device, device_id, metadata?{filename,title,authors,isbn}},
 *   GET /syncs/progress/<document>  → the last position the reader sent (its own XPath, never one made up here).
 *
 * The document is matched to a book of the library by the file Atlante sent (same name or same KOReader hash),
 * then by ISBN or title and author ([BibliographicEngine.sameWorkIn]). Progress only moves a book forward, and a
 * book you marked «Letto» is not touched: the reader never undoes what you recorded in Atlante.
 */
class LocalSync(private val library: Library, private val records: File, private val pin: () -> String) {
    interface Library {
        fun books(): List<Book>
        /** The book's own file in Atlante, if it has one (to match the reader's KOReader hash). */
        fun file(book: Book): File?
        fun replace(book: Book)
    }
    data class Reply(val code: Int, val body: String)
    /** What a sync did: the book moved to [percent], or only remembered when [book] is null or nothing changed. */
    data class Event(val book: Book?, val percent: Int, val applied: Boolean, val title: String)

    @Volatile var onEvent: ((Event) -> Unit)? = null
    private val lock = Any()

    fun handle(method: String, rawPath: String, headers: Map<String, String>, body: String): Reply {
        val path = rawPath.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        return try {
            when {
                method == "GET" && (path == "/healthcheck" || path == "/") -> ok(JSONObject().put("state", "OK"))
                method == "POST" && path == "/users/create" -> {
                    val j = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                    if (j.optString("username") == USER && keyOk(j.optString("password"))) Reply(201, JSONObject().put("username", USER).toString())
                    else { Thread.sleep(400); Reply(402, JSONObject().put("code", 2002).put("message", "Username is already registered.").toString()) }
                }
                !authorized(headers) -> { Thread.sleep(400); Reply(401,   // a slow "no": guessing the PIN takes days
                    JSONObject().put("code", 2001).put("message", "Unauthorized").toString()) }
                method == "GET" && path == "/users/auth" -> ok(JSONObject().put("authorized", "OK"))
                method == "PUT" && path == "/syncs/progress" -> put(JSONObject(body))
                method == "GET" && path.startsWith("/syncs/progress/") -> get(path.removePrefix("/syncs/progress/"))
                else -> Reply(404, JSONObject().put("message", "Not found").toString())
            }
        } catch (e: Exception) { Reply(400, JSONObject().put("code", 2003).put("message", "Invalid request: " + e.javaClass.simpleName).toString()) }
    }

    private fun ok(j: JSONObject) = Reply(200, j.toString())
    private fun keyOk(key: String) = key.isNotEmpty() && key.equals(SyncCore.md5(pin()), ignoreCase = true)
    private fun authorized(h: Map<String, String>) = h["x-auth-user"] == USER && keyOk(h["x-auth-key"].orEmpty())

    private fun get(document: String): Reply {
        if (!document.matches(DOCUMENT)) return Reply(400, JSONObject().put("message", "Invalid document").toString())
        val r = synchronized(lock) { load().optJSONObject(document.lowercase(Locale.ROOT)) } ?: return Reply(204, "")
        r.remove("book")
        return ok(r.put("document", document))
    }

    private fun put(j: JSONObject): Reply {
        val document = j.optString("document").lowercase(Locale.ROOT)
        if (!document.matches(DOCUMENT)) return Reply(400, JSONObject().put("message", "Invalid document").toString())
        val p = j.optDouble("percentage", Double.NaN)
        if (!p.isFinite() || p < 0 || p > 1) return Reply(400, JSONObject().put("message", "Invalid percentage").toString())
        val now = System.currentTimeMillis() / 1000
        val meta = j.optJSONObject("metadata")
        val event = synchronized(lock) {
            val all = load()
            val known = all.optJSONObject(document)?.optString("book").orEmpty()
            val books = library.books()
            val book = books.firstOrNull { it.id == known } ?: match(document, meta, books)
            val r = JSONObject().put("progress", j.optString("progress")).put("percentage", p).put("device", j.optString("device"))
                .put("device_id", j.optString("device_id")).put("timestamp", now)
            j.optJSONObject("position")?.let { r.put("position", it) }
            if (book != null) r.put("book", book.id)
            meta?.optString("title")?.takeIf { it.isNotBlank() }?.let { r.put("title", it) }
            all.put(document, r); save(all)
            val applied = book != null && apply(book, p)
            Event(book, (p * 100).roundToInt(), applied, book?.title ?: meta?.optString("title").orEmpty().ifBlank { meta?.optString("filename").orEmpty() })
        }
        runCatching { onEvent?.invoke(event) }
        return ok(JSONObject().put("document", document).put("timestamp", now))
    }

    /** The book of the library this document is: the file Atlante sent, else the same work by ISBN or title and author. */
    fun match(document: String, meta: JSONObject?, books: List<Book>): Book? {
        val filename = meta?.optString("filename").orEmpty()
        val withFile = books.filter { it.format != "CATALOGO" }
        withFile.firstOrNull { val n = sentName(it); n == filename || SyncCore.md5(n) == document }?.let { return it }
        withFile.firstOrNull { val f = library.file(it); f != null && f.isFile && runCatching { partialMd5(f) }.getOrNull() == document }?.let { return it }
        val title = meta?.optString("title").orEmpty().ifBlank { filename.substringBeforeLast('.') }
        if (title.isBlank()) return null
        val candidate = Book().apply {
            this.title = title.trim(); meta?.optString("authors")?.takeIf { it.isNotBlank() }?.let { author = it.trim() }
            isbn = meta?.optString("isbn").orEmpty().filter { it.isDigit() || it == 'X' || it == 'x' }
        }
        return runCatching { BibliographicEngine.sameWorkIn(books, candidate, null) }.getOrNull()
    }

    /** Moves the book forward to [p] (0..1), with a reading session for the pages; false when nothing changed. */
    private fun apply(b: Book, p: Double): Boolean {
        if (b.status == Book.READ) return false
        val c = Book.from(b.json())
        val percent = (p * 100).roundToInt().coerceIn(0, 100)
        if (c.totalPages > 0) {
            val page = if (p >= 1.0) c.totalPages else (p * c.totalPages).roundToInt().coerceIn(0, c.totalPages)
            if (page <= b.currentPage) return false
            c.currentPage = page; c.percent = percent
        } else {
            if (percent <= b.percent) return false
            c.percent = percent
        }
        c.status = if (p >= 1.0) Book.READ else Book.READING
        val today = LocalDate.now()
        Book.applyStatusEffects(c, today.toString())
        if (c.currentPage != b.currentPage) ReadingJournal.session(c, b.currentPage, c.currentPage, 0, today)
        library.replace(c)
        return true
    }

    private fun load(): JSONObject = runCatching { JSONObject(records.readText()) }.getOrDefault(JSONObject())
    private fun save(j: JSONObject) {
        records.parentFile?.mkdirs()
        val tmp = File(records.path + ".tmp"); tmp.writeText(j.toString()); if (!tmp.renameTo(records)) { records.writeText(j.toString()); tmp.delete() }
    }

    companion object {
        const val USER = "atlante"
        const val PORT = 7200
        private val DOCUMENT = Regex("[0-9a-fA-F]{32}")

        /** The name a book gets on the reader when Atlante sends it (CrossPoint page › Invia libri). */
        @JvmStatic fun sentName(b: Book) = b.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim() + "." + b.format.lowercase(Locale.ROOT)

        /** KOReader's "binary" document id: MD5 of 1 KB at 0, 1 KB, 4 KB, 16 KB … 1 GB (CrossPoint does the same). */
        @JvmStatic fun partialMd5(f: File): String {
            val md = MessageDigest.getInstance("MD5"); val buffer = ByteArray(1024)
            RandomAccessFile(f, "r").use { raf ->
                val size = raf.length()
                for (i in -1 until 11) {
                    val offset = if (i < 0) 0L else 1024L shl (2 * i)
                    if (offset >= size) continue
                    raf.seek(offset); val n = raf.read(buffer, 0, minOf(1024L, size - offset).toInt())
                    if (n > 0) md.update(buffer, 0, n)
                }
            }
            return md.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }
        }

        /** A random 6-digit PIN, the password of the sync. */
        @JvmStatic fun newPin(): String = (100000 + java.security.SecureRandom().nextInt(900000)).toString()

        /** The phone's addresses on the Wi-Fi or its hotspot (private IPv4), Wi-Fi first. */
        @JvmStatic fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("ap") || it.name.startsWith("swlan")) 1 else 2 }
                .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress } }
                .filter { CrossPointClient.isLocal(it) }.distinct()
        }.getOrDefault(emptyList())
    }
}

/** The HTTP side of [LocalSync]: a tiny server on port 7200, answering only devices of the local network. */
class LocalSyncServer(private val sync: LocalSync, private val port: Int = LocalSync.PORT) {
    @Volatile private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2)
    val running: Boolean get() = socket?.isClosed == false

    @Synchronized fun start() {
        if (running) return
        val s = ServerSocket(); s.reuseAddress = true; s.bind(InetSocketAddress(port)); socket = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { break }
                val from = (c.inetAddress as? Inet4Address)?.hostAddress
                if (from == null || !(CrossPointClient.isLocal(from) || c.inetAddress.isLoopbackAddress)) { runCatching { c.close() }; continue }
                runCatching { pool.execute { serve(c) } }.onFailure { runCatching { c.close() } }
            }
        }, "atlante-crosspoint-sync").apply { isDaemon = true }.start()
    }

    @Synchronized fun stop() { runCatching { socket?.close() }; socket = null }

    private fun serve(c: Socket) = c.use { socket ->
        socket.soTimeout = 15000
        val input = BufferedInputStream(socket.getInputStream())
        val request = line(input).split(' ')
        if (request.size < 2) return@use
        val headers = HashMap<String, String>()
        while (true) { val l = line(input); if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase(Locale.ROOT)] = l.substring(i + 1).trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) { reply(socket, LocalSync.Reply(413, "{}")); return@use }
        val body = ByteArray(length); var read = 0
        while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
        reply(socket, sync.handle(request[0].uppercase(Locale.ROOT), request[1], headers, String(body, 0, read, Charsets.UTF_8)))
    }

    private fun reply(socket: Socket, r: LocalSync.Reply) {
        val body = r.body.toByteArray(Charsets.UTF_8)
        val reason = when (r.code) { 200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 402 -> "Payment Required"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 ${r.code} $reason\r\nContent-Type: application/json\r\n" +
            (if (r.code == 204) "" else "Content-Length: ${body.size}\r\n") + "Connection: close\r\n\r\n"
        val out = socket.getOutputStream(); out.write(head.toByteArray(Charsets.US_ASCII)); if (r.code != 204) out.write(body); out.flush()
    }

    private fun line(input: InputStream): String {
        val b = ByteArrayOutputStream()
        while (b.size() < 8192) { val c = input.read(); if (c == -1 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }
        return b.toString("UTF-8")
    }

    companion object { private const val MAX_BODY = 64 * 1024 }
}
