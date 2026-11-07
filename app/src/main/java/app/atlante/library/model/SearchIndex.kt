package app.atlante.library.model

import java.text.Normalizer

/**
 * Local search of the library, by words and with a ranking:
 * - accents, case and apostrophes do not matter ("l'ultimo" = "L’Ultimo" = "ultimo");
 * - every word typed must start a word of the book (title, other titles, author, saga, ISBN, genre, tags,
 *   publisher, notes); the reading journal and the sync data are not searched;
 * - the book's other titles count like its title: the original and Italian titles of its identified work and of
 *   its editions ("The Final Empire" finds "L'ultimo impero"), and the original title written in the sheet;
 * - ranking: same title, then title beginning, then title words, other titles, saga, author, the rest.
 * The words of each book are kept until the book changes (no work per keystroke beyond the comparison).
 */
object SearchIndex {
    /** Book id → other titles, filled from the bibliographic graph off the main thread. */
    @JvmStatic @Volatile var aliases: Map<String, List<String>> = emptyMap()

    private class Entry(val book: Book, val aliasesSeen: Map<String, List<String>>, val title: String, val fields: List<Pair<Int, List<String>>>)
    private val entries = HashMap<String, Entry>()

    private const val TITLE = 10; private const val ALIAS = 9; private const val ISBN = 10; private const val SERIES = 7
    private const val AUTHOR = 6; private const val SUBTITLE = 5; private const val GENRE = 3; private const val TAGS = 3
    private const val PUBLISHER = 2; private const val NOTES = 1

    @JvmStatic fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    @JvmStatic fun words(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    /** Query words: one-letter words ("l'", "e") are left out unless they are all there is. */
    @JvmStatic fun queryWords(query: String): List<String> {
        val all = words(query)
        return all.filter { it.length > 1 || it[0].isDigit() }.ifEmpty { all }
    }

    private fun entry(b: Book): Entry {
        val known = aliases
        synchronized(entries) { entries[b.id]?.let { if (it.book === b && it.aliasesSeen === known) return it } }
        val others = (known[b.id].orEmpty() + b.meta.optString("originalTitle")).filter { it.isNotBlank() }
        val fields = listOf(
            TITLE to words(b.title), ALIAS to others.flatMap(::words), SUBTITLE to words(b.subtitle), SERIES to words(b.series),
            AUTHOR to words(b.author + " " + b.meta.optString("translators")), ISBN to (words(b.isbn.replace("-", "")) + BookMatcher.isbns(b.isbn)).distinct(),
            GENRE to words(b.genre), TAGS to words(b.tags), PUBLISHER to words(b.publisher), NOTES to words(b.notes + " " + journalText(b)))
        val e = Entry(b, known, normalize(b.title), fields)
        synchronized(entries) { entries[b.id] = e }
        return e
    }

    /** Your notes, quotes and highlights in the reading journal (their text only: never the sync data). */
    private fun journalText(b: Book): String {
        val out = StringBuilder()
        for (key in listOf("notes", "highlights", "quotes")) {
            val list = b.journal.optJSONArray(key) ?: continue
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                for (field in listOf("body", "text", "title", "comment", "note")) o.optString(field).takeIf { it.isNotBlank() }?.let { out.append(it).append(' ') }
            }
        }
        return out.toString()
    }

    /** 0 when a word of the query is missing, otherwise higher for better matches. */
    @JvmStatic fun score(b: Book, query: String): Int = score(b, normalize(query), queryWords(query))

    private fun score(b: Book, phrase: String, words: List<String>): Int {
        if (words.isEmpty()) return 1
        val e = entry(b)
        var total = 0
        for (w in words) {
            var best = 0
            for ((weight, list) in e.fields) for (x in list) {
                val s = when { x == w -> weight * 2; x.startsWith(w) -> weight; else -> 0 }
                if (s > best) best = s
            }
            if (best == 0) return 0
            total += best
        }
        if (e.title == phrase) total += 100
        else if (e.title.startsWith(phrase)) total += 50
        else if (e.fields[1].second.isNotEmpty() && aliasPhrase(b, phrase)) total += 40
        return total
    }

    private fun aliasPhrase(b: Book, phrase: String) = (aliases[b.id].orEmpty() + b.meta.optString("originalTitle")).any { normalize(it).startsWith(phrase) }

    /** The books matching [query], best first (stable: equal scores keep the given order). */
    @JvmStatic fun rank(books: List<Book>, query: String): List<Book> {
        val phrase = normalize(query); val words = queryWords(query)
        if (words.isEmpty()) return books
        return books.map { it to score(it, phrase, words) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    /** The people of a book's author field: "A & B", "A, B" are two authors. */
    @JvmStatic fun authors(b: Book): List<String> = b.author.split(" & ", ";").map { it.trim() }
        .filter { it.isNotEmpty() && it != "Autore sconosciuto" }

    @JvmStatic fun sameAuthor(b: Book, author: String): Boolean { val n = normalize(author); return authors(b).any { normalize(it) == n } }
}
