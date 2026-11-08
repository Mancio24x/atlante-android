package app.atlante.library.catalog

import app.atlante.library.model.Book
import app.atlante.library.model.BookMatcher
import app.atlante.library.model.SearchIndex
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one place where the app decides whether two records are the same work, and how two records become one.
 * Used by Hardcover sync, imports, "Ripara duplicati", "Ripara saghe", "Completa metadati".
 *
 * Identity, strongest first (never by title alone):
 * - VERIFIED: same file, same ISBN, same Hardcover book, the same work of the offline catalog (reached by an
 *   edition's ISBN or by a whole title with a common author surname), the same work of the saga graph.
 * - PROBABLE: same title (one of its whole forms) and the same author, without an identifier in common.
 * - never: authors with no surname in common, different numbers of the same saga, "Sono libri diversi".
 * Only VERIFIED pairs without conflicts are merged by themselves; the rest is proposed to you.
 */
object BibliographicEngine {
    enum class Confidence(val label: String) { VERIFIED("verificata"), PROBABLE("probabile"), UNCERTAIN("incerta") }

    /** What is known of a book's identity, gathered once per analysis (catalog work through LibraryIndex). */
    data class Identity(val bookId: String, val catalogWork: Long?, val hardcoverBook: Int?, val graphWork: Long?)

    data class Pair(val keep: Book, val other: Book, val confidence: Confidence, val reasons: List<String>, val conflicts: List<String>) {
        val automatic get() = confidence == Confidence.VERIFIED && conflicts.isEmpty()
    }

    fun hardcoverBook(b: Book): Int? = b.journal.optJSONObject("sync")?.optJSONObject("hardcover")?.optInt("book")?.takeIf { it > 0 }

    /** You said these two are different books (on either record): never proposed or merged. */
    fun different(a: Book, b: Book): Boolean {
        // A book still being looked up (Scopri, a search) has no id yet: nothing was decided about it.
        fun has(x: Book, id: String?): Boolean { if (id.isNullOrEmpty()) return false; val l = x.meta.optJSONArray("notSameAs") ?: return false; return (0 until l.length()).any { l.optString(it) == id } }
        return has(a, b.id) || has(b, a.id)
    }

    fun rememberDifferent(a: Book, b: Book) {
        val l = a.meta.optJSONArray("notSameAs") ?: JSONArray()
        if ((0 until l.length()).none { l.optString(it) == b.id }) l.put(b.id)
        a.meta.put("notSameAs", l)
    }

    private fun authorsMatch(a: Book, b: Book): Boolean? {
        val sa = BookMatcher.surnames(a.author); val sb = BookMatcher.surnames(b.author)
        if (sa.isEmpty() || sb.isEmpty()) return null
        return sa.any { it in sb }
    }

    /** The same work, and why; null when they are not (or not enough to say so). */
    fun compare(a: Book, b: Book, ia: Identity?, ib: Identity?): kotlin.Pair<Confidence, List<String>>? {
        if ((a.id != null && a.id == b.id) || different(a, b)) return null
        val score = BookMatcher.score(a, b)
        if (score == BookMatcher.DIFFERENT && a.number > 0 && b.number > 0) return null       // two volumes of one saga
        val authors = authorsMatch(a, b)
        if (authors == false) return null                                                   // same title, other author: other work
        val reasons = ArrayList<String>()
        if (score >= BookMatcher.SAME_ISBN) reasons += "stesso ISBN o stesso file"
        if (ia?.hardcoverBook != null && ia.hardcoverBook == ib?.hardcoverBook) reasons += "stesso libro su Hardcover"
        if (ia?.catalogWork != null && ia.catalogWork == ib?.catalogWork) reasons += "stessa opera nel catalogo offline (Wikidata Q${ia.catalogWork})"
        if (ia?.graphWork != null && ia.graphWork == ib?.graphWork) reasons += "stessa opera riconosciuta dal motore delle saghe"
        // The same title word for word (case, accents and punctuation aside) and the same author: the same book
        // added twice (a search, a sync). Saga volumes with other numbers were already left out above.
        if (score >= BookMatcher.SAME_TITLE_AND_AUTHOR && authors == true && SearchIndex.normalize(a.title) == SearchIndex.normalize(b.title)
            && (a.number <= 0 || b.number <= 0 || a.number == b.number)) reasons += "stesso titolo e stesso autore"
        if (reasons.isNotEmpty()) return Confidence.VERIFIED to reasons
        if (score >= BookMatcher.SAME_TITLE_AND_AUTHOR && authors == true) return Confidence.PROBABLE to listOf("stesso titolo e stesso autore, senza identificativi in comune")
        return null
    }

    /** Personal data a record holds: the one with more of it is kept (its id, its file, its cover stay). */
    /** The Italian edition: language, or an Italian ISBN (978-88, 979-12). */
    fun italian(b: Book): Boolean = b.language.trim().lowercase() in setOf("it", "ita", "italiano", "italian") ||
        BookMatcher.isbns(b.isbn).any { it.startsWith("97888") || it.startsWith("97912") || (it.length == 10 && it.startsWith("88")) }

    /**
     * Which record stays: the Italian edition first (its title stays in the library; the other's file and data come
     * into it), then the one with a file, then the one with more personal data, then the older one.
     */
    private fun weight(b: Book): Int {
        var w = if (italian(b)) 1200 else 0
        if (b.format != "CATALOGO") w += 1000
        w += b.journal.optJSONArray("sessions")?.length()?.times(10) ?: 0
        if (b.notes.isNotBlank()) w += 50
        w += (b.journal.optJSONArray("notes")?.length() ?: 0) * 20
        if (ReadingJournal.rating(b) > 0) w += 30
        if (b.journal.optString("review").isNotBlank()) w += 40
        if (b.status != Book.TO_READ) w += 20
        if (b.meta.optString("coverSource") in setOf("gallery", "online")) w += 15
        return w
    }

    fun canonical(a: Book, b: Book): kotlin.Pair<Book, Book> {
        val wa = weight(a); val wb = weight(b)
        return if (wa > wb || (wa == wb && a.addedAt <= b.addedAt)) a to b else b to a
    }

    /** Differences that only you can settle; empty when one side is simply missing what the other has. */
    fun conflicts(keep: Book, other: Book): List<String> {
        val out = ArrayList<String>()
        val r1 = ReadingJournal.rating(keep); val r2 = ReadingJournal.rating(other)
        if (r1 > 0 && r2 > 0 && r1 != r2) out += "voto: $r1 / $r2"
        val v1 = keep.journal.optString("review"); val v2 = other.journal.optString("review")
        if (v1.isNotBlank() && v2.isNotBlank() && v1 != v2) out += "due recensioni diverse"
        if (keep.format != "CATALOGO" && other.format != "CATALOGO" && keep.meta.optString("fileSha256") != other.meta.optString("fileSha256")) out += "due file (${keep.format} e ${other.format})"
        if (keep.finished.isNotEmpty() && other.finished.isNotEmpty() && keep.finished != other.finished) out += "letto il ${keep.finished} e il ${other.finished}"
        if (keep.series.isNotEmpty() && other.series.isNotEmpty() && keep.number > 0 && other.number > 0 && keep.number != other.number) out += "numero nella saga diverso"
        return out
    }

    /**
     * Every pair of records of the same work in [books] (a group of three gives two pairs with the same kept record).
     * [identities]: what LibraryIndex / Hardcover / the saga graph know of each book.
     */
    fun duplicates(books: List<Book>, identities: Map<String, Identity>, stop: () -> Boolean = { false }): List<Pair> {
        // Candidate pairs only through shared keys, never all against all.
        val buckets = HashMap<String, MutableList<Book>>()
        fun add(key: String, b: Book) = buckets.getOrPut(key) { ArrayList() }.add(b)
        for (b in books) {
            val id = identities[b.id]
            BookMatcher.isbns(b.isbn).forEach { add("isbn:$it", b) }
            b.meta.optString("fileSha256").takeIf { it.length == 64 }?.let { add("file:$it", b) }
            id?.catalogWork?.let { add("cat:$it", b) }
            id?.hardcoverBook?.let { add("hc:$it", b) }
            id?.graphWork?.let { add("graph:$it", b) }
            for (s in BookMatcher.surnames(b.author)) add("ta:" + SearchIndex.normalize(b.title).take(24) + "|" + s, b)
            b.meta.optString("originalTitle").takeIf { it.isNotBlank() }?.let { t -> for (s in BookMatcher.surnames(b.author)) add("ta:" + SearchIndex.normalize(t).take(24) + "|" + s, b) }
        }
        // Union of the records of one work, then one pair per extra record against the kept one.
        val parent = HashMap<String, String>()
        fun find(x: String): String { var r = x; while (parent[r] != null && parent[r] != r) r = parent[r]!!; return r }
        val why = HashMap<kotlin.Pair<String, String>, kotlin.Pair<Confidence, List<String>>>()
        val byId = books.associateBy { it.id }
        for (group in buckets.values) {
            if (stop()) throw kotlin.coroutines.cancellation.CancellationException("Annullata")   // «Annulla» stops it here too
            if (group.size < 2 || group.size > 40) continue          // 40+ books on one key is a generic title, not one work
            for (i in group.indices) for (j in i + 1 until group.size) {
                val a = group[i]; val b = group[j]
                val key = if (a.id < b.id) a.id to b.id else b.id to a.id
                if (key in why) continue
                val c = compare(a, b, identities[a.id], identities[b.id]) ?: continue
                why[key] = c
                parent.putIfAbsent(a.id, a.id); parent.putIfAbsent(b.id, b.id)
                val ra = find(a.id); val rb = find(b.id); if (ra != rb) parent[rb] = ra
            }
        }
        val root = parent.keys.associateWith { find(it) }
        val groups = parent.keys.groupBy { root[it]!! }.values.filter { it.size > 1 }
        // The weakest reason of each group, found once (it was looked up again for every record: slow with many doubles).
        val weakest = HashMap<String, kotlin.Pair<Confidence, List<String>>>()
        for ((k, v) in why) { val r = root[k.first] ?: continue; val w = weakest[r]; if (w == null || v.first.ordinal > w.first.ordinal) weakest[r] = v }
        val out = ArrayList<Pair>()
        for (g in groups) {
            if (stop()) throw kotlin.coroutines.cancellation.CancellationException("Annullata")
            val members = g.mapNotNull { byId[it] }
            // Kept: the record with the most personal data.
            val keep = members.reduce { x, y -> canonical(x, y).first }
            for (o in members) {
                if (o.id == keep.id) continue
                val k = if (keep.id < o.id) keep.id to o.id else o.id to keep.id
                // The pair's own reason, else the weakest reason along the group (a group is as sure as its weakest link).
                val c = why[k] ?: weakest[root[o.id]] ?: continue
                if (different(keep, o)) continue
                out += Pair(keep, o, c.first, c.second, conflicts(keep, o))
            }
        }
        return out.sortedWith(compareBy({ it.keep.title.lowercase() }, { it.other.title.lowercase() }))
    }

    /**
     * For imports (an EPUB, a sheet found online): the record of the library that is already this work, or null.
     * The candidate is matched to the offline catalog and compared with the works of the library; otherwise the
     * usual identity rules (same file, ISBN, title and author) decide.
     */
    @JvmStatic fun sameWorkIn(library: List<Book>, candidate: Book, catalog: CatalogDao?): Book? {
        val work = catalog?.let { c -> runCatching { kotlinx.coroutines.runBlocking { LibraryIndex.match(c, candidate) } }.getOrNull() }
        if (work != null) {
            val owners = LibraryIndex.workOf.filterValues { it == work }.keys
            library.firstOrNull { it.id in owners && !different(it, candidate) && authorsMatch(it, candidate) != false }?.let { return it }
        }
        val best = BookMatcher.best(candidate, library) { it } ?: return null
        return best.takeIf { authorsMatch(it, candidate) != false && !different(it, candidate) }
    }

    // ---- merge -------------------------------------------------------------------------------------------------

    /** Your choices on a conflict: which side wins for rating and review (default: the kept record). */
    data class Choices(val ratingFromOther: Boolean = false, val reviewFromOther: Boolean = false)

    private fun rank(status: String) = when (status) { Book.READ -> 3; Book.READING, Book.PAUSED -> 2; else -> 1 }

    /**
     * The kept record with everything of the other one: sessions, finished readings, notes, quotes, collections,
     * favorite, rating and review (yours to choose on conflict), the furthest progress, the earliest start, the
     * other ISBNs and editions, identifiers, sync links. Nothing is lost; duplicates (the same session twice) once.
     */
    fun merged(keep: Book, other: Book, choices: Choices = Choices()): Book {
        val k = Book.from(keep.json())
        // Reading state: the furthest one, with its progress.
        if (rank(other.status) > rank(k.status)) { k.status = other.status; if (other.currentPage > k.currentPage) k.currentPage = other.currentPage }
        if (other.currentPage > k.currentPage && other.status == k.status) k.currentPage = other.currentPage
        if (other.percent > k.percent) k.percent = other.percent
        if (k.totalPages == 0) k.totalPages = other.totalPages
        if (k.started.isEmpty() || (other.started.isNotEmpty() && other.started < k.started)) k.started = other.started.ifEmpty { k.started }
        if (k.finished.isEmpty()) k.finished = other.finished
        for (f in listOf("isbn", "publisher", "language", "year", "description", "subtitle", "genre", "edition", "medium", "location")) fill(k, other, f)
        if (k.series.isEmpty()) { k.series = other.series; k.number = other.number }
        if (k.tags.isBlank()) k.tags = other.tags
        else if (other.tags.isNotBlank()) k.tags = (k.tags.split(',') + other.tags.split(',')).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
        if (other.notes.isNotBlank() && !k.notes.contains(other.notes)) k.notes = listOf(k.notes, other.notes).filter { it.isNotBlank() }.joinToString("\n\n")
        if (k.rating == 0 || choices.ratingFromOther) k.rating = if (other.rating > 0) other.rating else k.rating
        if (k.chapters.isEmpty()) k.chapters.addAll(other.chapters)
        mergeJournal(k, other, choices)
        mergeMeta(k, other)
        return k
    }

    private fun fill(k: Book, o: Book, field: String) {
        val f = Book::class.java.getField(field)
        if ((f.get(k) as String).isEmpty()) f.set(k, f.get(o) as String)
    }

    private fun union(a: JSONArray?, b: JSONArray?, key: (Any?) -> String): JSONArray {
        val out = JSONArray(); val seen = HashSet<String>()
        for (arr in listOf(a, b)) for (i in 0 until (arr?.length() ?: 0)) { val v = arr!!.opt(i); if (seen.add(key(v))) out.put(v) }
        return out
    }

    private fun sessionKey(v: Any?): String {
        val s = v as? JSONObject ?: return v.toString()
        s.optString("timerId").takeIf { it.isNotEmpty() }?.let { return "t:$it" }
        return listOf(s.optString("date"), s.optLong("at"), s.optInt("from"), s.optInt("to"), s.optLong("seconds")).joinToString("|")
    }

    private fun mergeJournal(k: Book, o: Book, choices: Choices) {
        val kj = k.journal; val oj = o.journal
        kj.put("sessions", union(kj.optJSONArray("sessions"), oj.optJSONArray("sessions"), ::sessionKey))
        // Finished readings: the other record's completion becomes an attempt, the same date once.
        val attempts = union(kj.optJSONArray("attempts"), oj.optJSONArray("attempts")) { (it as? JSONObject)?.optString("finished") ?: it.toString() }
        if (o.finished.isNotEmpty() && o.finished != k.finished && (0 until attempts.length()).none { attempts.optJSONObject(it)?.optString("finished") == o.finished })
            attempts.put(JSONObject().put("started", o.started).put("finished", o.finished))
        if (attempts.length() > 0) kj.put("attempts", attempts)
        // Notes, highlights, quotes: lists are joined (each entry once), plain texts are joined, a text next to a
        // list becomes one more entry of it. Never dropped.
        for (key in listOf("notes", "highlights", "quotes")) {
            val mine = kj.opt(key); val theirs = oj.opt(key) ?: continue
            kj.put(key, when {
                mine == null -> theirs
                mine is JSONArray && theirs is JSONArray -> union(mine, theirs) { it.toString() }
                mine is String && theirs is String -> if (mine == theirs || mine.contains(theirs)) mine else listOf(mine, theirs).filter { it.isNotBlank() }.joinToString("\n\n")
                mine is JSONArray -> union(mine, JSONArray().put(JSONObject().put("body", theirs.toString()))) { it.toString() }
                else -> union(JSONArray().put(JSONObject().put("body", mine.toString())), theirs as? JSONArray ?: JSONArray().put(theirs)) { it.toString() }
            })
        }
        val collections = union(kj.optJSONArray("collections"), oj.optJSONArray("collections")) { it.toString() }
        if (collections.length() > 0) kj.put("collections", collections)
        if (oj.optBoolean("favorite")) kj.put("favorite", true)
        if (!kj.has("rating") || kj.optDouble("rating") <= 0 || choices.ratingFromOther) if (oj.optDouble("rating", 0.0) > 0) kj.put("rating", oj.optDouble("rating"))
        if (kj.optString("review").isBlank() || choices.reviewFromOther) if (oj.optString("review").isNotBlank()) kj.put("review", oj.optString("review"))
        if (oj.optInt("attempt", 1) > kj.optInt("attempt", 1)) kj.put("attempt", oj.optInt("attempt"))
        // Sync links of every service (Hardcover, Calibre, KOReader…): the kept record's own ones win.
        val sync = kj.optJSONObject("sync") ?: JSONObject()
        oj.optJSONObject("sync")?.let { theirs -> theirs.keys().forEach { s ->
            val mine = sync.optJSONObject(s)
            if (mine == null || mine.optInt("id") == 0) sync.put(s, theirs.get(s))
        } }
        if (sync.length() > 0) kj.put("sync", sync)
        oj.keys().forEach { key -> if (!kj.has(key)) kj.put(key, oj.get(key)) }
    }

    private fun mergeMeta(k: Book, o: Book) {
        val km = k.meta; val om = o.meta
        // Identifiers of both (ISBNs of the other edition too).
        val ids = km.optJSONObject("ids") ?: JSONObject()
        om.optJSONObject("ids")?.let { theirs -> theirs.keys().forEach { key -> if (ids.optString(key).isEmpty()) ids.put(key, theirs.get(key)) } }
        if (ids.length() > 0) km.put("ids", ids)
        // The other record as one edition of this work: consultable in the sheet.
        val editions = km.optJSONArray("editions") ?: JSONArray()
        val edition = JSONObject().put("title", o.title).put("isbn", o.isbn).put("publisher", o.publisher).put("year", o.year)
            .put("language", o.language).put("format", o.format).put("from", o.id)
        if ((0 until editions.length()).none { editions.optJSONObject(it)?.optString("from") == o.id } && (o.isbn != k.isbn || o.title != k.title || o.format != k.format)) editions.put(edition)
        om.optJSONArray("editions")?.let { for (i in 0 until it.length()) editions.put(it.get(i)) }
        if (editions.length() > 0) km.put("editions", editions)
        for (key in listOf("originalTitle", "translators", "firstPublished", "subjects")) if (km.optString(key).isEmpty() && om.optString(key).isNotEmpty()) km.put(key, om.get(key))
        if (km.optString("originalTitle").isEmpty() && SearchIndex.normalize(o.title) != SearchIndex.normalize(k.title) && o.language.isNotEmpty() && o.language != k.language) km.put("originalTitle", o.title)
        val refused = union(km.optJSONArray("notSameAs"), om.optJSONArray("notSameAs")) { it.toString() }
        if (refused.length() > 0) km.put("notSameAs", refused)
        om.keys().forEach { key -> if (!km.has(key)) km.put(key, om.get(key)) }
    }

    private object ReadingJournal { fun rating(b: Book) = app.atlante.library.model.ReadingJournal.rating(b) }
}
