package app.atlante.library

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.atlante.library.saga.db.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SagaDatabaseTest {
    private lateinit var db: AtlanteDatabase
    private val dao get() = db.saga()

    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AtlanteDatabase::class.java)

    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlanteDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()

    @Test fun sagaVolumesInOrderWithSubseriesAndOwnedCount() = runBlocking {
        val saga = dao.insertSeries(SeriesEntity(name = "Red Rising Saga", wikidataId = "Q79053462"))
        val trilogy = dao.insertSeries(SeriesEntity(name = "Red Rising Trilogy", wikidataId = "Q137598364", parentId = saga))
        val w1 = dao.insertWork(WorkEntity(originalTitle = "Red Rising", wikidataId = "Q18393778", openLibraryId = "OL17076473W"))
        val w2 = dao.insertWork(WorkEntity(originalTitle = "Golden Son", wikidataId = "Q19263643"))
        val w7 = dao.insertWork(WorkEntity(originalTitle = "Red God", wikidataId = "Q137598197"))
        dao.replaceAutomaticEntries(saga, listOf(SeriesEntry(saga, w7, "7", 7.0), SeriesEntry(saga, w1, "1", 1.0), SeriesEntry(saga, w2, "2", 2.0)))
        dao.putEntry(SeriesEntry(trilogy, w1, "1", 1.0))
        dao.putLink(BookLink("a".repeat(64), workId = w1, seriesId = saga, score = 95, status = "auto"))
        assertEquals(listOf("Red Rising", "Golden Son", "Red God"), dao.volumes(saga).map { it.work.originalTitle })
        assertEquals(listOf("Red Rising Trilogy"), dao.subseries(saga).map { it.name })
        val summary = dao.allSeries().first { it.series.id == saga }
        assertEquals(3, summary.volumes); assertEquals(1, summary.owned)
        assertEquals(setOf("Red Rising Saga", "Red Rising Trilogy"), dao.seriesOf(w1).map { it.name }.toSet())
    }

    @Test fun refreshKeepsManualEntriesAndDeletingASagaKeepsWorks() = runBlocking {
        val saga = dao.insertSeries(SeriesEntity(name = "Mistborn", wikidataId = "Q268100"))
        val w1 = dao.insertWork(WorkEntity(originalTitle = "The Final Empire", wikidataId = "Q2778373"))
        val extra = dao.insertWork(WorkEntity(originalTitle = "Racconto aggiunto a mano"))
        dao.putEntry(SeriesEntry(saga, extra, null, membership = "novella", source = "manual"))
        dao.replaceAutomaticEntries(saga, listOf(SeriesEntry(saga, w1, "1", 1.0)))
        dao.replaceAutomaticEntries(saga, listOf(SeriesEntry(saga, w1, "1", 1.0)))          // a second refresh
        assertEquals(listOf("The Final Empire", "Racconto aggiunto a mano"), dao.volumes(saga).map { it.work.originalTitle })
        db.openHelper.writableDatabase.execSQL("DELETE FROM series WHERE id = $saga")
        assertNotNull(dao.work(w1)); assertTrue(dao.volumes(saga).isEmpty())
    }

    @Test fun cacheExpiresWithoutTouchingOtherData() = runBlocking {
        dao.putCache(CacheEntry("search:mistborn", "search", "{}", 0, 1_000)); dao.putCache(CacheEntry("suggest:x", "suggestion", "{}", 0, 5_000))
        dao.putOverride(UserOverride("book", "b1", "series", "Mistborn", 0))
        assertEquals(1, dao.deleteExpired(2_000))
        assertNull(dao.cached("search:mistborn", 2_000)); assertNotNull(dao.cached("suggest:x", 2_000))
        assertEquals(1, dao.overrides("book", "b1").size)
    }

    /** The exported schema of version 1 opens and validates: the starting point of every future migration test. */
    /** 1 → 2 adds the library index and keeps everything else: links, manual choices, works. */
    @Test fun migrationToTwoKeepsTheData() {
        val packaged = runCatching { InstrumentationRegistry.getInstrumentation().context.assets.open("${AtlanteDatabase::class.java.name}/2.json").close() }.isSuccess
        org.junit.Assume.assumeTrue(packaged)
        migrations.createDatabase("migration-data", 1).apply {
            execSQL("INSERT INTO works (id, originalTitle, altTitles, italianTitle, kind, updatedAt) VALUES (7, 'The Final Empire', '', 'L''ultimo impero', '', 0)")
            execSQL("INSERT INTO book_links (bookId, workId, seriesId, score, status, reasons, checkedAt) VALUES ('${"a".repeat(64)}', 7, NULL, 95, 'manual', '', 1)")
            execSQL("INSERT INTO user_overrides (entityType, entityId, field, value, updatedAt) VALUES ('series', '3', 'order', '7,8', 1)")
            close()
        }
        val db = migrations.runMigrationsAndValidate("migration-data", 2, true, *AtlanteDatabase.MIGRATIONS)
        db.query("SELECT originalTitle, italianTitle FROM works WHERE id = 7").use { c -> assertTrue(c.moveToFirst()); assertEquals("L'ultimo impero", c.getString(1)) }
        db.query("SELECT status FROM book_links").use { c -> assertTrue(c.moveToFirst()); assertEquals("manual", c.getString(0)) }
        db.query("SELECT value FROM user_overrides").use { c -> assertTrue(c.moveToFirst()); assertEquals("7,8", c.getString(0)) }
        db.execSQL("INSERT INTO library_fts (rowid, bookId, titles, authors, series, hash, catalogWork) VALUES (1, 'b', 'L''ultimo impero | The Final Empire', 'Brandon Sanderson', 'Mistborn', 'h', '')")
        db.query("SELECT bookId FROM library_fts WHERE library_fts MATCH 'final* empir*'").use { c -> assertTrue(c.moveToFirst()); assertEquals("b", c.getString(0)) }
        db.close()
    }

    @Test fun exportedSchemaVersionOneIsValid() {
        // Schemas are packaged only in debug assets (kept out of the APK); build.sh runs this test on debug too.
        val packaged = runCatching { InstrumentationRegistry.getInstrumentation().context.assets.open("${AtlanteDatabase::class.java.name}/1.json").close() }.isSuccess
        org.junit.Assume.assumeTrue(packaged)
        migrations.createDatabase("migration-test", 1).close()
        migrations.runMigrationsAndValidate("migration-test", 1, true, *AtlanteDatabase.MIGRATIONS).close()
    }
}
