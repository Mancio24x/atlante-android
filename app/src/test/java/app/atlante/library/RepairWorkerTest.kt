package app.atlante.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.atlante.library.catalog.RepairWorker
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.storage.LibraryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** Tests 11–13 and 17: the repair as WorkManager runs it, offline, with its record (bar and notification read it). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepairWorkerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun libraryRepairRunsInTheBackgroundAndRecordsRealProgress() = runBlocking {
        File(context.filesDir, "library.json").delete(); File(context.filesDir, "books").deleteRecursively()
        val store = LibraryStore.shared(context)
        for (b in store.snapshot()) store.remove(b)
        fun book(id: Char, title: String, isbn: String) = Book().apply { this.id = id.toString().repeat(64); this.title = title; author = "Umberto Eco"; this.isbn = isbn; format = "CATALOGO" }
        val a = book('a', "Il nome della rosa", "9788845292613").apply { status = Book.READ; finished = "2024-05-01" }
        val b = book('b', "The Name of the Rose", "9788845292613")
        ReadingJournal.session(b, 0, 50, 900, LocalDate.parse("2024-04-01"))
        store.addCatalog(a); store.addCatalog(b); store.addCatalog(book('c', "Il pendolo di Foucault", "9788845278655"))
        val worker = TestListenableWorkerBuilder<RepairWorker>(context).setInputData(workDataOf(RepairWorker.TYPE to RepairWorker.LIBRARY)).build()
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        val job = AtlanteDatabase.get(context).saga().job(worker.id.toString())!!
        assertEquals("done", job.state); assertEquals(3, job.total); assertEquals(3, job.done)
        assertEquals(1, job.duplicates); assertEquals(1, job.applied)
        val all = store.snapshot()
        assertEquals(2, all.size)                                            // same ISBN → one record
        val rosa = all.single { it.title == "Il nome della rosa" }
        assertEquals(1, ReadingJournal.array(rosa, "sessions").length()); assertEquals(Book.READ, rosa.status)
    }
}
