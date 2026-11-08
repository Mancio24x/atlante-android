package app.atlante.library

import app.atlante.library.sync.CrosspointClient
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class CrosspointClientTest {
    @Test fun baseUrlNormalises() {
        assertEquals("http://crosspoint.local", CrosspointClient.baseUrl(""))
        assertEquals("http://192.168.1.20:8080", CrosspointClient.baseUrl("192.168.1.20:8080"))
        assertEquals("http://crosspoint.local", CrosspointClient.baseUrl("http://crosspoint.local/"))
        assertThrows(java.io.IOException::class.java) { CrosspointClient.baseUrl("https://x.local") }
        assertThrows(java.io.IOException::class.java) { CrosspointClient.baseUrl("http://u:p@x.local") }
    }

    @Test fun uploadSendsMultipartFile() {
        var body = ""; var query: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/upload") { ex -> body = String(ex.requestBody.readBytes(), Charsets.ISO_8859_1); query = ex.requestURI.rawQuery; ex.sendResponseHeaders(200, -1); ex.close() }
        server.start()
        try {
            val f = File.createTempFile("libro", ".epub").apply { writeText("CONTENUTO"); deleteOnExit() }
            CrosspointClient.upload("127.0.0.1:${server.address.port}", f, "Il:libro?.epub")
            assertTrue(body.contains("filename=\"Il_libro_.epub\"")); assertTrue(body.contains("CONTENUTO")); assertEquals("path=%2F", query)
            assertTrue(CrosspointClient.reachable("127.0.0.1:${server.address.port}"))
        } finally { server.stop(0) }
    }
}
