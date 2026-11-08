package app.atlante.library.diagnostics

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.atlante.library.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Sends crash and error reports as Issues of the project's private reports repository, only if you said yes
 * (asked once at the first start; Impostazioni › Privacy e progetto changes it). Every 6 hours with a connection,
 * and right after a start that found a new crash. Personal data are removed first (titles, names, keys, emails);
 * the phone is a random code, to tell reports apart. Without the build's token nothing is ever sent.
 */
class ReportUploader(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        upload(applicationContext) { title, body -> post(title, body) }; Result.success()
    } catch (e: IOException) { Result.retry() }

    companion object {
        private const val PREFS = "diagnostics"
        private const val NAME = "atlante-reports"
        private const val MAX_PER_RUN = 10

        @JvmStatic fun configured(): Boolean = BuildConfig.REPORTS_TOKEN.isNotEmpty()
        /** null: not asked yet; true / false: your answer. */
        @JvmStatic fun consent(c: Context): Boolean? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { if (it.contains("send")) it.getBoolean("send", false) else null }
        @JvmStatic fun setConsent(c: Context, yes: Boolean) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("send", yes).apply()
            if (yes) now(c)
        }

        @JvmStatic fun schedule(c: Context) {
            if (!configured()) return
            val request = PeriodicWorkRequestBuilder<ReportUploader>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        @JvmStatic fun now(c: Context) {
            if (!configured() || consent(c) != true) return
            val request = OneTimeWorkRequestBuilder<ReportUploader>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(c).enqueueUniqueWork("$NAME-now", ExistingWorkPolicy.KEEP, request)
        }

        /** A random code for this phone (no name, no account): reports from one phone can be told apart. */
        @JvmStatic fun phone(c: Context): String {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return p.getString("phone", null) ?: UUID.randomUUID().toString().take(8).also { p.edit().putString("phone", it).apply() }
        }

        /**
         * The reports not sent yet, each through [post] (title, body → HTTP status). Crash reports one Issue each, the
         * errors written since the last time one Issue. Stops on a refused token (401/403/404): nothing is retried in vain.
         * Returns how many Issues were created.
         */
        @JvmStatic fun upload(c: Context, post: (String, String) -> Int): Int {
            if (consent(c) != true) return 0
            val dir = CrashLog.dir(c)
            val sentFile = File(dir, ".sent"); val sent = if (sentFile.isFile) sentFile.readLines().toMutableSet() else mutableSetOf()
            val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"
            var created = 0
            fun refused(status: Int) = status == 401 || status == 403 || status == 404
            for (f in CrashLog.crashes(c).sortedBy { it.lastModified() }) {
                if (f.name in sent || created >= MAX_PER_RUN) continue
                val text = scrub(f.readText())
                val error = text.lineSequence().dropWhile { !it.startsWith("=== Errore") }.drop(1).firstOrNull()?.trim().orEmpty()
                val status = post("Crash $version · ${error.take(110)}", body(c, version, text))
                if (refused(status)) break
                if (status in 200..299) { sent += f.name; created++ } else throw IOException("GitHub: $status")
            }
            val errors = File(dir, "errors.txt")
            if (errors.isFile && created < MAX_PER_RUN) {
                val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val from = p.getLong("errorsSent", 0).let { if (it > errors.length()) 0 else it }   // the log was trimmed: from the start
                if (errors.length() > from) {
                    val chunk = errors.readBytes().copyOfRange(from.toInt(), errors.length().toInt()).toString(Charsets.UTF_8)
                    val count = Regex("(?m)^=== ").findAll(chunk).count()
                    val status = post("Errori $version · $count", body(c, version, scrub(chunk)))
                    if (status in 200..299) { p.edit().putLong("errorsSent", errors.length()).apply(); created++ }
                    else if (!refused(status)) throw IOException("GitHub: $status")
                }
            }
            runCatching { sentFile.writeText(sent.joinToString("\n")) }
            return created
        }

        private fun body(c: Context, version: String, text: String) =
            "Telefono: `${phone(c)}` · versione $version\n\n```\n" + text.take(60000) + "\n```\n"

        /** Personal data out: titles in «», @names, emails, keys and passwords, and the app's own book ids. */
        @JvmStatic fun scrub(text: String): String = text
            .replace(Regex("«[^»\\n]{0,200}»"), "«…»")
            .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]+"), "Bearer ***")
            .replace(Regex("(?i)(token|password|passwd|key|authorization)([\"']?\\s*[=:]\\s*[\"']?)[^\\s,\"'}]+"), "$1$2***")
            .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "***@***")
            .replace(Regex("(?<![A-Za-z0-9])@[A-Za-z0-9_.-]{2,}"), "@***")
            .replace(Regex("\\b[0-9a-f]{64}\\b"), "<libro>")

        private fun post(title: String, body: String): Int {
            val c = URL("https://api.github.com/repos/${BuildConfig.REPORTS_REPO}/issues").openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer " + BuildConfig.REPORTS_TOKEN)
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "AtlanteLibraryAndroid")
            c.setRequestProperty("Content-Type", "application/json")
            try {
                c.outputStream.use { it.write(JSONObject().put("title", title).put("body", body).toString().toByteArray()) }
                return c.responseCode
            } finally { c.disconnect() }
        }
    }
}
