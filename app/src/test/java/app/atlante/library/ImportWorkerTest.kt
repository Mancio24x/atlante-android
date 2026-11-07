package app.atlante.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.atlante.library.catalog.ImportPlanner
import app.atlante.library.catalog.RepairWorker
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.storage.BackupArchive
import app.atlante.library.storage.CatalogImport
import app.atlante.library.storage.LibraryStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/**
 * Importing CSV and ZIP backups, as the app does it (background worker on a copy of the file).
 * The crash was the confirmation step: every row compared with every book on the main thread
 * (2,000 rows × 1,500 books = 157 s), so Android closed the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportWorkerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: LibraryStore

    @Before fun empty() {
        File(context.filesDir, "library.json").delete(); File(context.filesDir, "books").deleteRecursively()
        store = LibraryStore.shared(context); for (b in store.snapshot().toList()) store.remove(b)
    }

    private fun file(name: String, bytes: ByteArray) = File(context.filesDir, "imports").apply { mkdirs() }.resolve(name).apply { writeBytes(bytes) }

    private fun import(f: File): Pair<ListenableWorker.Result, app.atlante.library.saga.db.RepairJob> = runBlocking {
        val w = TestListenableWorkerBuilder<RepairWorker>(context)
            .setInputData(workDataOf(RepairWorker.TYPE to RepairWorker.IMPORT, RepairWorker.PATH to f.path, RepairWorker.FILE_NAME to f.name)).build()
        val r = w.doWork()
        r to AtlanteDatabase.get(context).saga().job(w.id.toString())!!
    }

    private val goodreads = "\\uFEFFBook Id,Title,Author,Author l-f,ISBN,ISBN13,My Rating,Number of Pages,Date Read,Exclusive Shelf,My Review\n" +
        "1,\"Il nome della rosa\",Umberto Eco,\"Eco, Umberto\",\"=\"\"\"\"\",\"=\"\"9788845292613\"\"\",5,503,2024/03/02,read,\"Bello, \"\"davvero\"\"\"\n" +
        "2,\"L'ultimo impero\",Brandon Sanderson,\"Sanderson, Brandon\",,,4,,,currently-reading,\n" +
        "3,\"Città di ossa\",Cassandra Clare,\"Clare, Cassandra\",,,0,,,to-read,\n" +
        "4,\"Il nome della rosa\",Umberto Eco,\"Eco, Umberto\",,,0,,,to-read,\n"

    @Test fun csvImportsOnceAndRepeatsAddNothing() {
        // Already here, under the same title and author: kept as it is (with its own rating).
        store.addCatalog(Book().apply { id = "a".repeat(64); title = "L'ultimo impero"; author = "Brandon Sanderson"; format = "CATALOGO"; rating = 2 })
        val (r, job) = import(file("goodreads.csv", goodreads.replace("\\\\uFEFF", "\\uFEFF").toByteArray()))
        assertTrue(job.message, r is ListenableWorker.Result.Success); assertEquals("done", job.state)
        val report = JSONObject(job.result).getJSONObject("import")
        // The row repeated in the file is dropped while reading it (same record); the one already here is reconciled.
        assertEquals(report.toString(), 3, report.getInt("analyzed")); assertEquals(report.toString(), 2, report.getInt("added"))
        assertEquals(report.toString(), 1, report.getInt("reconciled"))
        val books = store.snapshot()
        assertEquals(3, books.size)
        assertEquals(2, books.single { it.title == "L'ultimo impero" }.rating)           // personal data here not overwritten
        val rosa = books.single { it.title == "Il nome della rosa" }
        assertEquals(Book.READ, rosa.status); assertEquals("9788845292613", rosa.isbn)
        assertFalse(File(job.let { context.filesDir }, "imports/goodreads.csv").exists())   // the copy is removed
        // The same file again: nothing added, nothing duplicated.
        val (_, again) = import(file("goodreads.csv", goodreads.toByteArray()))
        assertEquals(0, JSONObject(again.result).getJSONObject("import").getInt("added")); assertEquals(3, store.snapshot().size)
    }

    @Test fun atlanteZipBackupRestoresWithSessionsAndIsIdempotent() {
        val b = Book().apply { id = "b".repeat(64); title = "Dune"; author = "Frank Herbert"; format = "CATALOGO"; status = Book.READING; currentPage = 120 }
        ReadingJournal.session(b, 100, 120, 600, LocalDate.parse("2026-01-02")); ReadingJournal.put(b, "notes", "Spezia")
        val zip = ByteArrayOutputStream().also { BackupArchive.write(it, JSONArray().put(b.json()), File(context.filesDir, "books"), true, 1L) }.toByteArray()
        val (r, job) = import(file("atlante.zip", zip))
        assertTrue(job.message, r is ListenableWorker.Result.Success)
        val dune = store.find(b.id)!!
        assertEquals(120, dune.currentPage); assertEquals(1, ReadingJournal.array(dune, "sessions").length()); assertEquals("Spezia", dune.journal.optString("notes"))
        val (_, again) = import(file("atlante.zip", zip))
        assertEquals(0, JSONObject(again.result).getJSONObject("import").getInt("added"))
        assertEquals(1, ReadingJournal.array(store.find(b.id)!!, "sessions").length())
    }

    @Test fun damagedFilesEndWithAReasonAndTouchNothing() {
        store.addCatalog(Book().apply { id = "c".repeat(64); title = "Mio"; author = "Io"; format = "CATALOGO" })
        val zip = ByteArrayOutputStream().also { BackupArchive.write(it, JSONArray().put(store.snapshot()[0].json()), File(context.filesDir, "books"), false, 1L) }.toByteArray()
        for ((name, bytes) in listOf("vuoto.csv" to ByteArray(0), "rotto.zip" to zip.copyOf(zip.size / 2), "binario.csv" to ByteArray(3000) { (it * 31).toByte() },
                "solo-intestazione.csv" to "Title,Author\n".toByteArray())) {
            val (r, job) = import(file(name, bytes))
            assertTrue(name, r is ListenableWorker.Result.Failure); assertEquals("failed", job.state); assertTrue(name, job.message.isNotBlank())
            assertEquals(1, store.snapshot().size)
        }
    }

    @Test fun aPathAsIdIsNeverRestored() {
        val evil = JSONObject().put("format", BackupArchive.KIND).put("version", 1).put("books", JSONArray()
            .put(JSONObject().put("id", "..").put("title", "x").put("author", "y").put("format", "CATALOGO"))
            .put(JSONObject().put("id", "d".repeat(64)).put("title", "Buono").put("author", "Z").put("format", "CATALOGO")))
        val contents = CatalogImport.read(ByteArrayInputStream(evil.toString().toByteArray()), "backup.json")
        assertTrue(contents.books.map { it.id }.toString(), contents.books.all { it.id.matches(Regex("[0-9a-f]{64}")) })
        // And the restore itself refuses a path as id, even if a record arrived with one.
        File(store.staging(), "..").mkdirs()
        val bad = Book().apply { id = ".."; title = "x"; author = "y"; format = "CATALOGO" }
        assertEquals(0, store.restore(listOf(bad), store.staging()))
        assertTrue(context.filesDir.isDirectory)
        assertTrue(store.snapshot().isEmpty())
    }

    @Test fun planningIsFastOnBigFiles() {
        val library = (0 until 1500).map { i -> Book().apply { id = "%064x".format(i + 1); title = "Libro $i"; author = "Autore ${i % 300} Cognome${i % 200}"; format = "CATALOGO" } }
        val rows = (0 until 2000).map { i -> Book().apply { id = "%064x".format(100000 + i); title = "Altro titolo $i"; author = "Scrittore $i"; format = "CATALOGO" } }
        val t = System.nanoTime()
        val plan = ImportPlanner.plan(rows + library.take(10), library)
        val ms = (System.nanoTime() - t) / 1_000_000
        assertEquals(2000, plan.fresh.size); assertEquals(10, plan.sameRecord)
        assertTrue("$ms ms", ms < 3000)
    }
}
