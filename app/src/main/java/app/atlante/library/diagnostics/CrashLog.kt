package app.atlante.library.diagnostics

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Crash and error reports, kept on the phone (nothing is sent anywhere):
 * - a crash anywhere in the app (screens, widgets, background work) writes crash-<time>.txt with the error, its
 *   causes, the app's recent log, version, phone and the last actions;
 * - errors shown to you or met by a background job are added to errors.txt (the last 200 KB);
 * - at the next start the new reports are copied to Download/Atlante/Diagnostica (Android 10+), where any file
 *   manager opens them; Impostazioni › Privacy e progetto shares or clears them.
 */
object CrashLog {
    private const val DIR = "diagnostics"
    private const val ERRORS = "errors.txt"
    private const val ERRORS_MAX = 200 * 1024
    private const val KEEP = 30
    private val trail = ArrayDeque<String>()
    @Volatile private var installed = false
    private lateinit var app: Context

    fun dir(c: Context): File = File(c.applicationContext.filesDir, DIR).apply { mkdirs() }

    /** Called once, first thing, by the Application: every uncaught exception is written before the app closes. */
    @JvmStatic @Synchronized fun install(context: Context) {
        if (installed) return
        installed = true; app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { writeCrash(app, thread, error) } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A short note of what the app was doing ("Biblioteca", "Ripara duplicati avviata"): the last 60 go in reports. */
    @JvmStatic fun step(text: String) {
        synchronized(trail) { trail.addLast(stamp() + "  " + text); while (trail.size > 60) trail.removeFirst() }
    }

    /** An error that did not close the app (a dialog, a failed repair): one entry in errors.txt. */
    @JvmStatic fun error(c: Context, where: String, error: Throwable?) {
        try {
            val f = File(dir(c), ERRORS)
            if (f.length() > ERRORS_MAX) { val tail = f.readText().takeLast(ERRORS_MAX / 2); f.writeText(tail) }
            f.appendText("=== ${stamp()} · $where\n${error?.let(::trace) ?: "(nessun dettaglio)"}\n")
            step("errore: $where: ${error?.message ?: ""}")
        } catch (_: Exception) { }
    }

    @JvmStatic fun writeCrash(c: Context, thread: Thread, error: Throwable): File {
        val name = "crash-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + ".txt"
        val f = File(dir(c), name)
        val text = buildString {
            append("Atlante Library · rapporto di crash\n")
            append("Quando: ").append(stamp()).append('\n')
            append(about(c))
            append("Thread: ").append(thread.name).append("\n\n")
            append("=== Errore\n").append(trace(error)).append('\n')
            append("=== Ultime azioni\n"); synchronized(trail) { trail.forEach { append(it).append('\n') } }
            append("\n=== Log dell'app (ultime righe)\n").append(logcat())
        }
        f.writeText(text)
        prune(c)
        return f
    }

    @JvmStatic fun reports(c: Context): List<File> =
        dir(c).listFiles()?.filter { it.isFile && it.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() }.orEmpty()

    @JvmStatic fun crashes(c: Context): List<File> = reports(c).filter { it.name.startsWith("crash-") }

    @JvmStatic fun clear(c: Context) { reports(c).forEach { it.delete() }; File(dir(c), ".exported").delete() }

    /**
     * New reports copied to Download/Atlante/Diagnostica (Android 10+, no permission needed): returns how many
     * crash reports were new since the last start, so the app can say so.
     */
    @JvmStatic fun exportNew(c: Context): Int {
        val mark = File(dir(c), ".exported")
        val done = if (mark.isFile) mark.readLines().toMutableSet() else mutableSetOf()
        var newCrashes = 0
        for (f in reports(c)) {
            val key = f.name + ":" + f.length()
            if (key in done) continue
            if (f.name.startsWith("crash-") && done.none { it.startsWith(f.name + ":") }) newCrashes++
            if (Build.VERSION.SDK_INT >= 29) runCatching { toDownloads(c, f) }
            done += key
        }
        runCatching { mark.writeText(done.joinToString("\n")) }
        return newCrashes
    }

    /** The folder as a person sees it in a file manager. */
    @JvmStatic fun publicFolder(): String = "Download/Atlante/Diagnostica"

    private fun toDownloads(c: Context, f: File) {
        if (Build.VERSION.SDK_INT < 29) return
        val resolver = c.contentResolver
        val where = Environment.DIRECTORY_DOWNLOADS + "/Atlante/Diagnostica/"
        // The same name again (errors.txt grows): the old copy is replaced.
        resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(f.name, where))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name); put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, where)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
    }

    private fun prune(c: Context) { crashes(c).drop(KEEP).forEach { it.delete() } }

    private fun about(c: Context): String {
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).let { "${it.versionName} (${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()})" } }.getOrDefault("?")
        return "App: ${c.packageName} $version\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nTelefono: ${Build.MANUFACTURER} ${Build.MODEL}\n"
    }

    private fun trace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())

    /** This app's own recent log (no permission needed for its own process). */
    private fun logcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "400", "--pid=" + Process.myPid()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(); out
    }.getOrDefault("(log non disponibile)")
}
