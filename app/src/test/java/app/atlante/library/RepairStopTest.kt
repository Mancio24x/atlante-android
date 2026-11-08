package app.atlante.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.atlante.library.catalog.BibliographicEngine
import app.atlante.library.catalog.RepairWorker
import app.atlante.library.model.Book
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.saga.db.RepairJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Ripara duplicati stops: «Annulla» during the search, no endless restarts, no pile of finished results. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepairStopTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun cancelStopsTheSearchItself() {
        val books = (0 until 200).map { i -> Book().apply { id = "%064x".format(i); title = "Romanzo numero ${i / 2}"; author = "Elena Ferrante"; format = "CATALOGO" } }
        var asked = 0
        try { BibliographicEngine.duplicates(books, emptyMap()) { ++asked > 3 }; fail("the search must stop when cancelled") }
        catch (e: kotlin.coroutines.cancellation.CancellationException) { assertTrue(asked in 4..10) }
    }

    @Test fun afterThreeStopsByAndroidItDoesNotStartOverAgain() = runBlocking {
        val id = UUID.randomUUID()
        context.getSharedPreferences("repair-stops", Context.MODE_PRIVATE).edit().putInt(id.toString(), 3).commit()
        val worker = TestListenableWorkerBuilder<RepairWorker>(context).setId(id).setInputData(workDataOf(RepairWorker.TYPE to RepairWorker.LIBRARY)).build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Failure)
        val job = AtlanteDatabase.get(context).saga().job(id.toString())!!
        assertEquals("failed", job.state); assertTrue(job.message, job.message.contains("3 volte"))
    }

    @Test fun finishedResultsWithoutDecisionsAreCleared() = runBlocking {
        val dao = AtlanteDatabase.get(context).saga()
        val now = System.currentTimeMillis()
        dao.putJob(RepairJob("done-1", RepairWorker.LIBRARY, "done", "Completato", now))
        dao.putJob(RepairJob("failed-1", RepairWorker.SAGAS, "failed", "Errore", now))
        dao.putJob(RepairJob("decide-1", RepairWorker.MAPS, "done", "Completato", now, proposals = 2))
        dao.putJob(RepairJob("run-1", RepairWorker.COVERS, "running", "Analisi", now))
        dao.dropSettled()
        val left = dao.jobs().first().map { it.id }.filter { it.endsWith("-1") }.toSet()
        assertEquals(setOf("decide-1", "run-1"), left)
    }
}
