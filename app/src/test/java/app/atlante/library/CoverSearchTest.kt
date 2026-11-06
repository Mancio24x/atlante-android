package app.atlante.library

import android.graphics.Bitmap
import app.atlante.library.model.Book
import app.atlante.library.online.CoverSearch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Cover engine on fake answers: right book only, Italian edition and exact ISBN first, sharpest wins, placeholders refused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverSearchTest {
    private fun jpeg(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w step 7) for (y in 0 until h step 5) b.setPixel(x, y, (x * 31 + y * 17) or 0xFF000000.toInt())   // noise: real-sized JPEG
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    @Test fun titlesMatchStrictly() {
        assertTrue(CoverSearch.titleMatches("L'ultimo impero", "Mistborn #1. L'ultimo impero - The Final Empire"))
        assertTrue(CoverSearch.titleMatches("Blackwater I. La piena", "La piena"))
        assertFalse("Catalan edition", CoverSearch.titleMatches("Blackwater I. La piena", "BLACKWATER I. La riuada"))
        assertFalse("a sequel is another book", CoverSearch.titleMatches("Dune", "I figli di Dune"))
        assertTrue(CoverSearch.titleMatches("Dune", "Dune"))
        assertTrue(CoverSearch.titleMatches("Il nome della rosa", "Il nome della rosa. Nuova edizione"))
        assertFalse(CoverSearch.titleMatches("1984", "1985"))
        assertTrue(CoverSearch.authorMatches("Brandon Sanderson", "Brandon Sanderson & Jean Claude Mallé"))
        assertFalse(CoverSearch.authorMatches("Pierce Brown", "Dan Simmons"))
        assertTrue(CoverSearch.italianIsbn("https://is1-ssl.mzstatic.com/x/9788835711124.jpg/100x100bb.jpg"))
        assertFalse(CoverSearch.italianIsbn("https://is1-ssl.mzstatic.com/x/1044171286.jpg/100x100bb.jpg"))
    }

    @Test fun bestCoverIsTheItalianSharpOneAndPlaceholdersAreRefused() {
        val italian = jpeg(700, 1000); val spanish = jpeg(800, 1000); val tiny = jpeg(40, 60)
        val apple = """{"results":[
            {"trackName":"BLACKWATER I. La riada","artistName":"Michael McDowell","artworkUrl100":"https://is1-ssl.mzstatic.com/a/1044171278.jpg/100x100bb.jpg"},
            {"trackName":"La piena","artistName":"Michael McDowell","artworkUrl100":"https://is1-ssl.mzstatic.com/a/9791255020653.jpg/100x100bb.jpg"},
            {"trackName":"Blackwater I: The Flood","artistName":"Michael McDowell","artworkUrl100":"https://is1-ssl.mzstatic.com/a/9781529948394.jpg/100x100bb.jpg"}]}"""
        val get: (String, Int) -> ByteArray = { url, _ -> when {
            "itunes.apple.com" in url -> apple.toByteArray()
            url.endsWith("9791255020653.jpg/1000x1000bb.jpg") -> italian
            url.endsWith("9781529948394.jpg/1000x1000bb.jpg") -> spanish   // English edition, larger
            "openlibrary.org/search" in url -> """{"docs":[]}""".toByteArray()
            "googleapis" in url -> """{"items":[]}""".toByteArray()
            "covers.openlibrary.org" in url -> tiny
            else -> throw java.io.IOException(url)
        } }
        val book = Book().apply { title = "Blackwater I. La piena"; author = "Michael McDowell" }
        val best = CoverSearch.best(book, get)!!
        assertTrue("Italian edition preferred", best.italian)
        assertEquals(700, best.width)
        val all = CoverSearch.all(book, get)
        assertEquals(1, all.size)   // Catalan and English titles do not match, the tiny image is a placeholder
    }

    @Test fun exactIsbnWins() {
        val exact = jpeg(330, 500); val big = jpeg(700, 1000)
        val get: (String, Int) -> ByteArray = { url, _ -> when {
            "covers.openlibrary.org/b/isbn/9780345539786" in url -> exact
            "itunes.apple.com" in url -> """{"results":[{"trackName":"Red Rising","artistName":"Pierce Brown","artworkUrl100":"https://is1-ssl.mzstatic.com/a/1.jpg/100x100bb.jpg"}]}""".toByteArray()
            url.endsWith("/1.jpg/1000x1000bb.jpg") -> big
            "openlibrary.org/search" in url -> """{"docs":[]}""".toByteArray()
            "googleapis" in url -> """{"items":[]}""".toByteArray()
            else -> throw java.io.IOException(url)
        } }
        val best = CoverSearch.best(Book().apply { title = "Red Rising"; author = "Pierce Brown"; isbn = "9780345539786" }, get)!!
        assertTrue(best.exact); assertEquals(330, best.width)
        assertTrue(CoverSearch.isbns(Book().apply { isbn = "0345539788" }).contains("9780345539786"))
    }
}
