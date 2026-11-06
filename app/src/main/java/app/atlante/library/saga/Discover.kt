package app.atlante.library.saga

import app.atlante.library.model.Book
import app.atlante.library.model.BookMatcher
import app.atlante.library.online.OnlineCatalog
import app.atlante.library.saga.db.CacheEntry
import app.atlante.library.saga.db.SagaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Year

/**
 * "Scopri": six books at a time, the most read on Open Library among those with an Italian edition
 * (Open Library "want to read" logs), optionally of one genre. One book per author, nothing you already have,
 * no box sets. The Italian title and cover come from a real Italian edition when there is one.
 */
class Discover(
    private val dao: SagaDao,
    private val library: () -> List<Book>,
    private val fetch: (String) -> String = { String(OnlineCatalog.request(it, 4 * 1024 * 1024), Charsets.UTF_8) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Genre shown on the chip → Open Library subject; classics have no period limit. */
    data class Genre(val label: String, val subject: String, val since: Int? = 1980)

    data class Pick(val workKey: String, val title: String, val author: String, val year: String?, val coverUrl: String?, val italian: Boolean) {
        val query get() = "$title $author".trim()
    }

    var cacheDays = 7

    /** The [page]-th group of six (0 = the first): the same answer for the same genre and page. */
    suspend fun picks(genre: Genre?, page: Int = 0): List<Pick> {
        val candidates = candidates(genre)
        // Off the main thread, and through an index of the library: never one comparison per (candidate, book).
        return withContext(Dispatchers.Default) {
            val owned = TitleIndex(library()) { it.title }
            val seenAuthors = HashSet<String>()
            candidates.filter { c ->
                if (Suggestions.bundle(c.title)) return@filter false
                val same = owned.matches(c.title); if (same.isEmpty()) return@filter true
                val surnames = BookMatcher.surnames(c.author)
                same.none { b -> BookMatcher.surnames(b.author).any { it in surnames } }
            }.filter { c -> seenAuthors.add(c.author.lowercase()) }        // one book per author
                .drop(page * 6).take(6)
        }
    }

    fun hasMore(count: Int, page: Int) = count == 6 && page < 6

    /** Italian edition of a work (title and cover), or the work as it is. */
    suspend fun italian(p: Pick): Pick {
        val json = cached("ita:${p.workKey}") { fetch("https://openlibrary.org/works/${p.workKey}/editions.json?limit=100") } ?: return p
        val entries = runCatching { JSONObject(json).optJSONArray("entries") }.getOrNull() ?: return p
        val italian = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.filter { e ->
            val langs = e.optJSONArray("languages")
            langs != null && (0 until langs.length()).any { langs.optJSONObject(it)?.optString("key") == "/languages/ita" }
        }
        // With a cover first, then the most complete title ("Harry Potter e la pietra filosofale", not "Harry Potter").
        val best = italian.sortedWith(compareByDescending<JSONObject> { (it.optJSONArray("covers")?.optLong(0) ?: 0) > 0 }
            .thenByDescending { it.optString("title").split(' ').size }).firstOrNull() ?: return p
        val cover = best.optJSONArray("covers")?.optLong(0)?.takeIf { it > 0 }
        val title = best.optString("title").substringBefore(" : ").trim().ifBlank { p.title }
        return p.copy(title = title, coverUrl = OpenLibraryWorks.coverUrl(cover ?: 0) ?: p.coverUrl, italian = true)
    }

    private suspend fun candidates(genre: Genre?): List<Pick> {
        val year = Year.now().value
        val q = buildString {
            append("language:ita")
            genre?.let { append(" subject:").append(it.subject) }
            val since = if (genre == null) 1990 else genre.since
            since?.let { append(" first_publish_year:[").append(it).append(" TO ").append(year).append("]") }
        }
        // lang=it makes Open Library put the best Italian edition first in "editions": its title and cover come
        // with the same answer, with no further request per book.
        val json = cached("discover2:${genre?.subject ?: "popolari"}") {
            fetch("https://openlibrary.org/search.json?q=" + URLEncoder.encode(q, "UTF-8") + "&sort=readinglog&limit=80&lang=it"
                + "&fields=key,title,author_name,cover_i,first_publish_year,editions,editions.title,editions.language,editions.cover_i")
        } ?: return emptyList()
        return parse(json)
    }

    private suspend fun cached(key: String, load: () -> String): String? {
        dao.cached(key, now())?.let { return it.json }
        val json = withContext(Dispatchers.IO) { runCatching(load).getOrNull() } ?: return null
        dao.putCache(CacheEntry(key, "discover", json, now(), now() + cacheDays * 86_400_000L))
        return json
    }

    companion object {
        /** The edition Open Library chose for lang=it, when it really is Italian. */
        private fun italianEdition(doc: JSONObject): JSONObject? {
            val e = doc.optJSONObject("editions")?.optJSONArray("docs")?.optJSONObject(0) ?: return null
            val langs = e.optJSONArray("language") ?: return null
            return if ((0 until langs.length()).any { langs.optString(it) == "ita" }) e else null
        }

        val GENRES = listOf(
            Genre("Fantasy", "fantasy"), Genre("Fantascienza", "science_fiction"), Genre("Gialli", "mystery"), Genre("Thriller", "thrillers"),
            Genre("Horror", "horror"), Genre("Romanzi storici", "historical_fiction"), Genre("Rosa", "romance"), Genre("Classici", "classic_literature", null),
            Genre("Ragazzi", "young_adult_fiction"), Genre("Fumetti", "comics_&_graphic_novels", null), Genre("Storia", "history", null), Genre("Filosofia", "philosophy", null),
        )

        /** Works with a cover only: the grid is made of covers. */
        fun parse(json: String): List<Pick> {
            val docs = JSONObject(json).optJSONArray("docs") ?: return emptyList()
            return (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }.mapNotNull { d ->
                val cover = d.optLong("cover_i").takeIf { it > 0 } ?: return@mapNotNull null
                val author = d.optJSONArray("author_name")?.optString(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val pick = Pick(d.optString("key").substringAfterLast('/'), d.optString("title"), author, d.optInt("first_publish_year").takeIf { it > 0 }?.toString(),
                    OpenLibraryWorks.coverUrl(cover), false)
                italianEdition(d)?.let { e ->
                    val title = e.optString("title").substringBefore(" : ").trim()
                    val italianCover = e.optLong("cover_i").takeIf { it > 0 }
                    // Without its own cover the Italian edition is completed later by italian().
                    if (title.isBlank() || italianCover == null) null
                    else pick.copy(title = title, coverUrl = OpenLibraryWorks.coverUrl(italianCover), italian = true)
                } ?: pick
            }.filter { it.title.isNotBlank() }
        }
    }
}
