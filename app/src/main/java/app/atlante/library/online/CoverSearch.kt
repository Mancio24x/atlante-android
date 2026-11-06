package app.atlante.library.online

import android.graphics.BitmapFactory
import app.atlante.library.model.Book
import app.atlante.library.model.BookMatcher
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Cover engine: collects candidates from several free public sources, checks that each one belongs to this
 * book (same ISBN, or same title AND author), downloads them and keeps the sharpest real cover.
 *
 * Sources, most exact first: Open Library by ISBN (10 and 13), Google Books by ISBN, Apple Books by ISBN,
 * the other editions of the same work on Open Library (Italian first), then title + author on Apple Books,
 * Google Books and Open Library. Placeholders ("image not available", 1×1 pixels) are rejected.
 */
object CoverSearch {
    data class Candidate(val url: String, val source: String, val exact: Boolean, val italian: Boolean = false)
    data class Cover(val bytes: ByteArray, val source: String, val width: Int, val height: Int, val exact: Boolean, val italian: Boolean = false) {
        /** Exact edition first, then an Italian edition, then size; landscape or tiny images lose. */
        val score: Int get() {
            val ratio = height.toFloat() / width.coerceAtLeast(1)
            return (if (exact) 2000 else 0) + (if (italian) 700 else 0) + minOf(width, 1200) + (if (ratio in 1.2f..1.8f) 300 else -600)
        }
    }

    /** Italian ISBN groups: 978-88 and 979-12. */
    fun italianIsbn(text: String) = Regex("(97888|97912)\\d{8}").containsMatchIn(text)

    /** Minimum width of a usable cover; [GOOD] is enough to stop looking. */
    const val MIN = 150
    const val GOOD = 500

    private val fetch: (String, Int) -> ByteArray get() = { url, limit -> OnlineCatalog.request(url, limit) }

    fun isbns(book: Book): List<String> {
        val all = LinkedHashSet<String>()
        for (raw in BookMatcher.isbns(book.isbn + " " + book.meta.optJSONObject("ids")?.optString("isbn", "").orEmpty())) {
            all += raw
            if (raw.length == 10) all += BookMatcher.to13(raw)
            if (raw.length == 13 && raw.startsWith("978")) to10(raw)?.let { all += it }
        }
        return all.toList()
    }

    private fun to10(isbn13: String): String? {
        val core = isbn13.substring(3, 12)
        val sum = core.mapIndexed { i, c -> (10 - i) * (c - '0') }.sum()
        val check = (11 - sum % 11) % 11
        return core + if (check == 10) "X" else check.toString()
    }

    /** Every candidate address, most exact first; the network is used lazily by [best] and [all]. */
    fun candidates(book: Book, get: (String, Int) -> ByteArray = fetch): Sequence<Candidate> = sequence {
        val isbns = isbns(book)
        for (i in isbns) yield(Candidate("https://covers.openlibrary.org/b/isbn/$i-L.jpg?default=false", "Open Library", true))
        for (i in isbns.filter { it.length == 13 }.take(1)) {
            yieldAll(google(book, "isbn:$i", get, exact = true))
            yieldAll(apple(book, i, get, exact = true))
            yieldAll(openLibraryEditions(book, "q=$i", get, exact = false))
        }
        val author = book.author.takeUnless { it == "Autore sconosciuto" }?.split(" & ", ", ")?.firstOrNull()?.trim().orEmpty()
        if (book.title.isNotBlank() && author.isNotBlank()) {
            yieldAll(apple(book, "${book.title} $author", get, exact = false))
            yieldAll(google(book, "intitle:${book.title} inauthor:$author", get, exact = false))
            yieldAll(openLibraryEditions(book, "title=" + enc(book.title) + "&author=" + enc(author), get, exact = false))
        }
    }

    /** The best cover found, or null. Stops early once an exact, sharp cover is found. */
    fun best(book: Book, get: (String, Int) -> ByteArray = fetch, maxDownloads: Int = 8): Cover? {
        var best: Cover? = null; var downloads = 0
        for (c in candidates(book, get).distinctBy { it.url }) {
            if (downloads >= maxDownloads) break
            downloads++
            val cover = download(c, get) ?: continue
            if (best == null || cover.score > best.score) best = cover
            if (cover.exact && cover.width >= GOOD) break
        }
        return best
    }

    /** All distinct covers found (for the "Scegli copertina online" picker), best first. */
    fun all(book: Book, get: (String, Int) -> ByteArray = fetch, maxDownloads: Int = 14): List<Cover> {
        val found = ArrayList<Cover>(); val sizes = HashSet<Int>()
        for (c in candidates(book, get).distinctBy { it.url }.take(maxDownloads)) {
            val cover = download(c, get) ?: continue
            if (sizes.add(cover.bytes.size)) found += cover   // the same image from two addresses only once
        }
        return found.sortedByDescending { it.score }
    }

    fun download(c: Candidate, get: (String, Int) -> ByteArray = fetch): Cover? {
        val bytes = runCatching { get(c.url, 6 * 1024 * 1024) }.getOrNull() ?: return null
        // Google answers an unknown cover with a PNG "image not available"; its real covers are JPEG.
        if (c.source.startsWith("Google") && !((bytes.size > 3) && (bytes[0].toInt() and 255) == 0xFF && (bytes[1].toInt() and 255) == 0xD8)) return null
        val (w, h) = dimensions(bytes) ?: return null
        if (w < MIN || h < MIN || bytes.size < 4000) return null      // placeholders and 1×1 images
        return Cover(bytes, c.source, w, h, c.exact, c.italian)
    }

    fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    // ---- sources --------------------------------------------------------------------------------

    private fun google(book: Book, query: String, get: (String, Int) -> ByteArray, exact: Boolean): List<Candidate> = runCatching {
        val items = JSONObject(String(get("https://www.googleapis.com/books/v1/volumes?maxResults=5&q=" + enc(query), 1024 * 1024))).optJSONArray("items")
            ?: return@runCatching emptyList()
        (0 until items.length()).mapNotNull { items.optJSONObject(it) }.mapNotNull { item ->
            val info = item.optJSONObject("volumeInfo") ?: return@mapNotNull null
            if (!exact && !OnlineCatalog.googleMatches(book, info)) return@mapNotNull null
            if (info.optJSONObject("imageLinks") == null) return@mapNotNull null
            // The content service gives a larger image than the API thumbnail.
            Candidate("https://books.google.com/books/content?id=${enc(item.optString("id"))}&printsec=frontcover&img=1&zoom=1&fife=w800-h1200", "Google Books", exact,
                info.optString("language") == "it")
        }
    }.getOrDefault(emptyList())

    /** Apple Books public search (no key): artwork can be asked at 1000×1000 at most, kept proportional. */
    private fun apple(book: Book, term: String, get: (String, Int) -> ByteArray, exact: Boolean): List<Candidate> = runCatching {
        val results = JSONObject(String(get("https://itunes.apple.com/search?media=ebook&country=it&limit=6&term=" + enc(term), 1024 * 1024))).optJSONArray("results")
            ?: return@runCatching emptyList()
        (0 until results.length()).mapNotNull { results.optJSONObject(it) }.mapNotNull { r ->
            val art = r.optString("artworkUrl100").takeIf { it.startsWith("https://") && it.contains(".mzstatic.com/") } ?: return@mapNotNull null
            val sameIsbn = isbns(book).any { art.contains(it) }
            if (exact && !sameIsbn) return@mapNotNull null
            if (!sameIsbn && !(titleMatches(book.title, r.optString("trackName")) && authorMatches(book.author, r.optString("artistName")))) return@mapNotNull null
            Candidate(art.replace(Regex("/\\d+x\\d+bb\\.(jpg|png)$"), "/1000x1000bb.jpg"), "Apple Books", sameIsbn, italianIsbn(art))
        }
    }.getOrDefault(emptyList())

    /** Covers of the work's editions: Italian editions first, then the work's main cover. */
    private fun openLibraryEditions(book: Book, filter: String, get: (String, Int) -> ByteArray, exact: Boolean): List<Candidate> = runCatching {
        val docs = JSONObject(String(get("https://openlibrary.org/search.json?$filter&lang=it&limit=5&fields=title,author_name,cover_i,editions,editions.cover_i,editions.language,editions.title", 1024 * 1024)))
            .optJSONArray("docs") ?: return@runCatching emptyList()
        val out = ArrayList<Candidate>()
        for (d in (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }) {
            val eds = d.optJSONObject("editions")?.optJSONArray("docs")
            val edTitles = eds?.let { e -> (0 until e.length()).mapNotNull { e.optJSONObject(it)?.optString("title") } } ?: emptyList()
            val byText = filter.startsWith("title=")
            if (byText && (listOf(d.optString("title")) + edTitles).none { titleMatches(book.title, it) }) continue
            if (byText) {
                val authors = d.optJSONArray("author_name")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                if (authors.none { authorMatches(book.author, it) }) continue
            }
            for (i in 0 until (eds?.length() ?: 0)) {
                val e = eds!!.optJSONObject(i) ?: continue
                val id = e.optLong("cover_i"); if (id <= 0) continue
                val italian = e.optJSONArray("language")?.let { l -> (0 until l.length()).any { l.optString(it) == "ita" } } == true
                out += Candidate("https://covers.openlibrary.org/b/id/$id-L.jpg?default=false", "Open Library (edizione)", exact, italian)
            }
            d.optLong("cover_i").takeIf { it > 0 }?.let { out += Candidate("https://covers.openlibrary.org/b/id/$it-L.jpg?default=false", "Open Library (opera)", exact) }
        }
        out.sortedByDescending { it.italian }
    }.getOrDefault(emptyList())

    // ---- matching -------------------------------------------------------------------------------

    private fun words(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .split(Regex("[^a-z0-9]+")).filter { it.length > 1 || it.all(Char::isDigit) }

    private val STOP = setOf("il", "lo", "la", "le", "gli", "un", "una", "uno", "di", "del", "della", "dei", "the", "of", "and", "e", "a")

    private val NUMBER = Regex("^([0-9]+|[ivx]+)$")

    /** Significant words of a title part: articles dropped, volume numbers kept apart. */
    private fun significant(part: String) = words(part).filterNot { it in STOP }.toSet()

    /** Parts of a title ("Blackwater I. La piena" → "Blackwater I", "La piena"); volume labels are marked. */
    private fun rawParts(title: String) = title.split(Regex("\\s*[.:;]\\s+|\\s+[-–—]\\s+|\\s*#\\s*\\d+[.:]?\\s*|\\s*\\(.*?\\)\\s*"))

    /** Title parts without volume labels ("Blackwater I", "Libro 2"): those prove nothing about the book. */
    private fun parts(title: String) = rawParts(title)
        .filterNot { part -> Normalizer.normalize(part.lowercase(), Normalizer.Form.NFD).split(Regex("[^a-z0-9]+")).any { NUMBER.matches(it) } }
        .map { significant(it) }.filter { it.isNotEmpty() }

    /**
     * Same book title, strictly:
     * - the same significant words; or
     * - the same title part that is not a volume label ("La piena" = "Blackwater I. La piena", but
     *   "Blackwater I" alone proves nothing); or
     * - a title of two or more words contained in a longer one ("L'ultimo impero" in
     *   "Mistborn #1. L'ultimo impero - The Final Empire"). One-word titles ("Dune") must match exactly.
     */
    fun titleMatches(mine: String, theirs: String): Boolean {
        val a = significant(mine); val b = significant(theirs)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val pa = parts(mine); val pb = parts(theirs)
        if (pa.any { x -> (x.size >= 2 || x.first().length >= 4) && pb.any { it == x } }) return true
        val numbers = a.filter { NUMBER.matches(it) }
        return a.size >= 2 && b.containsAll(a) && b.size <= a.size + 8 && numbers.all { it in b }
    }

    fun authorMatches(mine: String, theirs: String): Boolean {
        val surnames = BookMatcher.surnames(mine)
        if (surnames.isEmpty()) return false
        val other = words(theirs)
        return surnames.any { it in other }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
