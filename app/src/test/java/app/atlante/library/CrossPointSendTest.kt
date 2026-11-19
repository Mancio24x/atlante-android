package app.atlante.library

import app.atlante.library.crosspoint.CrossPointClient
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** «Invia a CrossPoint»: a reader with a new address is found again, a file already there is not sent twice. */
class CrossPointSendTest {
    /** A fake reader listening only on 127.0.0.1 (127.0.0.2 on the same port refuses the connection: an old address). */
    private class Reader(val files: String, val uploadReply: String = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok") : AutoCloseable {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")); val lines = ArrayList<String>()
        init { thread(isDaemon = true) { while (!server.isClosed) { val s = runCatching { server.accept() }.getOrNull() ?: break
            s.use { val input = s.getInputStream(); val head = ByteArrayOutputStream()
                while (!head.toString("UTF-8").endsWith("\r\n\r\n")) { val c = input.read(); if (c == -1) break; head.write(c) }
                val h = head.toString("UTF-8"); val length = Regex("Content-Length: (\\d+)").find(h)?.groupValues?.get(1)?.toInt() ?: 0
                var read = 0; val body = ByteArray(length); while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
                val line = h.lineSequence().first(); synchronized(lines) { lines += line }
                val reply = if (line.startsWith("GET /api/files")) "HTTP/1.1 200 OK\r\nContent-Length: ${files.toByteArray().size}\r\n\r\n$files" else uploadReply
                s.getOutputStream().write(reply.toByteArray()); s.getOutputStream().flush() } } } }
        val port get() = server.localPort
        fun uploads() = synchronized(lines) { lines.count { it.startsWith("POST /upload") } }
        override fun close() = server.close()
    }
    private val book = File.createTempFile("libro", ".epub").apply { writeBytes(ByteArray(1234) { it.toByte() }) }

    @Test fun aReaderWithANewAddressIsFoundAndUsed() = Reader("[]").use { r ->
        var looked = 0
        val sent = CrossPointClient.send("127.0.0.2", book, "Dune.epub", null, r.port, false) { looked++; listOf(CrossPointClient.Device("127.0.0.1", "CrossPoint-ab12")) }
        assertEquals(1, looked)
        assertEquals("127.0.0.1", sent.host)
        assertFalse(sent.alreadyThere)
        assertEquals(1, r.uploads())
    }

    @Test fun theRememberedReaderIsUsedWithoutLookingAround() = Reader("[]").use { r ->
        val sent = CrossPointClient.send("127.0.0.1", book, "Dune.epub", null, r.port, false) { fail("no discovery needed"); emptyList() }
        assertEquals("127.0.0.1", sent.host); assertEquals(1, r.uploads())
    }

    @Test fun aFileAlreadyOnTheReaderIsNotSentAgain() = Reader("""[{"name":"Dune.epub","size":1234,"isDirectory":false,"isEpub":true}]""").use { r ->
        val sent = CrossPointClient.send("127.0.0.1", book, "Dune.epub", null, r.port, false) { emptyList() }
        assertTrue(sent.alreadyThere); assertEquals(0, r.uploads())
        // Same name, other size (another edition): sent.
        Reader("""[{"name":"Dune.epub","size":99,"isDirectory":false,"isEpub":true}]""").use { other ->
            assertFalse(CrossPointClient.send("127.0.0.1", book, "Dune.epub", null, other.port, false) { emptyList() }.alreadyThere)
            assertEquals(1, other.uploads())
        }
    }

    @Test fun aReaderThatRefusesIsNotReplacedAndNoReaderGivesTheFirstError() {
        Reader("[]", "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 9\r\n\r\nSD piena.").use { r ->
            var looked = false
            val e = assertThrows(CrossPointClient.Refused::class.java) {
                CrossPointClient.send("127.0.0.1", book, "Dune.epub", null, r.port, false) { looked = true; emptyList() } }
            assertEquals("SD piena.", e.message); assertFalse(looked)
        }
        val port = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }   // nobody listens there now
        assertThrows(IOException::class.java) { CrossPointClient.send("127.0.0.1", book, "Dune.epub", null, port, false) { emptyList() } }
    }
}
