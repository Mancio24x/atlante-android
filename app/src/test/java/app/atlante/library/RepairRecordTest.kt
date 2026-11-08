package app.atlante.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.atlante.library.catalog.RepairWorker
import app.atlante.library.saga.db.AtlanteDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A repair that ends fast must not stay "In attesa" for ever (one more line in the bar at every run). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepairRecordTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun aFastRepairEndsAsDoneNotQueued() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val dao = AtlanteDatabase.get(context).saga()
        val ids = (0 until 4).map {
            val id = RepairWorker.start(context, RepairWorker.LIBRARY)
            // The record written by start() and the run itself: done within a few seconds.
            var state: String? = null
            for (wait in 0 until 100) { state = dao.job(id.toString())?.state; if (state == "done") break; Thread.sleep(100) }
            assertEquals("run $it", "done", state)
            id.toString()
        }
        // Four runs, one result: the last one (the older results of the same repair are gone).
        assertEquals(listOf(ids.last()), dao.jobs().first().map { it.id }.filter { it in ids })
    }
}
