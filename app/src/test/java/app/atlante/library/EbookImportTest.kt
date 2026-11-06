package app.atlante.library

import app.atlante.library.ebook.Azw3
import app.atlante.library.ebook.EbookParser
import app.atlante.library.model.Book
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Importing real files: an EPUB 3 (with NCX too), the same book converted to AZW3 by Calibre, a Windows-1252 TXT
 * and a CBZ with ComicInfo.xml (test/resources/ebooks). Only what the file contains is read; nothing is invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EbookImportTest {
    private fun copy(name: String): File {
        val f = Files.createTempFile("ebook", name).toFile(); f.deleteOnExit()
        f.writeBytes(javaClass.classLoader!!.getResource("ebooks/$name")!!.readBytes()); return f
    }
    private fun cover() = Files.createTempFile("cover", ".img").toFile().also { it.delete(); it.deleteOnExit() }

    @Test fun epubMetadataCoverAndNestedChapters() {
        val b = Book().apply { format = "EPUB" }; val c = cover()
        EbookParser.metadata(copy("prova.epub"), c, b)
        assertEquals("Il libro di prova", b.title); assertEquals("Anna Verdi", b.author)
        assertEquals("it", b.language); assertEquals("Editore Prova", b.publisher)
        assertEquals("9788804668237", b.isbn)                                     // the UUID is not taken for an ISBN
        assertEquals("urn:uuid:12345678-1234-1234-1234-123456789abc", b.meta.getJSONObject("ids").getString("uuid"))
        assertTrue(c.length() > 100)
        assertEquals(listOf("Parte prima", "Capitolo uno", "Capitolo due", "Parte seconda"), b.chapters.map { it.title })
        assertEquals(listOf(0, 1, 1, 0), b.chapters.map { it.level })
        assertEquals("", b.series); assertEquals("", b.description)               // not in the file: left empty
    }

    @Test fun epub2NcxRetainsNestedEntries() {
        val source=copy("prova.epub");val target=Files.createTempFile("ncx-only",".epub").toFile().also{it.deleteOnExit()}
        java.util.zip.ZipFile(source).use { input ->
            java.util.zip.ZipOutputStream(target.outputStream()).use { output ->
                input.entries().asSequence().filterNot { it.name.contains("nav",ignoreCase=true) }.forEach { e ->
                    output.putNextEntry(java.util.zip.ZipEntry(e.name));input.getInputStream(e).use { it.copyTo(output) };output.closeEntry()
                }
            }
        }
        val b=Book().apply{format="EPUB"};EbookParser.metadata(target,cover(),b)
        assertTrue(b.chapters.isNotEmpty());assertTrue(b.chapters.any{it.level>0})
    }

    @Test fun azw3FromCalibre() {
        val f = copy("prova.azw3"); val b = Book().apply { format = "AZW3" }; val c = cover()
        EbookParser.metadata(f, c, b)
        assertEquals("Il libro di prova", b.title); assertEquals("Anna Verdi", b.author)
        assertEquals("Editore Prova", b.publisher); assertTrue(b.language.startsWith("it"))
        assertEquals("9788804668237", b.isbn)
        assertTrue("embedded cover", c.length() > 100)
        val pages = EbookParser.textPages(f, "AZW3").joinToString("")
        assertTrue(pages.contains("Questa è la frase numero 399 della prima parte, con accenti: perché, città, già."))
        assertTrue(pages.contains("Seconda parte, riga 299"))
        assertFalse(pages.contains("<p"))
    }

    @Test fun azw3WithDrmIsRefusedAndHuffmanIsDeclared() {
        val data = javaClass.classLoader!!.getResource("ebooks/prova.azw3")!!.readBytes()
        val record0 = ((data[78].toInt() and 255) shl 24) or ((data[79].toInt() and 255) shl 16) or ((data[80].toInt() and 255) shl 8) or (data[81].toInt() and 255)
        val drm = data.copyOf().also { it[record0 + 13] = 2 }                     // PalmDOC header: encryption type
        val drmFile = Files.createTempFile("drm", ".azw3").toFile().also { it.writeBytes(drm); it.deleteOnExit() }
        try { EbookParser.metadata(drmFile, cover(), Book().apply { format = "AZW3" }); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("DRM")) }
        val huff = data.copyOf().also { it[record0] = (Azw3.HUFF_CDIC shr 8).toByte(); it[record0 + 1] = (Azw3.HUFF_CDIC and 255).toByte() }
        val huffFile = Files.createTempFile("huff", ".azw3").toFile().also { it.writeBytes(huff); it.deleteOnExit() }
        val b = Book().apply { format = "AZW3" }
        EbookParser.metadata(huffFile, cover(), b)
        assertEquals("Il libro di prova", b.title); assertFalse(b.meta.optBoolean("readable", true))
        try { EbookParser.textPages(huffFile, "AZW3"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("HUFF")) }
        val fake = Files.createTempFile("fake", ".azw3").toFile().also { it.writeText("non è un ebook"); it.deleteOnExit() }
        try { EbookParser.metadata(fake, cover(), Book().apply { format = "AZW3" }); fail() } catch (e: IOException) { }
    }

    @Test fun txtEncodingAndNoInventedTitle() {
        val f = copy("prova-cp1252.txt"); val b = Book().apply { format = "TXT"; title = "prova-cp1252" }
        EbookParser.metadata(f, cover(), b)
        assertEquals("prova-cp1252", b.title)                                    // the first line is not taken as title
        assertTrue(b.chapters.isEmpty())
        val text = EbookParser.textPages(f, "TXT").joinToString("")
        assertTrue(text.contains("C’era una volta una città dove perché e già"))
        assertEquals("già", EbookParser.decodeText("già".toByteArray(Charsets.UTF_8)))
        assertEquals("già", EbookParser.decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "già".toByteArray(Charsets.UTF_8)))
        val binary = Files.createTempFile("bin", ".txt").toFile().also { it.writeBytes(ByteArray(10000)); it.deleteOnExit() }
        try { EbookParser.metadata(binary, cover(), Book().apply { format = "TXT" }); fail() } catch (e: IOException) { }
    }

    @Test fun cbzComicInfoFrontCoverAndPageOrder() {
        val f = copy("prova.cbz"); val b = Book().apply { format = "CBZ" }; val c = cover()
        EbookParser.metadata(f, c, b)
        assertEquals("Fumetto di prova", b.title); assertEquals("Mario Neri", b.author)
        assertEquals("Serie prova", b.series); assertEquals(3.0, b.number, 0.0); assertEquals("it", b.language)
        java.util.zip.ZipFile(f).use { z ->
            assertEquals(listOf("pagina 1.png", "pagina 2.png", "pagina 10.png"), EbookParser.comicPages(z))
            assertArrayEquals("the FrontCover page, not the first", z.getInputStream(z.getEntry("pagina 2.png")).readBytes(), c.readBytes())
        }
    }
}
