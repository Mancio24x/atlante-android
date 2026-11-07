package app.atlante.library.storage

import android.content.Context
import app.atlante.library.model.Book
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The last 100 merges, with both records as they were before: "Annulla l'ultima unione" uses them. */
object MergeHistory {
    private fun file(c: Context) = File(c.filesDir, "merge-history.json")

    @JvmStatic @Synchronized fun add(c: Context, keep: Book, other: Book, moves: List<Array<File>>) {
        val all = list(c)
        all.put(JSONObject().put("at", System.currentTimeMillis()).put("keep", keep.json()).put("other", other.json())
            .put("moves", JSONArray().apply { moves.forEach { put(JSONArray().put(it[0].path).put(it[1].path)) } }))
        val kept = JSONArray(); for (i in maxOf(0, all.length() - 100) until all.length()) kept.put(all.get(i))
        val tmp = File(file(c).path + ".tmp"); tmp.writeText(kept.toString()); tmp.renameTo(file(c))
        // Widgets and links that pointed to the removed record follow the kept one.
        c.getSharedPreferences("merges", Context.MODE_PRIVATE).edit().putString(other.id, keep.id).apply()
    }

    @JvmStatic @Synchronized fun list(c: Context): JSONArray = runCatching { JSONArray(file(c).readText()) }.getOrDefault(JSONArray())

    @JvmStatic @Synchronized fun removeLast(c: Context): JSONObject? {
        val all = list(c); if (all.length() == 0) return null
        val last = all.getJSONObject(all.length() - 1)
        val kept = JSONArray(); for (i in 0 until all.length() - 1) kept.put(all.get(i))
        file(c).writeText(kept.toString())
        c.getSharedPreferences("merges", Context.MODE_PRIVATE).edit().remove(last.getJSONObject("other").optString("id")).apply()
        return last
    }

    /** The record a removed one was merged into (followed through several merges). */
    @JvmStatic fun redirect(c: Context, id: String): String {
        val p = c.getSharedPreferences("merges", Context.MODE_PRIVATE); var cur = id; var n = 0
        while (n++ < 10) cur = p.getString(cur, null) ?: return cur
        return cur
    }
}
