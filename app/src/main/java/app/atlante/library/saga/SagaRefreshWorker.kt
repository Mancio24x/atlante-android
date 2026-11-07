package app.atlante.library.saga

import android.content.Context
import androidx.work.*
import app.atlante.library.saga.db.AtlanteDatabase
import java.util.concurrent.TimeUnit

/**
 * Background refresh of the sagas from their source (Wikidata or Hardcover), every "Aggiorna le saghe ogni" days, only with a network and
 * enough battery. Manual sagas are skipped; at most [BATCH] sagas per run to stay gentle with the public services.
 */
class SagaRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Hardcover.configure(applicationContext)
        SagaRepository.configureCatalog(applicationContext)
        val settings = CacheSettings(applicationContext)
        val dao = AtlanteDatabase.get(applicationContext).saga()
        val repo = SagaRepository(dao).apply { refreshDays = settings.sagaRefreshDays }
        val due = dao.allSeries().map { it.series }.filter { !it.manual && it.wikidataId != null && it.parentId == null }
            .filter { System.currentTimeMillis() - it.refreshedAt >= settings.sagaRefreshDays * 86_400_000L }
            .sortedBy { it.refreshedAt }.take(BATCH)
        for (s in due) runCatching { repo.rebuild(WdSeriesLink(s.wikidataId!!, s.name, null, emptySet(), null, null, null, null)) }
        CacheManager.get(applicationContext).cleanup()
        return Result.success()
    }

    companion object {
        const val BATCH = 20
        private const val NAME = "saga-refresh"

        @JvmStatic fun schedule(context: Context) {
            val days = CacheSettings(context).sagaRefreshDays.toLong()
            val request = PeriodicWorkRequestBuilder<SagaRefreshWorker>(days, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
