package app.atlante.library.saga

import app.atlante.library.online.OnlineCatalog
import java.io.IOException
import java.net.URLEncoder
import org.json.JSONObject

/** A work (or a sub-series) listed as a member of a saga on Wikidata. */
data class WdMember(
    val qid: String, val label: String, val labelIt: String?, val labelEn: String?, val ordinal: String?,
    val openLibraryId: String?, val year: String?, val authors: List<String>, val kinds: Set<String>,
) {
    /** A member that is itself a series (Red Rising Trilogy inside Red Rising Saga). */
    val isSeries get() = kinds.any { it in Wikidata.SERIES_KINDS }
    val sortKey: Double get() = ordinal?.replace(',', '.')?.toDoubleOrNull() ?: Double.MAX_VALUE
}

/** A saga a work belongs to, with the work's position and the saga's parent/universe. */
data class WdSeriesLink(
    val seriesQid: String, val seriesLabel: String, val ordinal: String?, val kinds: Set<String>,
    val parentQid: String?, val parentLabel: String?, val universeQid: String?, val universeLabel: String?,
) {
    /** Cosmere is a universe, not a saga: never treat it as the book's saga. */
    val isUniverse get() = kinds.any { it in Wikidata.UNIVERSE_KINDS } && kinds.none { it == "Q1667921" }
}

data class WdWorkLinks(val workQid: String, val label: String, val series: List<WdSeriesLink>, val follows: List<String>, val followedBy: List<String>)

/**
 * Wikidata through its public endpoints (Query Service and wbsearchentities): small, targeted queries,
 * identified client, retries with backoff, responses cached by OnlineCatalog for 24 hours.
 * Properties: P179 part of the series, P1545 series ordinal, P50 author, P648 Open Library ID,
 * P577 publication date, P155/P156 follows/followed by, P1434 fictional universe, P31 instance of.
 */
object Wikidata {
    const val SPARQL = "https://query.wikidata.org/sparql?format=json&query="
    val SERIES_KINDS = setOf("Q1667921", "Q277759", "Q13593966", "Q7725310", "Q2235642", "Q614101")
    val UNIVERSE_KINDS = setOf("Q559618", "Q15711870", "Q196600", "Q17155032")
    private val QID = Regex("Q[0-9]+"); private val OLW = Regex("OL[0-9]+W")

    fun membersQuery(seriesQid: String): String {
        require(QID.matches(seriesQid))
        return """SELECT ?w ?itLabel ?enLabel ?label ?ord ?ol ?date ?kind ?author ?authorLabel WHERE {
  ?w p:P179 ?st. ?st ps:P179 wd:$seriesQid. OPTIONAL { ?st pq:P1545 ?ord. }
  OPTIONAL { ?w wdt:P648 ?ol. } OPTIONAL { ?w wdt:P577 ?date. } OPTIONAL { ?w wdt:P31 ?kind. } OPTIONAL { ?w wdt:P50 ?author. }
  OPTIONAL { ?w rdfs:label ?itLabel. FILTER(LANG(?itLabel) = "it") } OPTIONAL { ?w rdfs:label ?enLabel. FILTER(LANG(?enLabel) = "en") }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "it,en". ?w rdfs:label ?label. ?author rdfs:label ?authorLabel. }
} LIMIT 300"""
    }

    fun linksQuery(openLibraryWork: String): String {
        require(OLW.matches(openLibraryWork))
        return """SELECT ?w ?wLabel ?s ?sLabel ?ord ?skind ?parent ?parentLabel ?uni ?uniLabel ?prev ?next WHERE {
  ?w wdt:P648 "$openLibraryWork".
  OPTIONAL { ?w p:P179 ?st. ?st ps:P179 ?s. OPTIONAL { ?st pq:P1545 ?ord. } OPTIONAL { ?s wdt:P31 ?skind. } OPTIONAL { ?s wdt:P179 ?parent. } OPTIONAL { ?s wdt:P1434 ?uni. } }
  OPTIONAL { ?w wdt:P155 ?prev. } OPTIONAL { ?w wdt:P156 ?next. }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "it,en". } } LIMIT 100"""
    }

    /** Same as linksQuery but starting from a Wikidata item (found by label search). */
    fun linksByQidQuery(workQid: String): String {
        require(QID.matches(workQid))
        return linksQuery("OL0W").replace("?w wdt:P648 \"OL0W\".", "BIND(wd:$workQid AS ?w)")
    }

    private fun id(v: JSONObject?, key: String): String? = v?.optJSONObject(key)?.optString("value")?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    private fun text(v: JSONObject?, key: String): String? = v?.optJSONObject(key)?.optString("value")?.takeIf { it.isNotEmpty() }

    /** Members of a saga, one per work (the query returns a row per kind/author/date combination). */
    fun parseMembers(json: String): List<WdMember> {
        val rows = JSONObject(json).optJSONObject("results")?.optJSONArray("bindings") ?: return emptyList()
        val byQid = LinkedHashMap<String, WdMember>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val q = id(r, "w") ?: continue
            if (!QID.matches(q)) continue
            val known = byQid[q]
            val kinds = (known?.kinds ?: emptySet()) + listOfNotNull(id(r, "kind"))
            val authors = ((known?.authors ?: emptyList()) + listOfNotNull(text(r, "authorLabel")?.takeUnless { QID.matches(it) })).distinct()
            val ol = known?.openLibraryId ?: text(r, "ol")?.takeIf { OLW.matches(it) }
            val ordinal = known?.ordinal ?: text(r, "ord")
            val year = listOfNotNull(known?.year, text(r, "date")?.take(4)).minOrNull()
            byQid[q] = WdMember(q, text(r, "label") ?: known?.label ?: q, known?.labelIt ?: text(r, "itLabel"), known?.labelEn ?: text(r, "enLabel"),
                ordinal, ol, year, authors, kinds)
        }
        return byQid.values.sortedWith(compareBy<WdMember> { it.sortKey }.thenBy { it.year ?: "9999" })
    }

    fun parseLinks(json: String): WdWorkLinks? {
        val rows = JSONObject(json).optJSONObject("results")?.optJSONArray("bindings") ?: return null
        if (rows.length() == 0) return null
        var work: String? = null; var label = ""
        val series = LinkedHashMap<String, WdSeriesLink>(); val prev = linkedSetOf<String>(); val next = linkedSetOf<String>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            work = work ?: id(r, "w"); if (label.isEmpty()) label = text(r, "wLabel") ?: ""
            id(r, "prev")?.let { prev += it }; id(r, "next")?.let { next += it }
            val s = id(r, "s") ?: continue
            val known = series[s]
            series[s] = WdSeriesLink(s, text(r, "sLabel") ?: s, known?.ordinal ?: text(r, "ord"), (known?.kinds ?: emptySet()) + listOfNotNull(id(r, "skind")),
                known?.parentQid ?: id(r, "parent"), known?.parentLabel ?: text(r, "parentLabel"), known?.universeQid ?: id(r, "uni"), known?.universeLabel ?: text(r, "uniLabel"))
        }
        return work?.let { WdWorkLinks(it, label, series.values.toList(), prev.toList(), next.toList()) }
    }

    /** Item search by label in Italian and English (used only when no Open Library ID links the work). */
    fun searchUrl(text: String, language: String): String =
        "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&type=item&limit=10&language=$language&uselang=$language&search=" + URLEncoder.encode(text, "UTF-8")

    fun parseSearch(json: String): List<Pair<String, String>> {
        val a = JSONObject(json).optJSONArray("search") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("id") to (it.optString("label") + " — " + it.optString("description")) }
    }

    // ---- network ---------------------------------------------------------------------------------

    /** GET with the shared rate limiter and disk cache; retries twice on "busy" answers with growing waits. */
    fun get(url: String): String {
        var wait = 2000L
        repeat(3) { attempt ->
            try { return String(OnlineCatalog.request(url, 4 * 1024 * 1024), Charsets.UTF_8) }
            catch (e: IOException) {
                val busy = e.message?.let { it.contains("429") || it.contains("503") || it.contains("occupato") } ?: false
                if (!busy || attempt == 2) throw e
                Thread.sleep(wait); wait *= 2
            }
        }
        throw IOException("Wikidata non disponibile")
    }

    fun members(seriesQid: String) = parseMembers(get(SPARQL + URLEncoder.encode(membersQuery(seriesQid), "UTF-8")))
    fun linksForOpenLibrary(work: String) = parseLinks(get(SPARQL + URLEncoder.encode(linksQuery(work), "UTF-8")))
    fun linksForItem(qid: String) = parseLinks(get(SPARQL + URLEncoder.encode(linksByQidQuery(qid), "UTF-8")))
    fun search(text: String, language: String) = parseSearch(get(searchUrl(text, language)))

    /**
     * Full-text search (labels, aliases and descriptions): finds "Mistborn: L'Ultimo Impero" from
     * "L'ultimo impero Sanderson". Returns (qid, "label it|label en — description").
     */
    fun fullText(text: String): List<Pair<String, String>> {
        val hits = JSONObject(get("https://www.wikidata.org/w/api.php?action=query&list=search&format=json&srlimit=8&srsearch=" + URLEncoder.encode(text, "UTF-8")))
            .optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        val ids = (0 until hits.length()).mapNotNull { hits.optJSONObject(it)?.optString("title")?.takeIf { t -> QID.matches(t) } }
        if (ids.isEmpty()) return emptyList()
        val entities = JSONObject(get("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=labels%7Cdescriptions&languages=it%7Cen&ids=" + ids.joinToString("%7C")))
            .optJSONObject("entities") ?: return emptyList()
        return parseEntities(ids, entities)
    }

    fun parseEntities(ids: List<String>, entities: JSONObject): List<Pair<String, String>> = ids.mapNotNull { id ->
        val e = entities.optJSONObject(id) ?: return@mapNotNull null
        val labels = listOfNotNull(e.optJSONObject("labels")?.optJSONObject("it")?.optString("value"), e.optJSONObject("labels")?.optJSONObject("en")?.optString("value")).distinct()
        val d = e.optJSONObject("descriptions")
        val description = listOfNotNull(d?.optJSONObject("it")?.optString("value"), d?.optJSONObject("en")?.optString("value")).joinToString(" / ")
        if (labels.isEmpty()) null else id to (labels.joinToString("|") + " — " + description)
    }
}
