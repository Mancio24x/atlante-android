package app.atlante.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.atlante.library.catalog.RepairWorker
import app.atlante.library.model.Book
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.saga.db.RepairJob
import app.atlante.library.storage.LibraryStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Tests 12, 14, 15: the notification's text, stopping a repair, resuming it from its checkpoint (offline catalog). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepairResumeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: LibraryStore
    private val catalog = File("src/main/assets/catalog/atlante-catalog.db").isFile || File("app/src/main/assets/catalog/atlante-catalog.db").isFile

    private fun book(id: Char, title: String, author: String) = Book().apply { this.id = id.toString().repeat(64); this.title = title; this.author = author; format = "CATALOGO" }

    @Before fun library() {
        File(context.filesDir, "library.json").delete(); File(context.filesDir, "books").deleteRecursively()
        store = LibraryStore.shared(context); for (b in store.snapshot()) store.remove(b)
        store.addCatalog(book('a', "Harry Potter e la pietra filosofale", "J. K. Rowling"))
        store.addCatalog(book('b', "Il signore degli anelli", "J. R. R. Tolkien"))
        store.addCatalog(book('c', "Il nome della rosa", "Umberto Eco"))
    }

    @Test fun notificationShowsTheRealProgress() {
        assertEquals("650 / 1.000 libri analizzati — 65%", RepairWorker.progressText(RepairJob("x", "library", "running", total = 1000, done = 650)))
        assertEquals("Analisi locale", RepairWorker.progressText(RepairJob("x", "library", "running", phase = "Analisi locale")))
    }

    /** Stopped by Android after two books: the same work starts again after them and does each book once. */
    @Test fun resumesFromTheCheckpoint() = runBlocking {
        org.junit.Assume.assumeTrue(catalog)
        val id = UUID.randomUUID()
        val dao = AtlanteDatabase.get(context).saga()
        dao.putJob(RepairJob(id.toString(), RepairWorker.METADATA, "queued", checkpoint = 2, found = 0))
        val worker = TestListenableWorkerBuilder<RepairWorker>(context).setId(id)
            .setInputData(workDataOf(RepairWorker.TYPE to RepairWorker.METADATA, RepairWorker.ONLINE to false)).build()
        worker.doWork()
        val job = dao.job(id.toString())!!
        assertEquals("done", job.state); assertEquals(3, job.done); assertEquals(3, job.checkpoint)
        // Books are taken in id order: a and b were done before the stop, only c is completed now.
        assertTrue(store.find("c".repeat(64))!!.meta.optJSONObject("ids")?.optString("wikidata").orEmpty().startsWith("Q"))
        assertEquals("", store.find("a".repeat(64))!!.meta.optJSONObject("ids")?.optString("wikidata").orEmpty())
        assertEquals(3, store.snapshot().size)
    }

    /** Stopped while it runs: the record leaves "running", the library is whole (every book once, none half-written). */
    @Test fun stoppingLeavesTheLibraryWhole() = runBlocking {
        org.junit.Assume.assumeTrue(catalog)
        val worker = TestListenableWorkerBuilder<RepairWorker>(context)
            .setInputData(workDataOf(RepairWorker.TYPE to RepairWorker.METADATA, RepairWorker.ONLINE to false)).build()
        val run = launch(Dispatchers.Default) { runCatching { worker.doWork() } }
        val dao = AtlanteDatabase.get(context).saga()
        withTimeout(10_000) { while (dao.job(worker.id.toString())?.state != "running") delay(5) }
        run.cancelAndJoin()
        val job = dao.job(worker.id.toString())!!
        assertNotEquals("running", job.state)
        val books = LibraryStore.shared(context).snapshot()
        assertEquals(3, books.size); assertEquals(3, books.map { it.id }.toSet().size)
        for (b in books) assertNotNull(Book.from(b.json()))
    }
}
