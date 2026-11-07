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
    /**
     * Genre shown on the chip → Open Library subject; classics have no period limit. [words] recognize the genre
     * among a work's subjects (see [fits]); [query] replaces the plain subject search when one subject is too narrow.
     */
    data class Genre(val label: String, val subject: String, val since: Int? = 1980, val words: List<String> = emptyList(), val query: String? = null,
                     val minShare: Double = 0.0, val hardcover: List<String> = emptyList(), val hardcoverTop: Int = 3)

    data class Pick(val workKey: String, val title: String, val author: String, val year: String?, val coverUrl: String?, val italian: Boolean,
                    val subjects: List<String> = emptyList()) {
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
        if (p.workKey.startsWith("hc:")) return p        // Hardcover picks already carry the Italian edition
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
        val title = best.optString("title").substringBefore(" : ").trim().takeIf { it.isNotBlank() && looksItalian(it, p.title) } ?: return p
        return p.copy(title = title, coverUrl = OpenLibraryWorks.coverUrl(cover ?: 0) ?: p.coverUrl, italian = true)
    }

    private suspend fun candidates(genre: Genre?): List<Pick> {
        // With Hardcover connected: genres voted by its readers, much more reliable than Open Library's subjects.
        if (Hardcover.enabled && (genre == null || genre.hardcover.isNotEmpty())) {
            val json = cached("hc-discover:${genre?.label ?: "popolari"}") { Hardcover.discoverJson(genre?.hardcover) }
            val picks = json?.let { runCatching { parseHardcover(it, genre) }.getOrNull() }.orEmpty()
            if (picks.size >= 6) return picks
        }
        return openLibraryCandidates(genre)
    }

    private suspend fun openLibraryCandidates(genre: Genre?): List<Pick> {
        val year = Year.now().value
        val q = buildString {
            append("language:ita")
            genre?.let { append(" ").append(it.query ?: "subject:${it.subject}") }
            val since = if (genre == null) 1990 else genre.since
            since?.let { append(" first_publish_year:[").append(it).append(" TO ").append(year).append("]") }
        }
        // lang=it makes Open Library put the best Italian edition first in "editions": its title and cover come
        // with the same answer, with no further request per book.
        // Subjects come too: a work is shown under a genre only when that genre is really its own (see fits).
        val json = cached("discover3:${genre?.subject ?: "popolari"}") {
            fetch("https://openlibrary.org/search.json?q=" + URLEncoder.encode(q, "UTF-8") + "&sort=readinglog&limit=${if (genre == null) 80 else 200}&lang=it"
                + "&fields=key,title,author_name,cover_i,first_publish_year,subject_key,editions,editions.title,editions.language,editions.cover_i")
        } ?: return emptyList()
        return parse(json).filter { genre == null || fits(genre, it.subjects) }
    }

    private suspend fun cached(key: String, load: () -> String): String? {
        dao.cached(key, now())?.let { return it.json }
        val json = withContext(Dispatchers.IO) { runCatching(load).getOrNull() } ?: return null
        dao.putCache(CacheEntry(key, "discover", json, now(), now() + cacheDays * 86_400_000L))
        return json
    }

    companion object {
        private val ITALIAN_WORDS = setOf("il", "lo", "la", "i", "gli", "le", "l", "un", "uno", "una", "di", "del", "dello", "della", "dei", "degli", "delle",
            "e", "ed", "che", "per", "in", "nel", "nello", "nella", "nei", "a", "al", "alla", "ai", "da", "dal", "dalla", "con", "su", "sul", "sulla", "non")
        /**
         * Open Library sometimes marks other languages as Italian ("Boxi Jiekexun yu shen huo zhi dao", a Chinese
         * Percy Jackson): an "Italian" title is kept only with an Italian word in it, or a word of the original title.
         */
        @JvmStatic fun looksItalian(title: String, original: String): Boolean {
            fun words(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
                .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            val w = words(title); val o = words(original).filter { it.length > 2 }.toSet()
            return w.any { it in ITALIAN_WORDS } || w.any { it.length > 2 && it in o }
        }
        /** The edition Open Library chose for lang=it, when it really is Italian. */
        private fun italianEdition(doc: JSONObject): JSONObject? {
            val e = doc.optJSONObject("editions")?.optJSONArray("docs")?.optJSONObject(0) ?: return null
            val langs = e.optJSONArray("language") ?: return null
            return if ((0 until langs.length()).any { langs.optString(it) == "ita" }) e else null
        }

        val GENRES = listOf(
            Genre("Fantasy", "fantasy", words = listOf("fantasy", "magic", "wizard", "dragon"), hardcover = listOf("Fantasy")),
            Genre("Fantascienza", "science_fiction", words = listOf("science_fiction", "sci-fi", "dystopi", "space_opera", "cyberpunk"), hardcover = listOf("Science Fiction")),
            Genre("Giallo", "mystery", words = listOf("mystery", "detective", "crime", "murder", "giall", "polizies"), hardcover = listOf("Mystery", "Crime")),
            Genre("Thriller", "thrillers", words = listOf("thriller", "suspense", "spy"), hardcover = listOf("Thriller")),
            Genre("Horror", "horror", words = listOf("horror", "ghost", "vampire", "zombie", "supernatural", "gothic"), hardcover = listOf("Horror")),
            Genre("Romanzo storico", "historical_fiction", words = listOf("historical_fiction", "history_fiction", "romanzo_storico"), hardcover = listOf("Historical Fiction", "Historical")),
            Genre("Rosa", "romance", words = listOf("romance", "love_stor", "romantic"), hardcover = listOf("Romance")),
            Genre("Classici", "classic_literature", null, hardcover = listOf("Classics")),
            Genre("Ragazzi", "young_adult_fiction", words = listOf("young_adult", "juvenile", "children", "teen"), minShare = 0.1, hardcover = listOf("Young Adult")),
            // A classic with a comic adaptation among its editions carries comic subjects too: comics need many of them.
            Genre("Fumetti", "comics_&_graphic_novels", null, listOf("comic", "graphic_novel", "manga", "fumett"),
                "(subject:comics OR subject:manga OR subject:graphic_novels OR subject:fumetti)", minShare = 0.15, hardcover = listOf("Comics", "Graphic Novels", "Manga"), hardcoverTop = 1),
            Genre("Storia", "history", null, listOf("history", "storia", "historical_events"), hardcover = listOf("History")),
            Genre("Filosofia", "philosophy", null, listOf("philosophy", "filosofia", "ethics"), hardcover = listOf("Philosophy")),
        )

        /** Subjects about a book rather than of the book: adaptations, study guides, criticism. */
        private val NOT_THE_BOOK = listOf("adaptation", "adapted", "retelling", "adattament", "study_guide", "criticism", "interpretation")
        private val NEAR = mapOf("fantasy" to "science_fiction", "science_fiction" to "fantasy", "thrillers" to "mystery", "mystery" to "thrillers")

        /**
         * Open Library gives a work the subjects of all its editions: "Wuthering Heights" is a comic because a comic
         * adaptation exists, "The Hobbit" is horror and science fiction too. A work belongs to a genre only when that
         * genre has at least two subjects and as many (three quarters) as any other genre it has, or a real share of
         * all its subjects. Close genres (fantasy and science fiction, mystery and thriller) do not compete.
         */
        fun fits(genre: Genre, subjects: List<String>): Boolean {
            if (genre.words.isEmpty()) return true
            val own = subjects.map { it.lowercase() }.filter { s -> NOT_THE_BOOK.none { s.contains(it) } }
            fun score(g: Genre) = own.count { s -> g.words.any { s.contains(it) } && (g.subject != "history" || !s.contains("fiction")) }
            val mine = score(genre)
            if (mine < 2 || mine < genre.minShare * own.size) return false
            val other = GENRES.filter { it !== genre && it.words.isNotEmpty() && it.subject != "young_adult_fiction" && NEAR[genre.subject] != it.subject }.maxOfOrNull { score(it) } ?: 0
            return if (genre.minShare > 0) mine >= other else mine >= other * 0.75 || mine >= 0.25 * own.size
        }

        /** Hardcover answer → picks: Italian title and cover, the genre among the book's three most voted. */
        fun parseHardcover(json: String, genre: Genre?): List<Pick> {
            val books = JSONObject(json).optJSONArray("books") ?: return emptyList()
            val wanted = genre?.hardcover?.map { it.lowercase() }
            return (0 until books.length()).mapNotNull { books.optJSONObject(it) }.mapNotNull { b ->
                if (wanted != null && Hardcover.topGenres(b, genre.hardcoverTop).none { it in wanted }) return@mapNotNull null
                val it = b.optJSONArray("editions")?.optJSONObject(0) ?: return@mapNotNull null
                val cover = it.optJSONObject("image")?.optString("url")?.takeIf { u -> u.startsWith("http") }
                    ?: b.optJSONObject("image")?.optString("url")?.takeIf { u -> u.startsWith("http") } ?: return@mapNotNull null
                val author = b.optJSONArray("contributions")?.optJSONObject(0)?.optJSONObject("author")?.optString("name")?.takeIf { a -> a.isNotBlank() } ?: return@mapNotNull null
                val title = Hardcover.italianTitle(it.optString("title")) ?: b.optString("title")
                Pick("hc:" + b.optInt("id"), title, author, b.optInt("release_year").takeIf { y -> y > 0 }?.toString(), cover, true)
            }
        }

        /** Works with a cover only: the grid is made of covers. */
        fun parse(json: String): List<Pick> {
            val docs = JSONObject(json).optJSONArray("docs") ?: return emptyList()
            return (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }.mapNotNull { d ->
                val cover = d.optLong("cover_i").takeIf { it > 0 } ?: return@mapNotNull null
                val author = d.optJSONArray("author_name")?.optString(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val subjects = d.optJSONArray("subject_key")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                val pick = Pick(d.optString("key").substringAfterLast('/'), d.optString("title"), author, d.optInt("first_publish_year").takeIf { it > 0 }?.toString(),
                    OpenLibraryWorks.coverUrl(cover), false, subjects)
                italianEdition(d)?.let { e ->
                    val title = e.optString("title").substringBefore(" : ").trim()
                    val italianCover = e.optLong("cover_i").takeIf { it > 0 }
                    // Without its own cover the Italian edition is completed later by italian().
                    if (title.isBlank() || italianCover == null || !looksItalian(title, pick.title)) null
                    else pick.copy(title = title, coverUrl = OpenLibraryWorks.coverUrl(italianCover), italian = true)
                } ?: pick
            }.filter { it.title.isNotBlank() }
        }
    }
}
