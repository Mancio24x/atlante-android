package app.atlante.library

import app.atlante.library.crosspoint.LocalSync
import app.atlante.library.crosspoint.LocalSyncServer
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import app.atlante.library.sync.SyncCore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/** "Atlante locale": the CrossPoint reader's KOReader Sync against Atlante, as its firmware calls it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalSyncTest {
    private class Lib(vararg b: Book) : LocalSync.Library {
        val books = b.toMutableList(); val files = HashMap<String, File>()
        override fun books() = books.toList()
        override fun file(book: Book) = files[book.id]
        override fun replace(book: Book) { books[books.indexOfFirst { it.id == book.id }] = book }
    }
    private fun book(id: Char, title: String, author: String, pages: Int, page: Int, status: String = Book.READING, format: String = "EPUB") = Book().apply {
        this.id = id.toString().repeat(64); this.title = title; this.author = author; totalPages = pages; currentPage = page; this.status = status; this.format = format
    }
    private val pin = "123456"
    private val auth = mapOf("x-auth-user" to "atlante", "x-auth-key" to SyncCore.md5(pin))
    private fun sync(lib: Lib) = LocalSync(lib, File.createTempFile("crosspoint", ".json").apply { delete() }) { pin }
    private fun progress(doc: String, p: Double, meta: JSONObject? = null) =
        JSONObject().put("document", doc).put("progress", "/body/DocFragment[3]/body/p[12]/text().0").put("percentage", p)
            .put("device", "CrossPoint").put("device_id", "X4").apply { if (meta != null) put("metadata", meta) }.toString()

    @Test fun registersAndChecksThePin() {
        val s = sync(Lib())
        assertEquals(201, s.handle("POST", "/users/create", emptyMap(), JSONObject().put("username", "atlante").put("password", SyncCore.md5(pin)).toString()).code)
        assertEquals(402, s.handle("POST", "/users/create", emptyMap(), JSONObject().put("username", "atlante").put("password", SyncCore.md5("000000")).toString()).code)
        assertEquals(200, s.handle("GET", "/users/auth", auth, "").code)
        assertEquals(401, s.handle("GET", "/users/auth", mapOf("x-auth-user" to "atlante", "x-auth-key" to SyncCore.md5("000000")), "").code)
        assertEquals(401, s.handle("PUT", "/syncs/progress", emptyMap(), progress("a".repeat(32), .5)).code)
    }

    @Test fun aBookSentByAtlanteMovesForwardWithASession() {
        val rosa = book('r', "Il nome della rosa", "Umberto Eco", 500, 100)
        val lib = Lib(rosa); val s = sync(lib)
        val doc = SyncCore.md5(LocalSync.sentName(rosa))           // the reader's default: MD5 of the file name
        assertEquals(204, s.handle("GET", "/syncs/progress/$doc", auth, "").code)
        assertEquals(200, s.handle("PUT", "/syncs/progress", auth, progress(doc, .5)).code)
        val now = lib.books.single()
        assertEquals(250, now.currentPage); assertEquals(50, now.percent); assertEquals(Book.READING, now.status)
        val sessions = ReadingJournal.array(now, "sessions"); assertEquals(150, sessions.getJSONObject(sessions.length() - 1).getInt("pages"))
        // The reader gets back its own position (its XPath), not one made up from a percentage.
        val back = JSONObject(s.handle("GET", "/syncs/progress/$doc", auth, "").body)
        assertEquals("/body/DocFragment[3]/body/p[12]/text().0", back.getString("progress")); assertEquals(.5, back.getDouble("percentage"), 1e-9)
        assertFalse(back.has("book"))
    }

    @Test fun neverBackwardsAndNeverAReadBook() {
        val ahead = book('a', "Dune", "Frank Herbert", 600, 400); val read = book('b', "Emma", "Jane Austen", 300, 300, Book.READ)
        val lib = Lib(ahead, read); val s = sync(lib)
        s.handle("PUT", "/syncs/progress", auth, progress(SyncCore.md5(LocalSync.sentName(ahead)), .2))
        s.handle("PUT", "/syncs/progress", auth, progress(SyncCore.md5(LocalSync.sentName(read)), .1))
        assertEquals(400, lib.books[0].currentPage); assertEquals(300, lib.books[1].currentPage); assertEquals(Book.READ, lib.books[1].status)
    }

    @Test fun otherBooksByTitleAndAuthorAndTheEndIsRead() {
        val lib = Lib(book('c', "Il barone rampante", "Italo Calvino", 280, 0, Book.TO_READ, "CATALOGO")); val s = sync(lib)
        val meta = JSONObject().put("filename", "calvino-barone.epub").put("title", "Il barone rampante").put("authors", "Italo Calvino")
        s.handle("PUT", "/syncs/progress", auth, progress("b".repeat(32), 1.0, meta))
        val b = lib.books.single(); assertEquals(280, b.currentPage); assertEquals(Book.READ, b.status); assertTrue(b.finished.isNotEmpty())
        // Unknown books are only remembered.
        assertEquals(200, s.handle("PUT", "/syncs/progress", auth, progress("c".repeat(32), .3, JSONObject().put("title", "Un libro che non hai"))).code)
        assertEquals(200, s.handle("GET", "/syncs/progress/" + "c".repeat(32), auth, "").code)
    }

    @Test fun koreaderBinaryHashOfTheFile() {
        val f = File.createTempFile("book", ".epub"); f.writeBytes(ByteArray(300_000) { (it * 31 % 251).toByte() })
        val b = book('d', "Qualcosa", "Qualcuno", 100, 10); val lib = Lib(b).apply { files[b.id] = f }
        assertEquals(b.id, sync(lib).match(LocalSync.partialMd5(f), null, lib.books)?.id)
        assertNotEquals(LocalSync.partialMd5(f), SyncCore.md5(f.name))
    }

    @Test fun overHttpLikeTheReader() {
        val lib = Lib(book('e', "Siddharta", "Hermann Hesse", 200, 20)); val s = sync(lib)
        val port = ServerSocket(0).use { it.localPort }
        val server = LocalSyncServer(s, port); server.start()
        try {
            val doc = SyncCore.md5(LocalSync.sentName(lib.books[0]))
            val c = URL("http://127.0.0.1:$port/syncs/progress").openConnection() as HttpURLConnection
            c.requestMethod = "PUT"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/vnd.koreader.v1+json"); auth.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(progress(doc, .75).toByteArray()) }
            assertEquals(200, c.responseCode); assertEquals(doc, JSONObject(c.inputStream.bufferedReader().readText()).getString("document"))
            assertEquals(150, lib.books[0].currentPage)
            val g = URL("http://127.0.0.1:$port/syncs/progress/" + "f".repeat(32)).openConnection() as HttpURLConnection
            auth.forEach { (k, v) -> g.setRequestProperty(k, v) }; assertEquals(204, g.responseCode)
        } finally { server.stop() }
    }
}
