package app.atlante.library

import app.atlante.library.crosspoint.CrossPointClient
import app.atlante.library.crosspoint.CrossPointSend
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** «Invia a CrossPoint» from a book's sheet: the file name on the card and which reader gets it. */
class CrossPointSendTest {
    @Test fun fileNameIsTheTitleWithTheExtension() {
        assertEquals("Il nome della rosa.epub", CrossPointSend.fileName("Il nome della rosa", File("/x/abc123.EPUB")))
        assertEquals("Dune Messia.epub", CrossPointSend.fileName("Dune: Messia", File("a.epub")))
        assertEquals("a b c d.txt", CrossPointSend.fileName("a/b\\c?d", File("f.txt")))
        assertEquals("f.cbz", CrossPointSend.fileName("  ", File("f.cbz")))
        assertTrue(CrossPointSend.fileName("x".repeat(300), File("f.epub")).length <= 125)
    }

    @Test fun savedReaderFirstThenDiscovery() {
        val found = listOf(CrossPointClient.Device("192.168.1.30", "CrossPoint-ab12"))
        assertEquals("192.168.1.20", CrossPointSend.reach("192.168.1.20", null, { found }, { true }))
        assertEquals("192.168.1.30", CrossPointSend.reach("192.168.1.20", null, { found }, { false }))
        assertEquals("192.168.1.30", CrossPointSend.reach(null, null, { found }, { true }))
        // A saved address outside the home network is never contacted.
        assertEquals("192.168.1.30", CrossPointSend.reach("8.8.8.8", null, { found }, { error("contacted") }))
        assertNull(CrossPointSend.reach("", null, { emptyList() }, { true }))
        assertNull(CrossPointSend.reach(null, null, { throw java.io.IOException("no wifi") }, { true }))
    }
}
