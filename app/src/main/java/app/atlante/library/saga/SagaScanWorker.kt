package app.atlante.library.saga

import android.content.Context
import androidx.work.*
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.storage.LibraryStore
import java.util.concurrent.TimeUnit

/**
 * Recognizes the sagas of the whole library in the background, not only of the books whose page is opened:
 * books never checked, failed searches older than a day and, after a new Hardcover key, the books without a
 * sure answer. A batch per run (Hardcover allows about one book every two seconds, Wikidata fewer), then the
 * next run is queued until nothing is left. Only the saga database is written: library.json is never touched,
 * the map and lists read the links through SagaIndex. Choices made by the user are never changed.
 */
class SagaScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Hardcover.configure(applicationContext)
        val books = runCatching { LibraryStore.shared(applicationContext).snapshot() }.getOrElse { return Result.retry() }
        val dao = AtlanteDatabase.get(applicationContext).saga()
        val repo = SagaRepository(dao).apply { refreshDays = CacheSettings(applicationContext).sagaRefreshDays }
        val prefs = applicationContext.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        val recheckSince = prefs.getLong(RECHECK_SINCE, 0L)
        val now = System.currentTimeMillis()
        val links = dao.links().associateBy { it.bookId }
        val due = books.filter { b ->
            val l = links[b.id]
            when {
                l == null -> true
                l.status in SagaRepository.USER_STATUSES -> false
                l.status in UNSURE && l.checkedAt < recheckSince -> true
                l.status == "unidentified" -> now - l.checkedAt > 86_400_000L
                else -> false
            }
        }
        val batch = due.take(if (Hardcover.enabled) BATCH_HARDCOVER else BATCH)
        for (b in batch) {
            if (isStopped) return Result.success()
            // A Hardcover sync is running: it gets the request budget, the scan goes on in a minute.
            if (app.atlante.library.sync.HardcoverSync.syncing) { next(applicationContext); return Result.success() }
            val force = links[b.id]?.let { it.status in UNSURE && it.checkedAt < recheckSince } ?: false
            runCatching { repo.recognize(b, force) }
        }
        if (due.size > batch.size) next(applicationContext)
        else {
            prefs.edit().remove(RECHECK_SINCE).apply()
            runCatching { dao.pruneSeries(); dao.pruneUniverses() }
        }
        return Result.success()
    }

    companion object {
        const val BATCH = 25
        const val BATCH_HARDCOVER = 80
        private const val NAME = "saga-scan"
        private const val STATE = "atlante-saga-scan"
        private const val RECHECK_SINCE = "recheckSince"
        private val UNSURE = setOf("to_confirm", "no_saga", "unidentified")

        private fun request(delayMinutes: Long) = OneTimeWorkRequestBuilder<SagaScanWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()

        /** At app start: scans what is new since the last time; a scan already queued is kept. */
        @JvmStatic fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request(0))
        }

        /** After a new Hardcover key: the books without a sure saga are checked again with Hardcover. */
        @JvmStatic fun recheck(context: Context) {
            context.getSharedPreferences(STATE, Context.MODE_PRIVATE).edit().putLong(RECHECK_SINCE, System.currentTimeMillis()).apply()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request(0))
        }

        private fun next(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME + "-next", ExistingWorkPolicy.REPLACE, request(1))
        }
    }
}
