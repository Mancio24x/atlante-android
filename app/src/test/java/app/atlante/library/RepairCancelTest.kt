package app.atlante.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.atlante.library.catalog.RepairWorker
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.saga.db.RepairJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * «Annulla» on a repair that is not running (waiting, or stopped by Android and waiting to resume): no worker is
 * there to write "Annullato", so the record must be settled by the cancel itself, or the bar spins for ever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepairCancelTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dao get() = AtlanteDatabase.get(context).saga()
    private val old get() = System.currentTimeMillis() - 10 * 60_000L

    @Before fun workManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    private fun stateAfterWaiting(id: String, wanted: String): String? {
        var state: String? = null
        for (wait in 0 until 50) { state = runBlocking { dao.job(id)?.state }; if (state == wanted) break; Thread.sleep(100) }
        return state
    }

    /** Work that cannot start in the test (needs charging): it stays ENQUEUED, like a repair waiting its turn. */
    private fun waitingWork(): UUID {
        val request = OneTimeWorkRequestBuilder<RepairWorker>().setInputData(workDataOf(RepairWorker.TYPE to RepairWorker.LIBRARY))
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build()).build()
        WorkManager.getInstance(context).enqueue(request).result.get()
        return request.id
    }

    @Test fun cancelSettlesAWaitingRepairThatNeverRan() = runBlocking {
        val id = waitingWork().toString()
        dao.putJob(RepairJob(id, RepairWorker.LIBRARY, "queued", "In attesa", old))
        RepairWorker.cancel(context, id)
        assertEquals("cancelled", stateAfterWaiting(id, "cancelled"))
        assertFalse(dao.activeJobs().first().any { it.id == id })
    }

    @Test fun cancelSettlesARepairStoppedByAndroid() = runBlocking {
        // Stopped by Android: the record says "In pausa" (queued) or still "running" if the app was killed with it.
        val id = UUID.randomUUID().toString()
        dao.putJob(RepairJob(id, RepairWorker.LIBRARY, "running", "Applicazione delle modifiche", old, total = 900, done = 900))
        RepairWorker.cancel(context, id)
        assertEquals("cancelled", stateAfterWaiting(id, "cancelled"))
    }

    @Test fun recordsWithoutLiveWorkAreSettledButWaitingWorkIsNot() = runBlocking {
        val gone = UUID.randomUUID().toString()                       // WorkManager no longer knows it
        val cancelledElsewhere = waitingWork().also { WorkManager.getInstance(context).cancelWorkById(it).result.get() }.toString()
        val waiting = waitingWork().toString()                        // still waiting: left alone
        val justStarted = UUID.randomUUID().toString()                // just enqueued: WorkManager may not have it yet
        dao.putJob(RepairJob(gone, RepairWorker.LIBRARY, "running", "Analisi locale", old))
        dao.putJob(RepairJob(cancelledElsewhere, RepairWorker.SAGAS, "queued", "In pausa", old))
        dao.putJob(RepairJob(waiting, RepairWorker.COVERS, "queued", "In attesa", old))
        dao.putJob(RepairJob(justStarted, RepairWorker.MAPS, "queued", "In attesa", System.currentTimeMillis()))
        RepairWorker.settleOrphans(context)
        assertEquals("failed", dao.job(gone)!!.state)
        assertEquals("cancelled", dao.job(cancelledElsewhere)!!.state)
        assertEquals("queued", dao.job(waiting)!!.state)
        assertEquals("queued", dao.job(justStarted)!!.state)
    }
}
