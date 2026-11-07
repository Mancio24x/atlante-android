package app.atlante.library.saga

import app.atlante.library.model.Book
import app.atlante.library.model.BookMatcher
import app.atlante.library.online.OnlineCatalog
import app.atlante.library.saga.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** A book in a carousel: in the library ([ownedBookId]) or only known from a source (never added by itself). */
data class ShelfItem(
    val title: String, val author: String, val year: String?, val ordinal: String?,
    val ownedBookId: String?, val read: Boolean,
    /** Cover already known (Open Library cover id), or an Open Library work whose cover is fetched when shown. */
    val coverUrl: String?, val coverWork: String?,
    /** Text for the online search when the user wants to add it. */
    val query: String,
    /** Other titles of the same work (original, Italian, without the saga prefix): used to avoid duplicates. */
    val alsoKnownAs: List<String> = emptyList(),
    val workId: Long? = null,
    /** "volume", "novella", "prequel"… as the source gives it. */
    val membership: String = "volume",
    /** Where the position comes from: "hardcover", "wikidata", "manual". */
    val source: String = "",
)

data class SagaShelf(
    val link: BookLink, val series: SeriesEntity?, val items: List<ShelfItem>, val total: Int, val owned: Int,
    val subseries: List<String>, val universe: String?,
    /** Sub-series name → indexes of [items] that belong to it (Red Rising Trilogy → 0, 1, 2). */
    val lanes: Map<String, List<Int>> = emptyMap(),
    /** Your own order of the volumes (work ids), when you set one: it wins over every source. */
    val manualOrder: List<Long>? = null,
)

/**
 * The three carousels of the book page: the rest of the saga, other books by the author, and
 * "Potrebbero interessarti" (deterministic: same genre, most published editions first, owned books excluded).
 * Online answers stay in the cache for [suggestionDays].
 */
class Suggestions(
    private val dao: SagaDao,
    private val library: () -> List<Book>,
    private val fetch: (String) -> String = { String(OnlineCatalog.request(it, 4 * 1024 * 1024), Charsets.UTF_8) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    var suggestionDays = 30

    /** A saga opened from an index (no book of the page): same shelf, without a link. */
    suspend fun series(seriesId: Long): SagaShelf? = saga(BookLink("", seriesId = seriesId))

    suspend fun saga(link: BookLink): SagaShelf? = withContext(Dispatchers.Default) {
        val seriesId = link.seriesId ?: return@withContext null
        val series = dao.series(seriesId) ?: return@withContext null
        val volumes = dao.volumes(seriesId)
        val linked = dao.linksForWorks(volumes.map { it.work.id }).groupBy { it.workId }
        val books = library()
        val items = volumes.map { v ->
            val authors = dao.authorsOf(v.work.id).joinToString(" & ") { it.name }
            val owned = linked[v.work.id]?.firstNotNullOfOrNull { l -> books.firstOrNull { it.id == l.bookId } }
                ?: books.firstOrNull { b -> titlesOf(v.work).any { t -> BookMatcher.same(Book().apply { title = t; author = authors.ifBlank { b.author } }, b) } }
            val title = owned?.title ?: v.work.italianTitle ?: v.work.originalTitle
            ShelfItem(title, owned?.author ?: authors, v.work.firstPublished, v.ordinal, owned?.id, owned?.status == Book.READ,
                null, v.work.openLibraryId, "$title ${authors.substringBefore(" & ")}".trim(), titlesOf(v.work), v.work.id, v.membership, v.source)
        }
        val manual = dao.overrides("series", seriesId.toString()).firstOrNull { it.field == "order" }?.value
            ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.takeIf { it.isNotEmpty() }
        val universe = series.universeId?.let { dao.universe(it)?.name }
        val subs = dao.subseries(seriesId)
        val lanes = subs.associate { sub -> val ids = dao.volumes(sub.id).map { it.work.id }.toSet(); sub.name to items.indices.filter { items[it].workId in ids } }
            .filterValues { it.isNotEmpty() }
        SagaShelf(link, series, items, items.size, items.count { it.ownedBookId != null }, subs.map { it.name }, universe, lanes, manual)
    }

    /** Saves your order of a saga's volumes (work ids, top to bottom); null goes back to the sources' order. */
    suspend fun saveOrder(seriesId: Long, workIds: List<Long>?) = withContext(Dispatchers.IO) {
        dao.putOverride(app.atlante.library.saga.db.UserOverride("series", seriesId.toString(), "order", workIds?.joinToString(",") ?: "", now()))
    }

    /**
     * Owned books by the same author first, then the author's other works from Open Library; works already
     * in [exclude] (the saga carousel, with the original titles of this very book) are left out.
     */
    suspend fun byAuthor(book: Book, exclude: List<ShelfItem> = emptyList()): List<ShelfItem> = withContext(Dispatchers.Default) {
        val name = book.author.split(" & ").first().trim()
        if (name.isBlank() || name == "Autore sconosciuto") return@withContext emptyList()
        val surnames = BookMatcher.surnames(name)
        val books = library()
        val owned = books.filter { it.id != book.id && BookMatcher.surnames(it.author).any { s -> s in surnames } }
            .sortedBy { it.year.ifBlank { "9999" } }.map { it.asItem() }
        val online = cachedSearch("author", name, "author=" + URLEncoder.encode(name, "UTF-8"))
            // The author search also returns anthologies and namesakes: the first author must be this very person.
            .filter { w -> w.authors.firstOrNull()?.let { sameAuthor(it, name) } == true }
            .filterNot { w -> bundle(w.title) || surnames.any { s -> w.title.lowercase().contains("$s's") || w.title.lowercase().contains("$s’s") } }
            .filterNot { w -> BookMatcher.same(Book().apply { title = w.title; author = name }, book) || owned.any { o -> sameTitle(w, o.title) } || listed(w, exclude) }
        owned + online.map { it.asItem() }
    }

    /**
     * Same genre and period (30 years around the book), most published first, at most two per author;
     * excludes owned books, this author and the other carousels. Same input, same list.
     */
    suspend fun mayInterest(book: Book, exclude: List<ShelfItem> = emptyList()): List<ShelfItem> = withContext(Dispatchers.Default) {
        val subject = Genres.subjectFor(book.genre) ?: return@withContext emptyList()
        val year = Regex("\\d{4}").find(book.year)?.value?.toInt()
        val (from, to) = if (year != null) (year - 30) to (year + 30) else 1980 to 2100
        val owned = TitleIndex(library()) { it.title }
        val surnames = BookMatcher.surnames(book.author)
        val perAuthor = HashMap<String, Int>()
        // Only works whose own genre it is (Discover.fits): not Wuthering Heights among comics because of an adaptation.
        val main = Genres.normalize(book.genre)
        val genre = Discover.GENRES.firstOrNull { Genres.normalize(it.label) == main }
        cachedSearch("subject2", "$subject:$from-$to", "q=" + URLEncoder.encode("subject:$subject first_publish_year:[$from TO $to]", "UTF-8"))
            .filter { w -> genre == null || w.subjects.isEmpty() || Discover.fits(genre, w.subjects) }
            .filterNot { w -> w.authors.any { a -> BookMatcher.surnames(a).any { it in surnames } } }
            .filterNot { w -> bundle(w.title) || owned.contains(w.title) || listed(w, exclude) }
            .filter { w -> (perAuthor.merge(w.authors.firstOrNull() ?: "", 1, Int::plus) ?: 0) <= 2 }
            .take(20).map { it.asItem() }
    }

    /** Cover of an Open Library work (first cover of /works/OL…W.json), remembered for [suggestionDays]. */
    suspend fun workCover(olWork: String): String? {
        if (!Regex("OL[0-9]+W").matches(olWork)) return null
        val json = cached("cover:$olWork", "cover") { fetch("https://openlibrary.org/works/$olWork.json") } ?: return null
        val id = JSONObject(json).optJSONArray("covers")?.optLong(0) ?: 0
        return OpenLibraryWorks.coverUrl(if (id > 0) id else 0)
    }

    data class Found(val key: String, val title: String, val authors: List<String>, val year: String?, val coverId: Long, val subjects: List<String> = emptyList())

    private suspend fun cachedSearch(kind: String, value: String, filter: String): List<Found> {
        val json = cached("$kind:${value.lowercase()}", kind) {
            fetch("https://openlibrary.org/search.json?$filter&sort=editions&lang=it&limit=60&fields=key,title,author_name,cover_i,first_publish_year,subject_key,editions,editions.title,editions.language")
        } ?: return emptyList()
        return parse(json)
    }

    private suspend fun cached(key: String, kind: String, load: () -> String): String? {
        dao.cached(key, now())?.let { return it.json }
        val json = withContext(Dispatchers.IO) { runCatching(load).getOrNull() } ?: return null
        dao.putCache(CacheEntry(key, kind, json, now(), now() + suggestionDays * 86_400_000L))
        return json
    }

    private fun Found.asItem() = ShelfItem(title, authors.joinToString(" & "), year, null, null, false,
        OpenLibraryWorks.coverUrl(coverId), null, "$title ${authors.firstOrNull() ?: ""}".trim())

    private fun Book.asItem() = ShelfItem(title, author, year.ifBlank { null }, null, id, status == Book.READ, null, null, title)

    private fun titlesOf(w: WorkEntity) = listOfNotNull(w.originalTitle, w.italianTitle) + w.altTitles.split('\n').filter { it.isNotBlank() } +
        listOfNotNull(w.originalTitle.substringAfter(": ", "").ifBlank { null }, w.italianTitle?.substringAfter(": ", "")?.ifBlank { null })

    private fun sameAuthor(a: String, b: String) = Suggestions.sameAuthor(a, b)
    private fun sameTitle(w: Found, title: String) = OnlineCatalog.sameTitle(w.title, title)
    private fun listed(w: Found, items: List<ShelfItem>) = items.any { i -> (listOf(i.title) + i.alsoKnownAs).any { sameTitle(w, it) } }

    companion object {
        private val BUNDLE = Regex("""(?i)\b(box(ed)? ?set|boxset|collection|collected|omnibus|cofanetto|serisi|series|serie|trilog(y|ia)|books? \d|\d[- ]book|volumes? \d+ ?[-–] ?\d+|complete|graphic novel|sampler|summary of|study guide)\b""")

        /** Box sets, collections, translated "series" bundles and study guides: not books to suggest. */
        fun bundle(title: String) = BUNDLE.containsMatchIn(title)

        /** "J. K. Rowling" = "J.K. Rowling" = "Joanne K. Rowling"? Same surname and same first initial. */
        fun sameAuthor(a: String, b: String): Boolean {
            fun words(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
            val x = words(a); val y = words(b)
            return x.isNotEmpty() && y.isNotEmpty() && x.last() == y.last() && x.first()[0] == y.first()[0]
        }

        /** Open Library search answer: the Italian edition title is preferred when there is one. Duplicates removed. */
        fun parse(json: String): List<Found> {
            val docs = JSONObject(json).optJSONArray("docs") ?: return emptyList()
            val seen = HashSet<String>()
            return (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }.mapNotNull { d ->
                val key = d.optString("key").substringAfterLast('/')
                val ed = d.optJSONObject("editions")?.optJSONArray("docs")?.optJSONObject(0)
                val italian = ed?.takeIf { e -> e.optJSONArray("language")?.let { l -> (0 until l.length()).any { l.optString(it) == "ita" } } == true }?.optString("title")
                // Catalog titles of Italian editions can carry notes: "Ritratto di Dorian Gray : (con annotazioni)".
                val title = italian?.substringBefore(" : ")?.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")?.trim()?.takeIf { it.length > 1 } ?: d.optString("title")
                if (title.isBlank() || !seen.add(BookMatcher.clean(title))) return@mapNotNull null
                val authors = d.optJSONArray("author_name")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                val subjects = d.optJSONArray("subject_key")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                Found(key, title, authors, d.optInt("first_publish_year").takeIf { it > 0 }?.toString(), d.optLong("cover_i"), subjects)
            }
        }
    }
}
