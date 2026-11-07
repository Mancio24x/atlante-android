package app.atlante.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.atlante.library.catalog.CatalogDatabase
import app.atlante.library.catalog.LibraryIndex
import app.atlante.library.catalog.UnifiedSearch
import app.atlante.library.model.Book
import app.atlante.library.saga.db.AtlanteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The FTS4 index of the library, the offline catalog and the one search over both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryIndexTest {
    private lateinit var db: AtlanteDatabase
    private lateinit var cat: CatalogDatabase

    @Before fun open() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(c, AtlanteDatabase::class.java).allowMainThreadQueries().build()
        cat = Room.inMemoryDatabaseBuilder(c, CatalogDatabase::class.java).allowMainThreadQueries().build()
        // A few real catalog rows, as the pipeline writes them (folded FTS text, Wikidata ids).
        cat.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO catalog_works VALUES (2778373, 'L''ultimo impero', 'The Final Empire', 'L''ultimo impero', 'en', 2006, 'Brandon Sanderson', 'Fantasy', 'fantasy', 'OL5738148W', NULL, 40)")
            execSQL("INSERT INTO catalog_works VALUES (1516186, 'Il pozzo dell''ascensione', 'The Well of Ascension', 'Il pozzo dell''ascensione', 'en', 2007, 'Brandon Sanderson', 'Fantasy', 'fantasy', NULL, NULL, 30)")
            execSQL("INSERT INTO catalog_works VALUES (3107329, 'L''eroe delle ere', 'The Hero of Ages', 'L''eroe delle ere', 'en', 2008, 'Brandon Sanderson', 'Fantasy', 'fantasy', NULL, NULL, 28)")
            execSQL("INSERT INTO catalog_series VALUES (268100, 'Mistborn', 'Mistborn', NULL)")
            execSQL("INSERT INTO catalog_entries VALUES (268100, 2778373, '1', 1.0), (268100, 1516186, '2', 2.0), (268100, 3107329, '3', 3.0)")
            execSQL("INSERT INTO catalog_fts(rowid, titles, authors, series) VALUES (2778373, 'l ultimo impero | the final empire | mistborn the final empire', 'brandon sanderson', 'mistborn')")
            execSQL("INSERT INTO catalog_fts(rowid, titles, authors, series) VALUES (1516186, 'il pozzo dell ascensione | the well of ascension', 'brandon sanderson', 'mistborn')")
            execSQL("INSERT INTO catalog_fts(rowid, titles, authors, series) VALUES (3107329, 'l eroe delle ere | the hero of ages', 'brandon sanderson', 'mistborn')")
        }
    }
    @After fun close() { db.close(); cat.close() }

    private fun book(id: Char, title: String, author: String) = Book().apply { this.id = id.toString().repeat(64); this.title = title; this.author = author; format = "CATALOGO" }

    @Test fun indexFollowsAddsChangesAndRemovalsOnly() = runBlocking {
        val a = book('a', "L'ultimo impero", "Brandon Sanderson"); val b = book('b', "Il nome della rosa", "Umberto Eco")
        val dao = db.saga()
        assertEquals(LibraryIndex.Report(2, 0, 0, 0), LibraryIndex.sync(dao, cat.catalog(), listOf(a, b)))
        assertEquals(LibraryIndex.Report(0, 0, 0, 2), LibraryIndex.sync(dao, cat.catalog(), listOf(a, b)))    // nothing rebuilt
        val renamed = Book.from(b.json()).apply { title = "Il nome della rosa (illustrato)" }
        assertEquals(LibraryIndex.Report(0, 1, 0, 1), LibraryIndex.sync(dao, cat.catalog(), listOf(a, renamed)))
        assertEquals(LibraryIndex.Report(0, 0, 1, 1), LibraryIndex.sync(dao, cat.catalog(), listOf(a)))
        // Offline search of the library, also by the work's original title from the catalog, accents and case ignored.
        assertEquals(listOf(a.id), LibraryIndex.search(dao, "final empire"))
        assertEquals(listOf(a.id), LibraryIndex.search(dao, "L’ULTIMO"))
        assertTrue(LibraryIndex.search(dao, "rosa").isEmpty())
        // The book is linked to its catalog work in the index itself (not through a shared in-memory map, which a
        // background sync started by another test may replace at the same moment).
        assertEquals(listOf(a.id to "2778373"), dao.catalogLinks().map { it.bookId to it.catalogWork })
    }

    @Test fun sameTitleOtherAuthorIsNotTheCatalogWork() = runBlocking {
        val other = book('c', "L'ultimo impero", "Mario Rossi")
        assertNull(LibraryIndex.match(cat.catalog(), other))
        assertEquals(2778373L, LibraryIndex.match(cat.catalog(), book('d', "The Final Empire", "Brandon Sanderson")))
    }

    @Test fun oneSearchOverBothIndexesListsEachWorkOnce() = runBlocking {
        val mine = book('a', "L'ultimo impero", "Brandon Sanderson")
        LibraryIndex.sync(db.saga(), cat.catalog(), listOf(mine))
        val r = UnifiedSearch.search(db.saga(), cat.catalog(), "sanderson", listOf(mine))
        assertEquals(listOf(mine.id), r.library)
        // The owned work is not listed again from the catalog; the others come with their saga and number, Italian titles.
        assertEquals(listOf("Il pozzo dell'ascensione", "L'eroe delle ere"), r.catalog.map { it.work.title })
        assertEquals(listOf("2", "3"), r.catalog.map { it.ordinal }); assertEquals("Mistborn", r.catalog[0].series)
        val english = UnifiedSearch.search(db.saga(), cat.catalog(), "well of ascension", listOf(mine))
        assertEquals("Il pozzo dell'ascensione", english.catalog.single().work.title)
    }
}
