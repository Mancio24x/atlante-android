package app.atlante.library.saga

import android.content.Context
import app.atlante.library.model.BookMatcher
import app.atlante.library.model.Book
import app.atlante.library.sync.HardcoverAuth
import app.atlante.library.sync.HardcoverSync
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** A book identified on Hardcover: its series already classified (saga, sub-series, universe). */
data class HcFound(val links: WdWorkLinks, val score: Int, val via: String)

/**
 * Hardcover (hardcover.app) as a saga source, with the personal key each user enters in the settings.
 * Two requests per book: find it (ISBN, otherwise search by title and author), then the book with every series
 * and their volumes, Italian edition titles included. Series lists are kept in memory for the saga rebuild, so
 * recognizing a book does not ask for them again.
 *
 * Ids go in the graph's external-id columns with a prefix ("hcb:123" books, "hc:456" series), so they never
 * collide with Wikidata ids ("Q…") and the saga refresh knows which source to ask.
 */
object Hardcover {
    const val WORK = "hcb:"
    const val SERIES = "hc:"
    /** MainActivity.getPreferences(): where the settings keep the key. */
    const val PREFS = "MainActivity"
    const val KEY = "hardcoverToken"
    const val USE_FOR_SAGAS = "hardcoverSagas"

    /** Kinds given to the classified series, so that [WdSeriesLink.isUniverse] works the same for both sources. */
    val SAGA_KIND = setOf("Q277759")
    val UNIVERSE_KIND = setOf("Q559618")

    @Volatile private var key = ""
    @JvmStatic val enabled: Boolean get() = key.isNotEmpty()

    /** Reads the key (and the "use for sagas" switch) saved in the settings. */
    @JvmStatic fun configure(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        appContext = context.applicationContext
        // With OAuth the token changes every week: it is asked for at each request (see api).
        val on = p.getBoolean(USE_FOR_SAGAS, true) && HardcoverAuth.connected(context)
        useKey(if (!on) "" else if (HardcoverAuth.usesOauth(context)) OAUTH else p.getString(KEY, "") ?: "")
    }

    private const val OAUTH = "oauth"
    @Volatile private var appContext: Context? = null

    /** The token for a request: the personal key, or the OAuth token (renewed when needed, once more after a 401). */
    private fun call(query: String, variables: JSONObject): JSONObject {
        val context = appContext
        if (key != OAUTH || context == null) return HardcoverSync.request(key, query, variables)
        return try { HardcoverSync.request(HardcoverAuth.token(context), query, variables) }
        catch (e: java.io.IOException) {
            if (e.message?.startsWith("Hardcover non accetta") != true) throw e
            val renewed = HardcoverAuth.renewAfterRejection(context) ?: throw e
            HardcoverSync.request(renewed, query, variables)
        }
    }

    @JvmStatic fun useKey(token: String) { key = token.trim() }

    fun interface Api { fun call(query: String, variables: JSONObject): JSONObject }
    /** The network; tests replace it with recorded answers. */
    @Volatile var api: Api = Api { q, v -> call(q, v) }

    /** Checks a key before saving it: the Hardcover user name, or an exception with the reason. */
    @JvmStatic fun username(token: String): String {
        val me = HardcoverSync.request(token, "query { me { username } }", JSONObject()).opt("me")
        val user = if (me is JSONArray) me.optJSONObject(0) else me as? JSONObject
        return user?.optString("username")?.takeIf { it.isNotBlank() } ?: throw java.io.IOException("Hardcover non ha riconosciuto la chiave.")
    }

    // ---- recognition --------------------------------------------------------------------------------

    fun identify(book: Book): HcFound? {
        for (isbn in BookMatcher.isbns(book.isbn).take(2)) {
            val id = bookForIsbn(isbn) ?: continue
            return details(id)?.let { HcFound(it, 95, "ISBN → Hardcover") }
        }
        val (id, score) = search(book) ?: return null
        val via = when { score >= 85 -> "Hardcover: titolo e autore"; score >= 75 -> "Hardcover: titolo simile e autore"; else -> "Hardcover: solo titolo" }
        return details(id)?.let { HcFound(it, score, via) }
    }

    fun bookForIsbn(isbn: String): Int? {
        val editions = api.call("query(\$i:String!){ editions(where:{isbn_13:{_eq:\$i}}, limit:1) { book { id canonical_id } } }", JSONObject().put("i", isbn))
            .optJSONArray("editions")
        val b = editions?.optJSONObject(0)?.optJSONObject("book") ?: return null
        return if (b.isNull("canonical_id")) b.optInt("id").takeIf { it > 0 } else b.optInt("canonical_id")
    }

    /**
     * Best search hit: same title (or one of its other-language titles) and an author in common. Hardcover's
     * search sometimes ranks "Dune: House Harkonnen" above "Dune" when the author is in the query: without an
     * exact title the search is repeated with the title alone.
     */
    fun search(book: Book): Pair<Int, Int>? {
        val surnames = BookMatcher.surnames(book.author.takeUnless { it == "Autore sconosciuto" } ?: "")
        val first = bestHit(book, results(book.title + (surnames.maxByOrNull { it.length }?.let { " $it" } ?: "")))
        // Hardcover's own title is the same: sure. Otherwise (Italian titles are alternative titles, and some
        // alternative titles are wrong, like "Dune" on Dune: House Harkonnen) the title alone is searched too.
        if ((first != null && first.second >= 90) || surnames.isEmpty()) return first?.let { it.first to it.second }
        val again = bestHit(book, results(book.title))
        return listOfNotNull(first, again).maxWithOrNull(compareBy<Triple<Int, Int, Int>> { it.second }.thenBy { it.third })?.let { it.first to it.second }
    }

    private fun results(query: String): Any? = api.call("query(\$q:String!){ search(query:\$q, query_type:\"Book\", per_page:10, page:1) { results } }",
        JSONObject().put("q", query)).optJSONObject("search")?.opt("results")

    /** The same title, not only the same beginning: "Mistborn: The Final Empire" is "L'ultimo impero" through its Italian title. */
    fun exactTitle(book: Book, label: String): Boolean {
        val wanted = BookMatcher.whole(book.title)
        return listOf(label, label.substringAfter(": ", label), label.substringAfter(" - ", label), label.substringAfter(". ", label))
            .any { c -> BookMatcher.whole(c).any { it in wanted } }
    }

    fun pickHit(book: Book, results: Any?): Pair<Int, Int>? = bestHit(book, results)?.let { it.first to it.second }

    /** id, score, readers. Main title identical 90, an alternative title identical 88, a similar title 78 (to confirm). */
    fun bestHit(book: Book, results: Any?): Triple<Int, Int, Int>? {
        val parsed = when (results) { is JSONObject -> results; is String -> runCatching { JSONObject(results) }.getOrNull(); else -> null } ?: return null
        val hits = parsed.optJSONArray("hits") ?: return null
        val surnames = BookMatcher.surnames(book.author.takeUnless { it == "Autore sconosciuto" } ?: "")
        var best: Triple<Int, Int, Int>? = null    // id, score, readers
        for (i in 0 until hits.length()) {
            val doc = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
            val id = doc.optString("id").toIntOrNull() ?: continue
            // Titles in other scripts are left out: "Dune: Η Αναγέννηση…" would be just "Dune" once normalized.
            val titles = (listOf(doc.optString("title")) + strings(doc.optJSONArray("alternative_titles")).take(60)).filterNot { NOT_LATIN.containsMatchIn(it) }
            // Box sets by their title: Hardcover's "compilation" flag is also set on single novels (City of Bones).
            if (OMNIBUS.containsMatchIn(doc.optString("title"))) continue
            if (titles.none { it.isNotBlank() && SagaRecognizer.labelMatches(book, it) }) continue
            val main = titles.firstOrNull()?.let { it.isNotBlank() && exactTitle(book, it) } == true
            val exact = main || titles.any { it.isNotBlank() && exactTitle(book, it) }
            val authors = strings(doc.optJSONArray("author_names"))
            val score = when {
                surnames.isEmpty() -> if (exact) 70 else 60
                authors.any { a -> BookMatcher.surnames(a).any { it in surnames } } -> if (main) 90 else if (exact) 88 else 78
                else -> continue
            }
            val readers = doc.optInt("users_count")
            if (best == null || score > best.second || (score == best.second && readers > best.third)) best = Triple(id, score, readers)
        }
        return best
    }

    // ---- metadata and covers: Hardcover first in the whole app when connected ---------------------------

    /** Edition fields read by MetadataSources.hardcover (the same shape as before, plus "featured" on series). */
    const val EDITION = "title subtitle pages release_date isbn_13 isbn_10 image { url width height } publisher { name } language { code3 language }" +
        " book { id title slug description release_year image { url width height } contributions { contribution author { name } }" +
        " book_series(where:{compilation:{_eq:false}}) { position featured series { name } } }"

    /** The edition with this ISBN, or null. */
    fun editionByIsbn(isbn: String): JSONObject? = api.call("query(\$i:String!){ editions(where:{isbn_13:{_eq:\$i}}, limit:1) { $EDITION } }",
        JSONObject().put("i", isbn)).optJSONArray("editions")?.optJSONObject(0)

    /** For a book without ISBN: the Hardcover book found by title and author, its Italian edition when there is one. */
    fun editionBySearch(book: Book): JSONObject? {
        val (id, score) = search(book) ?: return null
        if (score < 78) return null
        val b = api.call("query(\$id:Int!){ books(where:{id:{_eq:\$id}}) { editions(where:{language:{code2:{_eq:\"it\"}}}, order_by:{users_count:desc_nulls_last}, limit:1) { $EDITION }" +
            " default_physical_edition { $EDITION } } }", JSONObject().put("id", id)).optJSONArray("books")?.optJSONObject(0) ?: return null
        return b.optJSONArray("editions")?.optJSONObject(0) ?: b.optJSONObject("default_physical_edition")
    }

    /** Cover addresses, most exact first: (url, same edition, Italian edition). */
    fun covers(book: Book): List<Triple<String, Boolean, Boolean>> {
        val out = ArrayList<Triple<String, Boolean, Boolean>>()
        fun add(e: JSONObject?, exact: Boolean) {
            val italian = e?.optJSONObject("language")?.optString("code3") == "ita"
            e?.optJSONObject("image")?.optString("url")?.takeIf { it.startsWith("http") }?.let { out += Triple(it, exact, italian) }
            e?.optJSONObject("book")?.optJSONObject("image")?.optString("url")?.takeIf { it.startsWith("http") }?.let { out += Triple(it, false, false) }
        }
        val isbn = BookMatcher.isbns(book.isbn).firstOrNull()
        if (isbn != null) add(editionByIsbn(isbn), true)
        if (out.isEmpty()) add(editionBySearch(book), false)
        return out.distinctBy { it.first }
    }

    // ---- book, series and volumes ------------------------------------------------------------------

    private const val MEMBERS = """book_series(where:{compilation:{_eq:false}, book:{canonical_id:{_is_null:true}, is_partial_book:{_neq:true}}}, order_by:{position:asc_nulls_last}, limit:150) {
        position book { id title release_year users_count contributions(limit:2) { author { name } }
          editions(where:{language:{code2:{_eq:"it"}}}, order_by:{users_count:desc_nulls_last}, limit:1) { title } } }"""

    val DETAILS = "query(\$id:Int!){ books(where:{id:{_eq:\$id}})" + """ { id title canonical_id
      book_series(where:{compilation:{_eq:false}}) { position featured series { id name $MEMBERS } } } }"""

    val SERIES_MEMBERS = "query(\$id:Int!){ series(where:{id:{_eq:\$id}})" + """ { id name $MEMBERS } }"""

    private val memberCache = ConcurrentHashMap<String, Pair<Long, List<WdMember>>>()

    fun details(bookId: Int, depth: Int = 0): WdWorkLinks? {
        val b = api.call(DETAILS, JSONObject().put("id", bookId)).optJSONArray("books")?.optJSONObject(0) ?: return null
        if (!b.isNull("canonical_id") && depth == 0) return details(b.optInt("canonical_id"), 1)
        val (links, members) = parseDetails(b)
        val now = System.currentTimeMillis()
        if (memberCache.size > 300) memberCache.clear()
        members.forEach { (qid, list) -> memberCache[qid] = now to list }
        return links
    }

    /** Volumes of a series ("hc:123"): from the last recognition when recent, otherwise one request. */
    fun members(seriesQid: String): List<WdMember> {
        memberCache[seriesQid]?.takeIf { System.currentTimeMillis() - it.first < 3_600_000L }?.let { return it.second }
        val id = seriesQid.removePrefix(SERIES).toInt()
        val s = api.call(SERIES_MEMBERS, JSONObject().put("id", id)).optJSONArray("series")?.optJSONObject(0)
            ?: throw java.io.IOException("Serie Hardcover $id non trovata")
        return cleanMembers(s.optJSONArray("book_series")).also { memberCache[seriesQid] = System.currentTimeMillis() to it }
    }

    /** One series of the book, before classification. */
    class RawSeries(val id: Int, val name: String, val position: String?, val featured: Boolean, val members: List<WdMember>) {
        /** Published volumes: announced books (Ghostbloods in The Mistborn Saga) are often missing from the bigger lists. */
        val ids: Set<String> = members.filter { m -> m.year?.toIntOrNull()?.let { it <= THIS_YEAR } ?: false }.map { it.qid }.toSet()
            .ifEmpty { members.map { it.qid }.toSet() }
    }

    private val THIS_YEAR = java.time.Year.now().value

    fun parseDetails(b: JSONObject): Pair<WdWorkLinks, Map<String, List<WdMember>>> {
        val raw = ArrayList<RawSeries>()
        val entries = b.optJSONArray("book_series") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val s = e.optJSONObject("series") ?: continue
            if (raw.any { it.id == s.optInt("id") }) continue
            raw += RawSeries(s.optInt("id"), s.optString("name"), ordinal(e), e.optBoolean("featured"), cleanMembers(s.optJSONArray("book_series")))
        }
        val workQid = WORK + b.optInt("id")
        val links = WdWorkLinks(workQid, b.optString("title"), classify(raw), emptyList(), emptyList())
        return links to raw.associate { SERIES + it.id to it.members }
    }

    /**
     * Hardcover lists every edition-like book of a series: translations entered as separate books, box sets,
     * omnibus. For each position the most read book stays; unnumbered extras only when people read them.
     */
    fun cleanMembers(list: JSONArray?): List<WdMember> {
        data class M(val member: WdMember, val readers: Int)
        val all = ArrayList<M>()
        for (i in 0 until (list?.length() ?: 0)) {
            val e = list!!.optJSONObject(i) ?: continue
            val b = e.optJSONObject("book") ?: continue
            val title = b.optString("title").trim(); if (title.isEmpty() || OMNIBUS.containsMatchIn(title)) continue
            val italian = b.optJSONArray("editions")?.optJSONObject(0)?.optString("title")?.let { italianTitle(it) }
            val authors = (0 until (b.optJSONArray("contributions")?.length() ?: 0)).mapNotNull {
                b.optJSONArray("contributions")?.optJSONObject(it)?.optJSONObject("author")?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            val year = if (b.isNull("release_year")) null else b.optInt("release_year").takeIf { it > 0 }?.toString()
            all += M(WdMember(WORK + b.optInt("id"), italian ?: title, italian, title, ordinal(e), null, year, authors, emptySet()), b.optInt("users_count"))
        }
        val top = all.maxOfOrNull { it.readers } ?: 0
        val numbered = all.filter { it.member.ordinal != null }.groupBy { it.member.ordinal }.values.map { group -> group.maxBy { it.readers } }
        val extras = all.filter { it.member.ordinal == null && it.readers >= maxOf(3, top / 100) }
        return (numbered + extras).map { it.member }.distinctBy { it.qid }
            .sortedWith(compareBy<WdMember> { it.sortKey }.thenBy { it.year ?: "9999" })
    }

    /**
     * Which series is the saga, which a part of it and which the universe, from the volumes they share:
     * - two lists with nearly the same books are the same saga in another order ("Dune Universe (Chronological)"
     *   and "(Publication Order)"): one stays, the plain one first;
     * - a list inside a bigger one is its sub-series (Original Trilogy → The Mistborn Saga);
     * - the top of a chain of three (Cosmere), a list called "universe"/"world", or a reading-order list that
     *   holds other sagas (The Shadowhunter Chronicles: Publication Order) is the universe.
     */
    fun classify(raw: List<RawSeries>): List<WdSeriesLink> {
        val kept = ArrayList<RawSeries>()
        for (s in raw.sortedWith(compareBy<RawSeries>({ ORDER_LIST.containsMatchIn(it.name) }, { !it.featured }, { it.id }))) {
            if (s.ids.isEmpty() || kept.any { overlap(it, s) >= 0.7 }) continue
            kept += s
        }
        fun inside(a: RawSeries, b: RawSeries) = a !== b && b.ids.size > a.ids.size && a.ids.count { it in b.ids } >= 0.8 * a.ids.size
        val parent = kept.associateWith { s -> kept.filter { inside(s, it) }.minByOrNull { it.ids.size } }
        val children = kept.associateWith { s -> kept.filter { parent[it] === s } }
        val universes = kept.filter { s ->
            parent[s] == null && (UNIVERSE_WORDS.containsMatchIn(s.name)
                || (children[s]!!.isNotEmpty() && ORDER_LIST.containsMatchIn(s.name))
                || children[s]!!.any { c -> children[c]!!.isNotEmpty() })
        }.toSet()
        fun universeOf(s: RawSeries): RawSeries? { var p = parent[s]; while (p != null) { if (p in universes) return p; p = parent[p] }; return null }
        val sagas = kept.filter { it !in universes }
            .sortedWith(compareBy<RawSeries>({ parent[it]?.takeIf { p -> p !in universes } != null }, { !it.featured }, { -it.ids.size }))
        return sagas.map { s ->
            val p = parent[s]?.takeIf { it !in universes }; val u = universeOf(s)
            WdSeriesLink(SERIES + s.id, displayName(s.name), s.position, SAGA_KIND, p?.let { SERIES + it.id }, p?.let { displayName(it.name) },
                u?.let { SERIES + it.id }, u?.let { displayName(it.name) })
        } + universes.map { u -> WdSeriesLink(SERIES + u.id, displayName(u.name), u.position, UNIVERSE_KIND, null, null, null, null) }
    }

    private fun overlap(a: RawSeries, b: RawSeries): Double {
        val shared = a.ids.count { it in b.ids }
        return shared.toDouble() / (a.ids.size + b.ids.size - shared).coerceAtLeast(1)
    }

    private val NOT_LATIN = Regex("""[\p{L}&&[^\p{IsLatin}]]""")
    private val ORDER_LIST = Regex("""(?i)\border\b|chronolog|publication|reading|cronologic|ordine di""")
    private val UNIVERSE_WORDS = Regex("""(?i)\buniverse\b|\buniverso\b|\bcosmere\b|\bworld\b|\bmultiverse\b|\bmondo\b|\p{L}{3,}verse\b""")
    private val OMNIBUS = Regex("""(?i)box(ed)?\s*set|boxset|complete collection|collection\b.*\bbooks?\b|\bbooks?\s*\d+\s*[-–]\s*\d+|\bomnibus\b|cofanetto|\(\d+ of \d+\)""")

    /** "Andrzej Sapkowski’s The Witcher (Publication Order)" → "The Witcher"; "…: Publication Order" goes too. */
    fun displayName(name: String): String {
        var n = name.replace(Regex("""\s*\([^)]*\)"""), "").replace(Regex("""(?i)\s*[:–-]\s*(publication|reading|chronological)\s+order\s*$"""), "")
        n = n.replace(Regex("""^\p{Lu}\p{L}+\s+\p{Lu}[\p{L}.-]+['’]s\s+(?=\p{Lu})"""), "")
        return n.trim().ifEmpty { name.trim() }
    }

    /** "Il pozzo dell'ascensione. Mistborn, Volume 2" → "Il pozzo dell'ascensione". */
    fun italianTitle(title: String): String? {
        val t = title.replace(Regex("""(?i)\s*[.:(,]\s*[^.:(]*\b(vol(ume)?\.?|libro|ciclo|serie|saga|tomo)\b.*$"""), "").trim()
        return t.takeIf { it.length >= 2 }
    }

    private fun ordinal(e: JSONObject): String? {
        if (e.isNull("position")) return null
        val d = e.optDouble("position", Double.NaN); if (d.isNaN()) return null
        return if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
    }

    private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optString(it).takeIf { s -> s.isNotBlank() } }
}
