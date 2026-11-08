package app.atlante.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.atlante.library.catalog.RepairWorker
import app.atlante.library.model.Book
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.storage.LibraryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A big library with many doubles (a sync that added copies): the repair ends, in time, and leaves one record per book. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuplicatesScaleTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun manyDoublesAreMergedInTime() = runBlocking {
        File(context.filesDir, "library.json").delete(); File(context.filesDir, "books").deleteRecursively()
        val store = LibraryStore.shared(context)
        for (b in store.snapshot()) store.remove(b)
        val books = ArrayList<Book>()
        for (i in 0 until 1200) books += Book().apply { id = "%064x".format(i); title = "Romanzo numero ${words(i)}"; author = "Autore ${words(i / 3)} Cognome${i}"; format = "CATALOGO" }
        // 300 doubles: same title and author; a third of them also with the same ISBN; 50 triples.
        for (i in 0 until 300) books += Book().apply { id = "%064x".format(100000 + i); title = books[i].title.uppercase(); author = books[i].author; format = "CATALOGO" }
        for (i in 0 until 50) books += Book().apply { id = "%064x".format(200000 + i); title = books[i].title; author = books[i].author; format = "CATALOGO" }
        for (b in books) store.addCatalog(b)
        val start = System.currentTimeMillis()
        val worker = TestListenableWorkerBuilder<RepairWorker>(context).setInputData(workDataOf(RepairWorker.TYPE to RepairWorker.LIBRARY)).build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        val took = System.currentTimeMillis() - start
        val job = AtlanteDatabase.get(context).saga().job(worker.id.toString())!!
        println("duplicates repair: ${took} ms, applied ${job.applied}, proposals ${job.proposals}")
        assertEquals(1200, store.snapshot().size)
        assertTrue("took $took ms", took < 60_000)
    }

    private fun words(n: Int): String { val w = listOf("alba", "bosco", "cielo", "duna", "eco", "fiume", "gelo", "isola", "lago", "mare"); var x = n; val sb = StringBuilder()
        do { sb.append(w[x % 10]).append(' '); x /= 10 } while (x > 0); return sb.toString().trim() }
}
