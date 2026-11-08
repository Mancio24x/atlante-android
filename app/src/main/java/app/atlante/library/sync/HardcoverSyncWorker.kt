package app.atlante.library.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.atlante.library.MainActivity
import app.atlante.library.R
import app.atlante.library.storage.LibraryStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Hardcover sync in the background: the app can be closed. Progress ("Confronto con Hardcover · 120 di 2.400")
 * and an "Interrompi" button live in a notification; at the end a notification with the summary. Stopping
 * keeps what was already saved (the sync saves in groups of 100 books).
 */
class HardcoverSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val settings = HardcoverSync.Settings()
    private var lastNotice = 0L
    private var done = 0; private var total = 0

    override suspend fun doWork(): Result {
        val c = applicationContext
        channel(c)
        runCatching { setForeground(info("Collegamento a Hardcover…")) }
        val prefs = c.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        settings.createMissing = prefs.getBoolean("hardcoverCreate", true); settings.sendToRead = prefs.getBoolean("hardcoverToRead", false)
        return try {
            settings.token = HardcoverAuth.token(c)
            HardcoverSync.catalog = runCatching { app.atlante.library.catalog.CatalogDatabase.get(c).catalog() }.getOrNull()
            runCatching { app.atlante.library.catalog.LibraryIndex.syncNow(c) }
            val store = LibraryStore.shared(c)
            val report = HardcoverSync.sync(store, settings, object : HardcoverSync.Progress {
                override fun update(message: String) { if (isStopped) settings.cancelled = true; notice(message) }
                override fun count(done: Int, total: Int) { this@HardcoverSyncWorker.done = done; this@HardcoverSyncWorker.total = total }
            })
            val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM HH:mm", app.atlante.library.ui.L.locale()))
            val line = "$time · ${report.pushed} inviati, ${report.pulled} ricevuti, ${report.created + report.added} nuovi"
            prefs.edit().putString("hardcoverConflicts", org.json.JSONArray(report.conflicts).toString()).putString("hardcoverUser", report.user).putString("hardcoverLast", "Ultima sincronizzazione: $line").apply()
            val summary = "${report.remote} libri su Hardcover · ${report.linked} collegati\n${report.pushed} aggiornati su Hardcover · ${report.pulled} aggiornati in Atlante\n" +
                "${report.added} aggiunti a Hardcover · ${report.created} nuove schede in Atlante" +
                (if (report.recognized > 0) "\n${report.recognized} libri riconosciuti come già presenti: nessun doppione" else "") +
                (if (report.merged > 0) "\n${report.merged} doppioni di sincronizzazioni precedenti uniti da soli (resta il tuo libro)" else "") +
                (if (report.conflicts.isNotEmpty()) "\n${report.conflicts.size} libri cambiati sia qui sia su Hardcover: scegli tu in Sync › «Conflitti con Hardcover»" else "")
            finished(if (settings.cancelled) "Sincronizzazione interrotta" else "Hardcover sincronizzato", summary)
            Result.success(workDataOf(SUMMARY to summary, NOTES to report.notes.take(60).joinToString("\n").take(8000), STOPPED to settings.cancelled))
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            finished("Sincronizzazione non riuscita", message)
            Result.failure(workDataOf(ERROR to message))
        }
    }

    /** At most twice a second: the notification shows the step and the bar. */
    private fun notice(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotice < 500) return
        lastNotice = now
        setForegroundAsync(info(message))
    }

    private fun info(message: String): ForegroundInfo {
        val c = applicationContext
        val n = NotificationCompat.Builder(c.also { app.atlante.library.ui.L.init(it) }, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(app.atlante.library.ui.L.t("Sincronizzazione con Hardcover"))
            .setContentText(app.atlante.library.ui.L.t(message)).setOnlyAlertOnce(true).setOngoing(true).setSilent(true).setContentIntent(openApp(c))
            .setProgress(total, done, total == 0)
            .addAction(0, "Interrompi", WorkManager.getInstance(c).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTICE, n)
    }

    private fun finished(title: String, text: String) {
        val c = applicationContext
        val n = NotificationCompat.Builder(c.also { app.atlante.library.ui.L.init(it) }, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(app.atlante.library.ui.L.t(title)).setContentText(app.atlante.library.ui.L.t(text.substringBefore('\n')))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(openApp(c)).build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(DONE, n) }
    }

    companion object {
        const val NAME = "hardcover-sync"
        const val SUMMARY = "summary"; const val NOTES = "notes"; const val ERROR = "error"; const val STOPPED = "stopped"
        private const val CHANNEL = "sync"; private const val NOTICE = 4101; private const val DONE = 4102

        private fun channel(c: Context) {
            if (Build.VERSION.SDK_INT >= 26) c.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, app.atlante.library.ui.L.t("Sincronizzazione"), NotificationManager.IMPORTANCE_LOW).apply { description = app.atlante.library.ui.L.t("Avanzamento di Sync con Hardcover") })
        }

        private fun openApp(c: Context) = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /** Starts the sync unless one is already running. */
        @JvmStatic fun start(c: Context) {
            val request = OneTimeWorkRequestBuilder<HardcoverSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(c).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
