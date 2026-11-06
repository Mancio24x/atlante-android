package app.atlante.library.saga

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.ui.MapGroups
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Sagas recognized by the saga engine (Wikidata / Open Library), by book id, for the reading map.
 * Read in the background; [refresh] calls back on the main thread only when something changed.
 */
object SagaIndex {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ACCEPTED = setOf("auto", "confirmed", "manual")

    @Volatile private var known: Map<String, MapGroups.Known> = emptyMap()
    @JvmStatic fun known(): Map<String, MapGroups.Known> = known

    @JvmStatic fun refresh(context: Context, onChange: Runnable) {
        val app = context.applicationContext
        scope.launch {
            val fresh = runCatching { load(app) }.getOrNull() ?: return@launch
            if (fresh.keys == known.keys && fresh.all { (id, k) -> known[id]?.let { it.series == k.series && it.ordinal == k.ordinal } == true }) return@launch
            known = fresh
            Handler(Looper.getMainLooper()).post(onChange)
        }
    }

    private suspend fun load(context: Context): Map<String, MapGroups.Known> {
        val dao = AtlanteDatabase.get(context).saga()
        val names = HashMap<Long, String?>()
        val out = HashMap<String, MapGroups.Known>()
        for (link in dao.links()) {
            val seriesId = link.seriesId ?: continue
            if (link.status !in ACCEPTED) continue
            val name = names.getOrPut(seriesId) { dao.series(seriesId)?.name } ?: continue
            out[link.bookId] = MapGroups.Known(name, link.workId?.let { dao.ordinal(seriesId, it) })
        }
        return out
    }
}
