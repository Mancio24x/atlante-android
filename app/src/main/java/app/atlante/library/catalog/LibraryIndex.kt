package app.atlante.library.catalog

import android.content.Context
import app.atlante.library.model.Book
import app.atlante.library.model.BookMatcher
import app.atlante.library.model.SearchIndex
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.saga.db.LibraryFts
import app.atlante.library.saga.db.SagaDao
import kotlinx.coroutines.*
import java.security.MessageDigest

/**
 * The FTS4 index of the personal library (table library_fts in the bibliographic database).
 * - Updated by itself: every save of library.json schedules [sync] (debounced); only books added, changed or
 *   removed are written, found through a hash of their searchable text (no rebuild at every start).
 * - Each book is linked, when it is the same work, to the offline catalog: its titles there (original, Italian,
 *   editions) become searchable titles of the book, and the catalog does not list that work a second time.
 * - Only searchable text is stored; the books stay in library.json, untouched.
 */
object LibraryIndex {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    /** Called by LibraryStore after every save. */
    @JvmStatic fun libraryChanged(context: Context) {
        val app = context.applicationContext ?: return
        synchronized(this) { pending?.cancel(); pending = scope.launch { delay(1500); runCatching { syncNow(app) } } }
    }

    /** At start and on return to the app (off the main thread): brings the index up to date; true when titles changed. */
    @JvmStatic fun syncBlocking(context: Context): Boolean = runCatching { runBlocking { syncNow(context) }; true }.getOrDefault(false)

    /** Book id → its catalog work (for imports: the same work is never added twice). */
    @Volatile var workOf: Map<String, Long> = emptyMap(); private set

    /** Book id → titles of its catalog work (for the in-memory ranking of the library). */
    @Volatile var catalogTitles: Map<String, List<String>> = emptyMap(); private set

    suspend fun syncNow(context: Context): Report {
        val store = app.atlante.library.storage.LibraryStore.shared(context)
        val catalog = runCatching { CatalogDatabase.get(context).catalog() }.getOrNull()
        val report = sync(AtlanteDatabase.get(context).saga(), catalog, store.snapshot())
        app.atlante.library.saga.SearchAliases.load(context)
        return report
    }

    data class Report(val added: Int, val updated: Int, val removed: Int, val unchanged: Int)

    @JvmStatic fun rowid(bookId: String): Long =
        bookId.take(15).toLongOrNull(16) ?: (bookId.hashCode().toLong() and 0x7fffffffL)

    /** The searchable text of a book (folded), before its catalog titles. */
    fun textOf(b: Book): Triple<String, String, String> {
        val titles = listOf(b.title, b.subtitle, b.meta.optString("originalTitle")).filter { it.isNotBlank() }.map(SearchIndex::normalize).distinct()
        val authors = SearchIndex.authors(b).map(SearchIndex::normalize)
        return Triple(titles.joinToString(" | "), authors.joinToString(" | "), SearchIndex.normalize(b.series))
    }

    private fun hash(vararg parts: String) = MessageDigest.getInstance("SHA-1").digest(parts.joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }.take(20)

    suspend fun sync(dao: SagaDao, catalog: CatalogDao?, books: List<Book>): Report {
        val state = dao.libraryIndexState().associateBy { it.bookId }
        val rows = ArrayList<LibraryFts>(); var added = 0; var updated = 0
        val titles = HashMap<String, List<String>>()
        for (b in books) {
            val (t, a, s) = textOf(b)
            val h = hash(t, a, s, if (catalog == null) "-" else "c")
            val old = state[b.id]
            var work = old?.catalogWork.orEmpty()
            if (old == null || old.hash != h) {
                work = catalog?.let { runCatching { match(it, b) }.getOrNull() }?.toString().orEmpty()
                val extra = work.toLongOrNull()?.let { catalog?.titlesOf(it) }.orEmpty()
                rows += LibraryFts(rowid(b.id), b.id, listOf(t, extra).filter { it.isNotBlank() }.joinToString(" | "), a, s, h, work)
                if (old == null) added++ else updated++
            }
            if (work.isNotEmpty()) catalog?.titlesOf(work.toLong())?.let { titles[b.id] = it.split(" | ") }
        }
        val ids = books.map { it.id }.toSet()
        val gone = state.values.filter { it.bookId !in ids }.map { it.rowid }
        if (rows.isNotEmpty()) rows.chunked(500).forEach { dao.putLibraryIndex(it) }
        if (gone.isNotEmpty()) gone.chunked(500).forEach { dao.deleteLibraryIndex(it) }
        catalogTitles = titles
        workOf = dao.catalogLinks().mapNotNull { l -> l.catalogWork.toLongOrNull()?.let { l.bookId to it } }.toMap()
        return Report(added, updated, gone.size, books.size - added - updated)
    }

    /** FTS4 query from what you type: every word as a prefix ("ultim* imper*"); null when there is nothing to search. */
    @JvmStatic fun ftsQuery(text: String): String? {
        val words = SearchIndex.queryWords(text).map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }.filter { it.isNotEmpty() }
        return if (words.isEmpty()) null else words.joinToString(" ") { "$it*" }
    }

    /**
     * The catalog work that is this book: one of its titles (whole) and one author surname in common, or the
     * same ISBN as a catalog edition. Never by title alone.
     */
    suspend fun match(catalog: CatalogDao, b: Book): Long? {
        for (isbn in BookMatcher.isbns(b.isbn)) catalog.editionByIsbn(isbn)?.let { return it.workId }
        val surnames = BookMatcher.surnames(b.author).map(SearchIndex::normalize).toSet()
        if (surnames.isEmpty()) return null
        val mine = listOf(b.title, b.meta.optString("originalTitle")).filter { it.isNotBlank() }.map(SearchIndex::normalize).toSet()
        val query = ftsQuery(b.title) ?: return null
        for (hit in catalog.search(query, 20)) {
            val authorWords = SearchIndex.words(hit.work.authors).toSet()
            if (surnames.none { s -> s.split(' ').all { it in authorWords } }) continue
            val theirs = ((catalog.titlesOf(hit.work.id) ?: "").split(" | ") + listOfNotNull(hit.work.title, hit.work.originalTitle, hit.work.italianTitle))
                .map(SearchIndex::normalize).toSet()
            if (mine.any { it in theirs }) return hit.work.id
        }
        return null
    }

    /** Books whose indexed text matches [text] (all words as prefixes, catalog titles included). */
    suspend fun search(dao: SagaDao, text: String): List<String> = ftsQuery(text)?.let { runCatching { dao.searchLibrary(it) }.getOrNull() }.orEmpty()
}
