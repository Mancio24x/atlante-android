package app.atlante.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.atlante.library.catalog.BibliographicEngine
import app.atlante.library.catalog.BibliographicEngine.Confidence
import app.atlante.library.catalog.CatalogDatabase
import app.atlante.library.catalog.LibraryIndex
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.storage.LibraryStore
import app.atlante.library.storage.MergeHistory
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** "Una sola opera = una sola scheda": the tests of the repair specification, on real files and databases. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BibliographicRepairTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AtlanteDatabase
    private lateinit var cat: CatalogDatabase
    private lateinit var store: LibraryStore

    @Before fun open() {
        File(context.filesDir, "library.json").delete(); File(context.filesDir, "books").deleteRecursively(); File(context.filesDir, "merged").deleteRecursively()
        File(context.filesDir, "merge-history.json").delete()
        store = LibraryStore(context)
        db = Room.inMemoryDatabaseBuilder(context, AtlanteDatabase::class.java).allowMainThreadQueries().build()
        cat = Room.inMemoryDatabaseBuilder(context, CatalogDatabase::class.java).allowMainThreadQueries().build()
        cat.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO catalog_works VALUES (2778373, 'L''ultimo impero', 'The Final Empire', 'L''ultimo impero', 'en', 2006, 'Brandon Sanderson', 'Fantasy', 'fantasy', NULL, NULL, 40)")
            execSQL("INSERT INTO catalog_fts(rowid, titles, authors, series) VALUES (2778373, 'l ultimo impero | the final empire | mistborn the final empire', 'brandon sanderson', 'mistborn')")
            execSQL("INSERT INTO catalog_editions VALUES (1, 2778373, 'L''ultimo impero', 'it', '9788834717486', 'Fanucci', 2007)")
        }
    }
    @After fun close() { db.close(); cat.close() }

    private fun book(id: Char, title: String, author: String, isbn: String = "") = Book().apply {
        this.id = id.toString().repeat(64); this.title = title; this.author = author; this.isbn = isbn; format = "CATALOGO"; addedAt = id.code.toLong() }

    private fun pairs(): List<BibliographicEngine.Pair> = runBlocking {
        LibraryIndex.sync(db.saga(), cat.catalog(), store.snapshot())
        val links = db.saga().catalogLinks().associate { it.bookId to it.catalogWork.toLongOrNull() }
        BibliographicEngine.duplicates(store.snapshot(), store.snapshot().associate { it.id to BibliographicEngine.Identity(it.id, links[it.id], BibliographicEngine.hardcoverBook(it), null) })
    }

    private fun repair() { for (p in pairs().filter { it.automatic }) store.merge(BibliographicEngine.merged(store.find(p.keep.id), store.find(p.other.id)), store.find(p.other.id)) }

    /** Tests 1, 2, 3, 7, 8: Italian and English, two ISBNs, two covers, personal data on both → one record, nothing lost. */
    @Test fun italianAndEnglishRecordsBecomeOneWithEverything() {
        val it = book('a', "L'ultimo impero", "Brandon Sanderson", "9788834717486").apply { status = Book.READ; finished = "2025-03-10"; started = "2025-02-01" }
        ReadingJournal.session(it, 0, 120, 3600, LocalDate.parse("2025-02-01")); ReadingJournal.put(it, "notes", "Vin e Kelsier")
        val en = book('b', "The Final Empire", "Brandon Sanderson", "9780765311788").apply { status = Book.READING; currentPage = 200; rating = 5 }
        ReadingJournal.session(en, 100, 200, 1800, LocalDate.parse("2026-01-05")); ReadingJournal.put(en, "favorite", true)
        ReadingJournal.put(en, "collections", JSONArray().put("Cosmere"))
        store.addCatalog(it); store.addCatalog(en)
        store.saveDownloadedCover(it, ByteArray(6000) { 1 }); store.saveDownloadedCover(en, ByteArray(7000) { 2 })
        val booksBefore = store.snapshot()
        val completedBefore = ReadingJournal.completed(booksBefore, LocalDate.parse("2025-06-01"), false)
        val found = pairs().single()
        assertEquals(Confidence.VERIFIED, found.confidence); assertTrue(found.reasons.toString(), found.reasons.any { r -> r.contains("catalogo") })
        repair()
        val all = store.snapshot()
        assertEquals("una sola scheda", 1, all.size)
        val kept = all.single()
        assertEquals("L'ultimo impero", kept.title)                                // the Italian record, kept
        assertEquals(Book.READ, kept.status); assertEquals(5, kept.rating)
        assertEquals(2, ReadingJournal.array(kept, "sessions").length())
        assertEquals("Vin e Kelsier", kept.journal.optString("notes"))
        assertTrue(kept.journal.optBoolean("favorite")); assertTrue(ReadingJournal.inCollection(kept, "Cosmere"))
        assertEquals("9788834717486", kept.isbn)
        assertTrue(kept.meta.optJSONArray("editions").toString().contains("9780765311788"))   // the English edition, inside the record
        assertTrue(File(store.directory(kept), "covers").listFiles()!!.isNotEmpty())          // the other cover, an alternative
        assertEquals(completedBefore, ReadingJournal.completed(all, LocalDate.parse("2025-06-01"), false))   // test 8
        // A second repair finds nothing: idempotent.
        assertTrue(pairs().isEmpty())
        // Recovery: the merge can be undone, both records come back.
        store.undoMerge(MergeHistory.removeLast(context)!!)
        assertEquals(2, store.snapshot().size)
    }

    /** Test 6: same title, other author → two works, never merged. */
    @Test fun sameTitleOtherAuthorStaysApart() {
        store.addCatalog(book('a', "L'ultimo impero", "Brandon Sanderson")); store.addCatalog(book('b', "L'ultimo impero", "Mario Rossi"))
        assertTrue(pairs().isEmpty())
    }

    /** The same title word for word and the same author, no identifier: the same book added twice, merged. */
    @Test fun identicalTitleAndAuthorIsMerged() {
        store.addCatalog(book('a', "Il deserto dei Tartari", "Dino Buzzati")); store.addCatalog(book('b', "Il deserto dei tartari", "Dino Buzzati"))
        val p = pairs().single()
        assertEquals(Confidence.VERIFIED, p.confidence); assertTrue(p.automatic)
        repair(); assertEquals(1, store.snapshot().size)
    }

    /** Uncertain (a title with more words, same author, no identifier): proposed, not merged; "Non unire" is remembered. */
    @Test fun probablePairIsOnlyProposed() {
        store.addCatalog(book('a', "Il deserto dei Tartari e altri racconti", "Dino Buzzati")); store.addCatalog(book('b', "Il deserto dei Tartari e altri racconti : romanzo", "Dino Buzzati"))
        val p = pairs().single()
        assertEquals(Confidence.PROBABLE, p.confidence); assertFalse(p.automatic)
        repair(); assertEquals(2, store.snapshot().size)
        val copy = Book.from(store.find(p.keep.id).json()); BibliographicEngine.rememberDifferent(copy, p.other); store.replace(copy)
        assertTrue(pairs().isEmpty())
    }

    /** A conflict (two different ratings) is never merged by itself; your choice decides. */
    @Test fun conflictsWaitForYou() {
        val a = book('a', "L'ultimo impero", "Brandon Sanderson", "9788834717486").apply { rating = 3 }; ReadingJournal.put(a, "rating", 3.0)
        val b = book('b', "L'ultimo impero", "Brandon Sanderson", "9788834717486").apply { rating = 5 }; ReadingJournal.put(b, "rating", 5.0)
        store.addCatalog(a); store.addCatalog(b)
        val p = pairs().single()
        assertFalse(p.automatic); assertTrue(p.conflicts.toString(), p.conflicts.any { it.startsWith("voto") })
        val merged = BibliographicEngine.merged(p.keep, p.other, BibliographicEngine.Choices(ratingFromOther = true))
        store.merge(merged, p.other)
        assertEquals(1, store.snapshot().size)
        assertEquals(if (p.other.rating == 5) 5.0 else 3.0, ReadingJournal.rating(store.snapshot().single()), 0.0)
    }

    /** Test 5: an EPUB of a work that already has a file joins it as another edition; no second record. */
    @Test fun epubOfAnotherEditionJoinsTheRecord() {
        val mine = book('a', "L'ultimo impero", "Brandon Sanderson").apply { format = "EPUB" }
        store.addCatalog(mine); store.directory(mine).mkdirs(); store.original(mine).writeText("prima edizione")
        runBlocking { LibraryIndex.sync(db.saga(), cat.catalog(), store.snapshot()) }
        val candidate = book('c', "The Final Empire", "Brandon Sanderson").apply { format = "EPUB" }
        store.directory(candidate).mkdirs(); store.original(candidate).writeText("seconda edizione")
        val same = BibliographicEngine.sameWorkIn(store.snapshot(), candidate, cat.catalog())
        assertEquals(mine.id, same?.id)
        val kept = store.addEdition(candidate, same!!)
        assertEquals(1, store.snapshot().size)
        assertEquals("prima edizione", store.original(kept).readText())
        assertEquals("seconda edizione", File(store.directory(kept), "editions/${candidate.id}.epub").readText())
        assertFalse(store.directory(candidate).exists())
    }

    /** Tests 14 and 16: a merge that can no longer happen (one record gone, a repair running elsewhere) changes nothing. */
    @Test fun impossibleMergeChangesNothing() {
        val a = book('a', "L'ultimo impero", "Brandon Sanderson"); store.addCatalog(a)
        val ghost = book('z', "The Final Empire", "Brandon Sanderson")
        try { store.merge(BibliographicEngine.merged(a, ghost), ghost); fail() } catch (e: Exception) { }
        assertEquals(1, store.snapshot().size); assertEquals("L'ultimo impero", store.snapshot().single().title)
        assertEquals(0, MergeHistory.list(context).length())
    }
}
