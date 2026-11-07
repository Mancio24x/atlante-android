package app.atlante.library.catalog

import app.atlante.library.model.Book
import app.atlante.library.model.BookMatcher
import app.atlante.library.model.SearchIndex

/**
 * Which records of an imported file (backup, CSV, catalog) are new, and which are works already in the library.
 * Each record is compared only with the library books sharing a key with it (same id, ISBN, file, title with an
 * author surname): an import of 2,000 rows into 1,500 books took 157 s compared all against all, on the main
 * thread, and Android closed the app. Records repeated in the file itself are counted once.
 * The identity rules are BookMatcher's and BibliographicEngine's: never the title alone, never another author.
 */
object ImportPlanner {
    data class Plan(val fresh: List<Book>, val sameRecord: Int, val reconciled: Int, val repeatedInFile: Int)

    private fun titleKeys(b: Book): Set<String> {
        val titles = listOf(b.title, b.meta.optString("originalTitle")).filter { it.isNotBlank() }
        // The whole title and its main part ("Il colibrì : romanzo" → "Il colibrì").
        val forms = titles.flatMap { t -> listOf(t, t.split(Regex("\\s*[:(\\[]|\\s+[-–—]\\s+"), 2)[0]) }.map(SearchIndex::normalize).filter { it.length >= 2 }
        val surnames = BookMatcher.surnames(b.author)
        return if (surnames.isEmpty()) forms.map { "t:$it|" }.toSet() else forms.flatMap { f -> surnames.map { "t:$f|$it" } }.toSet()
    }

    private fun keys(b: Book): Set<String> = buildSet {
        BookMatcher.isbns(b.isbn).forEach { add("i:$it") }
        b.meta.optString("fileSha256").takeIf { it.length == 64 }?.let { add("f:$it") }
        addAll(titleKeys(b))
    }

    fun plan(incoming: List<Book>, library: List<Book>, progress: (Int) -> Unit = {}): Plan {
        val ids = HashSet<String>(library.size * 2)
        val index = HashMap<String, MutableList<Book>>()
        fun add(b: Book) { b.id?.let { ids += it }; for (k in keys(b)) index.getOrPut(k) { ArrayList(2) }.add(b) }
        library.forEach(::add)
        val fresh = ArrayList<Book>(); var same = 0; var reconciled = 0; var repeated = 0
        val freshIds = HashSet<String>()
        for ((i, b) in incoming.withIndex()) {
            if (i % 50 == 0) progress(i)
            if (b.id != null && b.id in ids) { if (b.id in freshIds) repeated++ else same++; continue }
            val candidates = keys(b).flatMap { index[it].orEmpty() }.distinct()
            val match = candidates.firstOrNull { k -> BookMatcher.same(k, b) && authorsAgree(k, b) && !BibliographicEngine.different(k, b) }
            if (match != null) { if (match.id in freshIds) repeated++ else reconciled++; continue }
            fresh += b; b.id?.let { freshIds += it }; add(b)
        }
        progress(incoming.size)
        return Plan(fresh, same, reconciled, repeated)
    }

    private fun authorsAgree(a: Book, b: Book): Boolean {
        val sa = BookMatcher.surnames(a.author); val sb = BookMatcher.surnames(b.author)
        return sa.isEmpty() || sb.isEmpty() || sa.any { it in sb }
    }
}
