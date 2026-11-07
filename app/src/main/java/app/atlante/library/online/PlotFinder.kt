package app.atlante.library.online

import app.atlante.library.catalog.CatalogDao
import app.atlante.library.catalog.LibraryIndex
import app.atlante.library.model.Book
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The plot of a book found online, for its sheet. The Italian catalog (SBN) never has one and Open Library's
 * are mostly English, so, Italian first:
 * 1. the Italian Wikipedia article of the work (reached through its Wikidata id in the offline catalog);
 * 2. a description from a record of the same edition (same ISBN), Italian first.
 * A plot found only by title and author may belong to another work or edition: it is never shown. Nothing is
 * invented: no plot found, the sheet says "Trama non disponibile". The source is kept with the book.
 */
object PlotFinder {
    @JvmStatic fun fill(book: Book, catalog: CatalogDao?) {
        if (book.description.isNotBlank()) { book.description = clean(book.description); return }
        val qid = catalog?.let { c -> runCatching { runBlocking { LibraryIndex.match(c, book) } }.getOrNull() }
        qid?.let { runCatching { wikipedia(it) }.getOrNull() }?.let { set(book, it, "Wikipedia in italiano"); return }
        if (!sameEditionPossible(book)) return
        val result = runCatching { MetadataEngine.lookup(book, MetadataEngine.Settings()) { } }.getOrNull() ?: return
        val best = result.editionDescriptions.sortedWith(compareBy({ if (it.language == "ita") 0 else 1 }, { -it.text.length.coerceAtMost(1500) })).firstOrNull() ?: return
        set(book, best.text, best.source)
    }

    /** Only a book with an ISBN can be tied to its edition's records; without one, a plot could be another work's. */
    @JvmStatic fun sameEditionPossible(book: Book) = app.atlante.library.model.BookMatcher.isbns(book.isbn).isNotEmpty()

    /** Plain text: no HTML tags, entities decoded, blank lines collapsed. */
    @JvmStatic fun clean(text: String): String {
        var t = text.replace(Regex("(?i)</p>"), "\n\n").replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "")
        t = Regex("&#(x?)([0-9a-fA-F]+);").replace(t) { m -> runCatching { String(Character.toChars(m.groupValues[2].toInt(if (m.groupValues[1].isEmpty()) 10 else 16))) }.getOrDefault("") }
        t = t.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        return t.replace(Regex("[ \\t]+"), " ").replace(Regex("\\s*\n\\s*\n\\s*"), "\n\n").trim()
    }

    private fun set(book: Book, text: String, source: String) {
        book.description = clean(text)
        runCatching { book.meta.put("descriptionSource", source) }
    }

    /** The introduction of the work's article on it.wikipedia.org (Wikidata sitelink), or null. */
    fun wikipedia(qid: Long): String? {
        val entity = JSONObject(String(OnlineCatalog.request("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=Q$qid&props=sitelinks&sitefilter=itwiki&format=json", 256 * 1024)))
        val title = entity.optJSONObject("entities")?.optJSONObject("Q$qid")?.optJSONObject("sitelinks")?.optJSONObject("itwiki")?.optString("title")?.takeIf { it.isNotBlank() } ?: return null
        val summary = JSONObject(String(OnlineCatalog.request("https://it.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20"), 256 * 1024)))
        return summary.optString("extract").takeIf { it.length > 80 }
    }
}
