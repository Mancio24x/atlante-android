package app.atlante.library.saga

import android.content.Context
import app.atlante.library.online.OnlineCatalog
import app.atlante.library.saga.db.AtlanteDatabase
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/** User-configurable limits of temporary data. Personal data (library, notes, progress) is never part of it. */
class CacheSettings(context: Context) {
    private val prefs = context.getSharedPreferences("atlante-cache", Context.MODE_PRIVATE)
    var coverMb: Int get() = prefs.getInt("coverMb", 100); set(v) = prefs.edit().putInt("coverMb", v.coerceIn(20, 1000)).apply()
    var suggestionDays: Int get() = prefs.getInt("suggestionDays", 30); set(v) = prefs.edit().putInt("suggestionDays", v.coerceIn(1, 365)).apply()
    var sagaRefreshDays: Int get() = prefs.getInt("sagaRefreshDays", 7); set(v) = prefs.edit().putInt("sagaRefreshDays", v.coerceIn(1, 90)).apply()
}

/**
 * Covers of books that are not in the library (carousels, saga maps): files on disk, least recently used
 * removed first when the total goes over the limit. Covers of library books are stored with the books instead.
 */
class CoverCache(private val directory: File, private val maxBytes: () -> Long) {
    fun get(url: String, download: (String) -> ByteArray = { OnlineCatalog.download(it, 4 * 1024 * 1024) }): ByteArray {
        val file = File(directory, key(url))
        if (file.isFile) { file.setLastModified(System.currentTimeMillis()); return file.readBytes() }
        val bytes = download(url)
        directory.mkdirs()
        val tmp = File(directory, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) tmp.delete()
        trim()
        return bytes
    }

    fun bytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    /** Oldest-used first until the total fits the limit. Returns the bytes freed. */
    fun trim(limit: Long = maxBytes()): Long {
        val files = directory.listFiles()?.sortedBy { it.lastModified() } ?: return 0
        var total = files.sumOf { it.length() }; var freed = 0L
        for (f in files) { if (total <= limit) break; val n = f.length(); if (f.delete()) { total -= n; freed += n } }
        return freed
    }

    fun clear(): Long = trim(0)

    private fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
}

/** Space used by temporary data and the actions of the "Cache e dati temporanei" settings. */
class CacheManager(private val context: Context) {
    val settings = CacheSettings(context)
    val covers = CoverCache(File(context.cacheDir, "suggestion-covers")) { settings.coverMb * 1024L * 1024L }
    private val dao get() = AtlanteDatabase.get(context).saga()

    data class Usage(val covers: Long, val catalog: Long, val suggestions: Long, val graph: Long)

    suspend fun usage() = Usage(
        covers.bytes(),
        OnlineCatalog.cacheDirectory()?.listFiles()?.sumOf { it.length() } ?: 0L,
        dao.cacheBytes(),
        context.getDatabasePath(AtlanteDatabase.NAME).let { db -> listOf(db, File(db.path + "-wal")).sumOf { it.length() } },
    )

    /** Automatic cleanup (app start): expired suggestions and covers over the limit. */
    suspend fun cleanup(now: Long = System.currentTimeMillis()): Int { covers.trim(); return dao.deleteExpired(now) }

    fun clearCovers() = covers.clear()
    fun clearCatalog() { OnlineCatalog.cacheDirectory()?.listFiles()?.forEach { it.delete() } }
    suspend fun clearSuggestions() = dao.clearCache()

    companion object {
        @Volatile private var instance: CacheManager? = null
        @JvmStatic fun get(context: Context) = instance ?: synchronized(this) { instance ?: CacheManager(context.applicationContext).also { instance = it } }
        @JvmStatic fun cleanupAsync(context: Context) {
            val manager = get(context)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { manager.cleanup() } }
        }
    }
}
