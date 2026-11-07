package app.atlante.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.atlante.library.catalog.CatalogDatabase
import app.atlante.library.catalog.LibraryIndex
import app.atlante.library.model.Book
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The catalog built by scripts/catalog/build_catalog.py, opened by Room exactly as the app does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogAssetTest {
    @Test fun builtCatalogOpensAndKnowsRealWorks() = runBlocking {
        val file = File("src/main/assets/catalog/atlante-catalog.db").takeIf { it.isFile } ?: File("app/src/main/assets/catalog/atlante-catalog.db")
        assumeTrue("catalog not built", file.isFile)
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CatalogDatabase::class.java, "catalog-test-${System.nanoTime()}")
            .createFromFile(file).allowMainThreadQueries().build()
        val c = db.catalog()
        val count = c.count()
        assertTrue("works: $count", count in 9_000..10_000)
        // Same work in Italian and English.
        val t = System.nanoTime()
        val it = c.search(LibraryIndex.ftsQuery("ultimo impero")!!).map { it.work }
        val en = c.search(LibraryIndex.ftsQuery("final empire")!!).map { it.work }
        val ms = (System.nanoTime() - t) / 1e6
        val sanderson = it.firstOrNull { it.authors.contains("Sanderson") }
        println("catalogo: $count opere · ricerca in ${"%.1f".format(ms / 2)} ms · «ultimo impero» → ${sanderson?.title} / ${sanderson?.originalTitle}")
        if (sanderson != null) assertTrue(en.any { e -> e.id == sanderson.id })
        // A saga with verified numbers: Harry Potter, 1 to 7 in order.
        val hp = c.search(LibraryIndex.ftsQuery("harry potter pietra filosofale")!!).map { it.work }.firstOrNull { it.authors.contains("Rowling") }
        assertNotNull("Harry Potter e la pietra filosofale", hp)
        println("  ${hp!!.title} · ${hp.originalTitle} · ${hp.year} · ${hp.genre} · ${hp.coverUrl}")
        val saga = c.entriesOf(hp.id).first()
        val volumes = c.entries(saga.seriesId).filter { it.sortKey != null }.sortedBy { it.sortKey }
        val titles = c.works(volumes.map { it.workId }).associateBy { it.id }
        println("  saga ${c.series(saga.seriesId)?.let { it.italianName ?: it.name }}: " + volumes.joinToString(" → ") { "${it.ordinal} ${titles[it.workId]?.title}" })
        assertTrue(volumes.size >= 7)
        // A book of the library is matched to its work, never by title alone.
        assertEquals(hp.id, LibraryIndex.match(c, Book().apply { title = "Harry Potter e la pietra filosofale"; author = "J. K. Rowling" }))
        assertNull(LibraryIndex.match(c, Book().apply { title = "Harry Potter e la pietra filosofale"; author = "Mario Rossi" }))
        // Performance: 1,000 real titles (Italian when known) identified offline, as Ripara duplicati and imports do.
        val sample = db.openHelper.readableDatabase.query("SELECT title, authors FROM catalog_works ORDER BY id LIMIT 1000").use { cur ->
            generateSequence { if (cur.moveToNext()) Book().apply { title = cur.getString(0); author = cur.getString(1) } else null }.toList() }
        var found = 0
        val t0 = System.nanoTime()
        sample.forEachIndexed { i, b -> if (LibraryIndex.match(c, b) != null) found++; if (i == 99) println("  100 libri identificati offline in ${(System.nanoTime() - t0) / 1_000_000} ms") }
        val total = (System.nanoTime() - t0) / 1e6
        println("  1.000 libri in ${"%.0f".format(total)} ms · ${"%.2f".format(total / sample.size)} ms a libro · $found riconosciuti offline = $found richieste online evitate")
        assertTrue("found $found of ${sample.size}", found >= sample.size * 9 / 10)
        db.close()
    }
}
