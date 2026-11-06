package app.atlante.library.saga

import app.atlante.library.model.Book
import app.atlante.library.model.BookMatcher
import app.atlante.library.online.OnlineCatalog
import java.net.URLEncoder
import org.json.JSONObject

/** A work found on Open Library, with the titles of its editions (to recognize translations). */
data class OlWork(val key: String, val title: String, val authors: List<String>, val editionTitles: List<String>, val coverId: Long, val firstYear: String?)

/** Open Library works and editions (public API, shared rate limiter and cache). */
object OpenLibraryWorks {
    private val OLW = Regex("OL[0-9]+W")
    private fun json(url: String) = JSONObject(String(OnlineCatalog.request(url, 4 * 1024 * 1024), Charsets.UTF_8))

    /** /isbn/… → the work of that edition ("OL123W"), or null. */
    fun workForIsbn(isbn: String): String? = runCatching {
        json("https://openlibrary.org/isbn/$isbn.json").optJSONArray("works")?.optJSONObject(0)?.optString("key")?.substringAfterLast('/')?.takeIf { OLW.matches(it) }
    }.getOrNull()

    /** Works matching title and author; each carries the titles of its editions (all languages). */
    fun search(title: String, author: String): List<OlWork> = parseSearch(String(OnlineCatalog.request(
        "https://openlibrary.org/search.json?title=" + URLEncoder.encode(title, "UTF-8") + (if (author.isBlank()) "" else "&author=" + URLEncoder.encode(author, "UTF-8"))
            + "&limit=8&fields=key,title,author_name,cover_i,first_publish_year,editions,editions.title,editions.language", 4 * 1024 * 1024), Charsets.UTF_8))

    fun parseSearch(json: String): List<OlWork> {
        val docs = JSONObject(json).optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }.mapNotNull { d ->
            val key = d.optString("key").substringAfterLast('/'); if (!OLW.matches(key)) return@mapNotNull null
            val authors = d.optJSONArray("author_name")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val eds = d.optJSONObject("editions")?.optJSONArray("docs")
            val titles = eds?.let { e -> (0 until e.length()).mapNotNull { e.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotBlank() } } } ?: emptyList()
            OlWork(key, d.optString("title"), authors, titles, d.optLong("cover_i", 0), d.optString("first_publish_year").takeIf { it.isNotBlank() })
        }
    }

    /** Title of an edition of the work in the given language ("ita"), if Open Library has one; never invented. */
    fun titleInLanguage(workKey: String, language: String): Pair<String, Long>? = runCatching {
        val entries = json("https://openlibrary.org/works/$workKey/editions.json?limit=50").optJSONArray("entries") ?: return null
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val langs = e.optJSONArray("languages")?.toString() ?: continue
            if (langs.contains("/languages/$language\"")) {
                val cover = e.optJSONArray("covers")?.optLong(0) ?: 0L
                return e.optString("title").takeIf { it.isNotBlank() }?.let { it to cover }
            }
        }
        null
    }.getOrNull()

    /** Does this Open Library work describe the book? Title (or one of its edition titles) and an author surname. */
    fun matches(book: Book, w: OlWork): Int {
        val probe = Book().apply { author = book.author }
        val titles = listOf(w.title) + w.editionTitles
        val titleHit = titles.any { t -> BookMatcher.same(probe.apply { title = book.title }, Book().apply { title = t; author = w.authors.joinToString(" & ").ifBlank { "Autore sconosciuto" } }) }
        if (!titleHit) return 0
        val surnames = BookMatcher.surnames(book.author)
        val authorHit = surnames.isEmpty() || w.authors.any { a -> BookMatcher.surnames(a).any { it in surnames } }
        return if (authorHit) 85 else 0
    }

    fun coverUrl(coverId: Long, size: String = "M") = if (coverId > 0) "https://covers.openlibrary.org/b/id/$coverId-$size.jpg?default=false" else null
}
