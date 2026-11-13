package app.atlante.library

import app.atlante.library.crosspoint.CrossPointClient
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The CrossPoint client against a small fake of the reader's server (same answers as its firmware). */
class CrossPointClientTest {
    private class Fake(val reply: (String, ByteArray) -> ByteArray) : AutoCloseable {
        val server = ServerSocket(0); val requests = ArrayList<Pair<String, ByteArray>>()
        init { thread(isDaemon = true) { while (!server.isClosed) { val s = runCatching { server.accept() }.getOrNull() ?: break
            s.use { val input = s.getInputStream(); val head = ByteArrayOutputStream()
                while (!head.toString("UTF-8").endsWith("\r\n\r\n")) { val c = input.read(); if (c == -1) break; head.write(c) }
                val h = head.toString("UTF-8"); val length = Regex("Content-Length: (\\d+)").find(h)?.groupValues?.get(1)?.toInt() ?: 0
                val body = ByteArray(length); var read = 0; while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
                synchronized(requests) { requests += h to body }; s.getOutputStream().write(reply(h.lineSequence().first(), body)); s.getOutputStream().flush() } } } }
        override fun close() = server.close()
    }
    private fun ok(body: String, chunked: Boolean = false): ByteArray = if (!chunked) "HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body".toByteArray()
        else ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + body.chunked(7).joinToString("") { Integer.toHexString(it.toByteArray().size) + "\r\n" + it + "\r\n" } + "0\r\n\r\n").toByteArray()

    @Test fun onlyHomeNetworkAddresses() {
        assertTrue(CrossPointClient.isLocal("192.168.1.20")); assertTrue(CrossPointClient.isLocal("10.0.0.5")); assertTrue(CrossPointClient.isLocal("172.20.1.1"))
        assertFalse(CrossPointClient.isLocal("8.8.8.8")); assertFalse(CrossPointClient.isLocal("172.40.1.1")); assertFalse(CrossPointClient.isLocal("crosspoint.example.com"))
        assertEquals("CrossPoint-ab12", CrossPointClient.deviceName("crosspoint (on CrossPoint-ab12);81"))
        assertNull(CrossPointClient.deviceName("something else"))
    }

    @Test fun listsFoldersFirstFromAChunkedAnswer() = Fake { line, _ ->
        if (line.startsWith("GET /api/files?path=%2FBooks")) ok("""[{"name":"b.epub","size":10,"isDirectory":false,"isEpub":true},{"name":"Saghe","size":0,"isDirectory":true,"isEpub":false},{"name":"A.txt","size":3,"isDirectory":false,"isEpub":false}]""", chunked = true)
        else "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()
    }.use { f ->
        val list = CrossPointClient("127.0.0.1", f.server.localPort, false).list("/Books")
        assertEquals(listOf("Saghe", "A.txt", "b.epub"), list.map { it.name })
        assertTrue(list[0].isDirectory); assertTrue(list[2].isEpub)
    }

    @Test fun uploadsDownloadsAndReportsErrors() {
        val book = File.createTempFile("libro", ".epub").apply { writeBytes(ByteArray(40000) { (it % 251).toByte() }) }
        Fake { line, _ -> when {
            line.startsWith("POST /upload") -> ok("File uploaded successfully: Dune.epub")
            line.startsWith("GET /download") -> "HTTP/1.1 200 OK\r\nContent-Length: ${book.length()}\r\n\r\n".toByteArray() + book.readBytes()
            line.startsWith("POST /mkdir") -> "HTTP/1.1 400 Bad Request\r\nContent-Length: 21\r\n\r\nFolder already exists".toByteArray()
            else -> ok("")
        } }.use { f ->
            val c = CrossPointClient("127.0.0.1", f.server.localPort, false)
            var last = 0L
            c.upload("/Books", book, "Dune.epub") { done, _ -> last = done }
            assertEquals(book.length(), last)
            val (head, body) = f.requests.first { it.first.startsWith("POST /upload?path=%2FBooks") }
            assertTrue(head.contains("multipart/form-data; boundary="))
            val text = String(body, Charsets.ISO_8859_1)
            assertTrue(text.contains("filename=\"Dune.epub\""))
            assertTrue(String(body, Charsets.ISO_8859_1).contains(String(book.readBytes(), Charsets.ISO_8859_1)))
            val copy = File.createTempFile("copia", ".epub"); c.download("/Books/Dune.epub", copy)
            assertArrayEquals(book.readBytes(), copy.readBytes())
            val e = assertThrows(IOException::class.java) { c.mkdir("/", "Books") }
            assertEquals("Folder already exists", e.message)
            c.rename("/Books/Dune.epub", "Dune 1.epub")
            assertTrue(String(f.requests.last().second).contains("name=Dune+1.epub"))
        }
    }

    @Test fun sentFileIsNamedAfterTheBook() {
        val f = File("/tmp/x/abc123.epub")
        assertEquals("Il nome della rosa.epub", app.atlante.library.crosspoint.CrossPointSend.fileName("Il nome della rosa", f))
        assertEquals("1984 Orwell.epub", app.atlante.library.crosspoint.CrossPointSend.fileName("1984: Orwell", f))
        assertEquals("abc123.epub", app.atlante.library.crosspoint.CrossPointSend.fileName("  ", f))
        assertEquals("A B.txt", app.atlante.library.crosspoint.CrossPointSend.fileName("A/B", File("x.txt")))
    }
}
