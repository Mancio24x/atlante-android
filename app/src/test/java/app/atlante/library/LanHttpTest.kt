package app.atlante.library

import app.atlante.library.sync.SyncCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Calibre's content server is plain http:// on the home network, which Android blocks for HttpURLConnection
 * (target API 28+): such requests go over a socket. A fake server asks for credentials, then answers chunked.
 */
class LanHttpTest {
    @Test fun calibreOnTheHomeNetworkWithCredentials() {
        val server = ServerSocket(0)
        val seen = ArrayList<String>()
        val t = thread {
            repeat(2) { i ->
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    val head = generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList(); synchronized(seen) { seen += head }
                    val out = s.getOutputStream()
                    if (i == 0) out.write("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"calibre\"\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    else out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: application/json\r\n\r\n7\r\n{\"libra\r\n12\r\nry_map\": {\"a\": 1}}\r\n0\r\n\r\n".toByteArray())
                    out.flush()
                }
            }
        }
        val r = SyncCore.http("GET", "http://127.0.0.1:${server.localPort}/ajax/library-info", null, null, "lettore", "segreta", 1 shl 20)
        t.join(5000); server.close()
        assertEquals(200, r.status)
        assertEquals("{\"library_map\": {\"a\": 1}}", r.body)
        assertTrue(seen.toString(), seen.any { it.startsWith("GET /ajax/library-info HTTP/1.1") })
        assertTrue(seen.toString(), seen.any { it.startsWith("Authorization: Basic ") })
    }
}
