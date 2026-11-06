package app.atlante.library.saga

import app.atlante.library.online.OnlineCatalog

/**
 * The library's titles, normalized once: "which books have this title?" (same rule as
 * [OnlineCatalog.sameTitle]) in constant time instead of comparing with every book. With a large library the
 * old pairwise comparison (80 candidates × every book) blocked the screen for seconds.
 */
class TitleIndex<T>(items: List<T>, title: (T) -> String?) {
    private val whole = HashMap<String, MutableList<T>>()
    private val main = HashMap<String, MutableList<T>>()

    init {
        for (item in items) {
            val keys = OnlineCatalog.titleKeys(title(item) ?: continue) ?: continue
            whole.getOrPut(keys[0]) { ArrayList(1) }.add(item)
            main.getOrPut(keys[1]) { ArrayList(1) }.add(item)
        }
    }

    /** Items whose title is the same as [title] for [OnlineCatalog.sameTitle]. */
    fun matches(title: String?): List<T> {
        val keys = OnlineCatalog.titleKeys(title ?: return emptyList()) ?: return emptyList()
        val a = whole[keys[0]]; val b = main[keys[1]]
        return when { a == null -> b ?: emptyList(); b == null -> a; else -> (a + b).distinct() }
    }

    fun contains(title: String?) = matches(title).isNotEmpty()
}
