package app.atlante.library.catalog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.atlante.library.MainActivity
import app.atlante.library.R
import app.atlante.library.model.Book
import app.atlante.library.saga.CacheSettings
import app.atlante.library.saga.SagaRepair
import app.atlante.library.saga.SagaRepository
import app.atlante.library.saga.Suggestions
import app.atlante.library.saga.db.AtlanteDatabase
import app.atlante.library.saga.db.RepairJob
import app.atlante.library.saga.db.SagaDao
import app.atlante.library.storage.LibraryStore
import app.atlante.library.sync.HardcoverAuth
import app.atlante.library.sync.HardcoverSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every repair runs here, in the background: the app stays usable, a notification shows the real progress
 * ("650 / 1.000 libri analizzati — 65%") with "Interrompi", the bar in the app and "Operazioni in background"
 * read the same record (table repair_jobs).
 * - One repair at a time (unique work, queued), never during a Hardcover sync.
 * - Local first: the offline catalog and the library index answer before any online source.
 * - Certain results (same work verified, no conflict) are applied; the others wait for you as proposals.
 * - Stopped by Android: it starts again after the books already analysed (checkpoint). Stopped by you: what was
 *   applied stays, nothing half-done (each merge is one save).
 */
class RepairWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private lateinit var dao: SagaDao
    private lateinit var job: RepairJob
    private var lastSaved = 0L

    override suspend fun doWork(): Result {
        val c = applicationContext
        dao = AtlanteDatabase.get(c).saga()
        val type = inputData.getString(TYPE) ?: return Result.failure()
        val id = id.toString()
        job = dao.job(id)?.let { it.copy(state = "running", message = if (it.checkpoint > 0) "Ripresa da ${it.checkpoint} libri già analizzati" else "") }
            ?: RepairJob(id, type, "running", "Preparazione", System.currentTimeMillis())
        save(force = true)
        channel(c)
        runCatching { setForeground(info()) }
        SagaRepository.configureCatalog(c)
        app.atlante.library.saga.Hardcover.configure(c)
        return try {
            while (HardcoverSync.syncing) { phase("In attesa della sincronizzazione con Hardcover"); delay(2000) }
            val store = LibraryStore.shared(c)
            when (type) {
                LIBRARY, HARDCOVER -> duplicates(c, store, type == HARDCOVER)
                SAGAS -> sagas(c, store)
                MAPS -> maps(store)
                METADATA -> metadata(c, store)
                COVERS -> covers(c, store, inputData.getBoolean(EVERYTHING, false))
                IMPORT -> import(c, store)
                GENRES -> genres(c, store)
            }
            job = job.copy(state = "done", phase = "Completato", finishedAt = System.currentTimeMillis())
            save(force = true); finished(c)
            Result.success()
        } catch (e: CancellationException) {
            // You stopped it: "cancelled"; Android stopped it: it will start again from the checkpoint.
            // Android 12+ says why; before, WorkManager's own record says whether the work was cancelled.
            val byYou = if (Build.VERSION.SDK_INT >= 31) stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
                else runCatching { WorkManager.getInstance(c).getWorkInfoById(this@RepairWorker.id).get(2, java.util.concurrent.TimeUnit.SECONDS)?.state == WorkInfo.State.CANCELLED }.getOrDefault(true)
            job = job.copy(state = if (byYou) "cancelled" else "queued",
                phase = if (byYou) "Annullato" else "In pausa", finishedAt = System.currentTimeMillis(),
                message = if (byYou) "Interrotta da te: le modifiche già applicate restano" else "Interrotta da Android: riprende da sola")
            if (byYou) dropImportFile()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { save(force = true) }
            throw e
        } catch (e: Exception) {
            job = job.copy(state = "failed", phase = "Errore", finishedAt = System.currentTimeMillis(), message = e.message ?: e.javaClass.simpleName, errors = job.errors + 1)
            save(force = true); finished(c); dropImportFile()
            Result.failure()
        } catch (e: OutOfMemoryError) {
            // A file too big for this phone: reported as such, the library is untouched (nothing was saved yet).
            job = job.copy(state = "failed", phase = "Errore", finishedAt = System.currentTimeMillis(), errors = job.errors + 1,
                message = "Il file è troppo grande per la memoria di questo telefono: nessun libro è stato modificato")
            save(force = true); finished(c); dropImportFile()
            Result.failure()
        }
    }

    // ---- the repairs -----------------------------------------------------------------------------------------

    /** Library (or only Hardcover's records): same work twice → one record; certain pairs at once, the rest proposed. */
    private suspend fun duplicates(c: Context, store: LibraryStore, hardcoverOnly: Boolean) {
        phase("Analisi locale")
        // The index first: every book linked to its catalog work (only changed books are looked at again).
        LibraryIndex.syncNow(c)
        val books = store.snapshot()
        job = job.copy(total = books.size); save(force = true)
        val identities = identities(books)
        job = job.copy(done = books.size, offline = identities.values.count { it.catalogWork != null }); save()
        var pairs = BibliographicEngine.duplicates(books, identities)
        if (hardcoverOnly) pairs = pairs.filter { BibliographicEngine.hardcoverBook(it.other) != null || BibliographicEngine.hardcoverBook(it.keep) != null }
        // Hardcover's English copies that only Hardcover can tie to the Italian book (its editions list).
        if (hardcoverOnly && HardcoverAuth.connected(c)) {
            phase("Recupero online da Hardcover")
            val s = HardcoverSync.Settings().apply { token = HardcoverAuth.token(c) }
            val online = runCatching { HardcoverSync.duplicates(store, s, object : HardcoverSync.Progress { override fun update(message: String) { if (isStopped) s.cancelled = true } }) }.getOrDefault(emptyList())
            val known = pairs.map { setOf(it.keep.id, it.other.id) }.toSet()
            for (d in online) if (setOf(d.copy.id, d.original.id) !in known)
                pairs = pairs + BibliographicEngine.Pair(d.original, d.copy, if (d.certain) BibliographicEngine.Confidence.VERIFIED else BibliographicEngine.Confidence.PROBABLE,
                    listOf(if (d.certain) "Hardcover: stessa opera (ISBN o edizione italiana)" else "Hardcover: titolo e autore simili"), BibliographicEngine.conflicts(d.original, d.copy))
        }
        phase("Verifica dei risultati")
        job = job.copy(duplicates = pairs.size); save(force = true)
        phase("Applicazione delle modifiche")
        val proposals = JSONArray(); var applied = 0
        for (p in pairs) {
            if (isStopped) break
            if (p.automatic) {
                val keep = store.find(p.keep.id); val other = store.find(p.other.id)
                if (keep == null || other == null) continue                 // already merged (resumed job)
                runCatching { store.merge(BibliographicEngine.merged(keep, other), other) }.onSuccess { applied++ }.onFailure { job = job.copy(errors = job.errors + 1) }
            } else proposals.put(proposal(p))
            job = job.copy(applied = applied, proposals = proposals.length()); save()
        }
        job = job.copy(result = JSONObject().put("merges", proposals).toString(), message = "$applied unioni certe applicate" +
            if (proposals.length() > 0) " · ${proposals.length()} da decidere" else ""); save(force = true)
        if (applied > 0) { LibraryIndex.syncNow(c); app.atlante.library.widget.Widgets.libraryChanged(c) }
    }

    private suspend fun identities(books: List<Book>): Map<String, BibliographicEngine.Identity> {
        val catalog = dao.catalogLinks().associate { it.bookId to it.catalogWork.toLongOrNull() }
        val graph = dao.links().filter { it.status in setOf("auto", "confirmed", "manual", "no_saga") }.associate { it.bookId to it.workId }
        return books.associate { b -> b.id to BibliographicEngine.Identity(b.id, catalog[b.id], BibliographicEngine.hardcoverBook(b), graph[b.id]) }
    }

    /** Ripara saghe: every book, the offline catalog first, online only for what it does not know. */
    private suspend fun sagas(c: Context, store: LibraryStore) {
        phase("Analisi locale e online")
        val repo = SagaRepository(dao).apply { refreshDays = CacheSettings(c).sagaRefreshDays }
        val books = store.snapshot().sortedBy { it.id }
        job = job.copy(total = books.size); save(force = true)
        val base = job.checkpoint
        val todo = books.drop(base)
        // progress(i) comes before book i: the checkpoint counts the books already finished.
        val report = SagaRepair(dao, repo).run(todo, everything = inputData.getBoolean(EVERYTHING, false), fill = { b, name, ordinal -> fillSeries(store, b, name, ordinal) }) { i, _, _ ->
            if (isStopped) throw CancellationException()
            job = job.copy(done = base + i + 1, checkpoint = base + i); save()
        }
        job = job.copy(done = books.size, checkpoint = books.size, found = report.linked + report.toConfirm, proposals = report.toConfirm,
            message = "${report.linked} saghe sicure · ${report.toConfirm} da confermare · ${report.filled} schede completate"); save(force = true)
    }

    private fun fillSeries(store: LibraryStore, b: Book, name: String, ordinal: String?): Boolean {
        val x = store.find(b.id) ?: return false
        if (x.series.isNotEmpty()) return false
        val copy = Book.from(x.json()); copy.series = name
        ordinal?.replace(',', '.')?.toDoubleOrNull()?.let { copy.number = it }
        return runCatching { store.replace(copy) }.isSuccess
    }

    /** Ripara mappe: the same checks as "Controlla le mappe", proposals to confirm. */
    private suspend fun maps(store: LibraryStore) {
        phase("Analisi delle mappe")
        val books = store.snapshot()
        job = job.copy(total = books.size); save(force = true)
        val issues = SagaRepair(dao, SagaRepository(dao)).mapIssues(Suggestions(dao, { books }), books)
        val list = JSONArray()
        for (i in issues) list.put(JSONObject().put("series", i.series).put("seriesId", i.seriesId).put("text", i.text).put("bookId", i.bookId ?: JSONObject.NULL)
            .put("ordinal", i.ordinal ?: JSONObject.NULL).put("source", i.source))
        job = job.copy(done = books.size, found = issues.size, proposals = issues.count { it.bookId != null }, result = JSONObject().put("maps", list).toString(),
            message = if (issues.isEmpty()) "Mappe coerenti con le fonti" else "${issues.size} cose da guardare"); save(force = true)
    }

    /**
     * Completa metadati: from the offline catalog first (original and Italian title, year, genre, saga with its
     * verified number, Open Library id), then a cover online for books without one. Only empty fields are filled:
     * what you wrote, your edition and your chosen cover stay.
     */
    private suspend fun metadata(c: Context, store: LibraryStore) {
        phase("Analisi locale")
        LibraryIndex.syncNow(c)
        val catalog = runCatching { CatalogDatabase.get(c).catalog() }.getOrNull()
        val links = dao.catalogLinks().associate { it.bookId to it.catalogWork.toLongOrNull() }
        val books = store.snapshot().sortedBy { it.id }
        job = job.copy(total = books.size); save(force = true)
        var filled = job.found; var covers = 0; var offline = job.offline
        val conflicts = runCatching { JSONObject(job.result).optJSONArray("fields") }.getOrNull() ?: JSONArray()
        val changed = ArrayList<Book>()
        for ((i, b) in books.withIndex()) {
            if (i < job.checkpoint) continue
            if (isStopped) throw CancellationException()
            val work = links[b.id]?.let { catalog?.work(it) }
            if (work != null && catalog != null) {
                offline++
                val copy = Book.from(b.json()); var any = false
                if (copy.meta.optString("originalTitle").isEmpty() && work.originalTitle.isNotBlank() && app.atlante.library.model.SearchIndex.normalize(work.originalTitle) != app.atlante.library.model.SearchIndex.normalize(copy.title)) {
                    copy.meta.put("originalTitle", work.originalTitle); any = true }
                // The work's first publication (not your edition's year, which may be the Italian one).
                if (copy.meta.optString("firstPublished").isEmpty() && work.year != null) { copy.meta.put("firstPublished", work.year.toString()); any = true }
                val keepMine = copy.meta.optJSONArray("keepMine")?.let { k -> (0 until k.length()).map { k.optString(it) } }.orEmpty()
                // Different from what you wrote: never changed here, proposed (and "Tieni il mio" is remembered).
                if (copy.meta.optString("originalTitle").isNotEmpty() && work.originalTitle.isNotBlank() && "originalTitle" !in keepMine &&
                    app.atlante.library.model.SearchIndex.normalize(copy.meta.optString("originalTitle")) != app.atlante.library.model.SearchIndex.normalize(work.originalTitle))
                    conflicts.put(JSONObject().put("bookId", b.id).put("field", "originalTitle").put("value", work.originalTitle)
                        .put("text", "«${b.title}»: titolo originale «${copy.meta.optString("originalTitle")}» nella scheda, «${work.originalTitle}» nel catalogo (Wikidata)"))
                if (copy.series.isNotEmpty() && copy.number > 0 && "number" !in keepMine) catalog.entriesOf(work.id).forEach { e ->
                    val s = catalog.series(e.seriesId) ?: return@forEach
                    val same = app.atlante.library.ui.MapGroups.seriesKey(copy.series).let { k -> k == app.atlante.library.ui.MapGroups.seriesKey(s.name) || k == app.atlante.library.ui.MapGroups.seriesKey(s.italianName ?: "") }
                    if (same && e.sortKey != null && e.sortKey != copy.number)
                        conflicts.put(JSONObject().put("bookId", b.id).put("field", "number").put("value", e.ordinal).put("series", copy.series)
                            .put("text", "«${b.title}»: n. ${Book.formatNumber(copy.number)} di ${copy.series} nella scheda, n. ${e.ordinal} nel catalogo (Wikidata)"))
                }
                if (copy.genre.isEmpty() && work.genre != null) { copy.genre = work.genre; any = true }
                val ids = copy.meta.optJSONObject("ids") ?: JSONObject()
                if (ids.optString("wikidata").isEmpty()) { ids.put("wikidata", "Q${work.id}"); any = true }
                if (work.openLibraryId != null && ids.optString("openlibrary").isEmpty()) { ids.put("openlibrary", work.openLibraryId); any = true }
                copy.meta.put("ids", ids)
                if (copy.series.isEmpty()) catalog.entriesOf(work.id).filter { it.ordinal != null }.minByOrNull { it.sortKey ?: Double.MAX_VALUE }?.let { e ->
                    catalog.series(e.seriesId)?.let { s -> copy.series = s.italianName ?: s.name; copy.number = e.sortKey ?: 0.0; any = true } }
                if (any) {
                    val sources = copy.meta.optJSONObject("sources") ?: JSONObject(); sources.put("catalog", "Catalogo offline (Wikidata, Open Library)"); copy.meta.put("sources", sources)
                    changed += copy; filled++
                }
            }
            // No cover at all: one online search (the catalog has no images, only their addresses).
            if (inputData.getBoolean(ONLINE, true) && !store.cover(b).isFile && b.meta.optString("coverSource") !in setOf("gallery", "online")) {
                phase("Recupero online delle copertine")
                val cover = runCatching { app.atlante.library.online.CoverSearch.best(b) }.getOrNull()
                if (cover != null) runCatching { store.saveDownloadedCover(b, cover.bytes); covers++ }
                phase("Analisi locale")
            }
            if (changed.size >= 50) { store.replaceAll(changed); changed.clear() }
            job = job.copy(done = i + 1, checkpoint = if (changed.isEmpty()) i + 1 else job.checkpoint, found = filled, offline = offline, applied = filled + covers); save()
        }
        if (changed.isNotEmpty()) store.replaceAll(changed)
        job = job.copy(done = books.size, checkpoint = books.size, applied = filled + covers, proposals = conflicts.length(),
            result = JSONObject().put("fields", conflicts).toString(),
            message = "$filled schede completate dal catalogo offline · $covers copertine trovate online" + if (conflicts.length() > 0) " · ${conflicts.length()} differenze da decidere" else ""); save(force = true)
    }

    /**
     * Cerca e migliora le copertine: books without a cover, and with [improve] the small ones (under 300 px) that
     * you did not choose. A new cover replaces one only when clearly sharper. Your choices are never touched.
     */
    private suspend fun covers(c: Context, store: LibraryStore, improve: Boolean) {
        phase("Ricerca delle copertine")
        fun width(f: java.io.File): Int { val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeFile(f.path, o); return maxOf(0, o.outWidth) }
        val todo = store.snapshot().filter { b ->
            val f = store.cover(b); val src = b.meta.optString("coverSource")
            !f.isFile || (improve && src !in setOf("gallery", "online") && width(f) < 300)
        }.sortedBy { it.id }
        job = job.copy(total = todo.size); save(force = true)
        var found = job.found; var better = job.applied - job.found
        for ((i, b) in todo.withIndex()) {
            if (i < job.checkpoint) continue
            if (isStopped) throw CancellationException()
            val current = store.find(b.id)
            if (current != null) {
                val old = store.cover(current).let { if (it.isFile) width(it) else 0 }
                val cover = runCatching { app.atlante.library.online.CoverSearch.best(current) }.getOrNull()
                if (cover != null && (old == 0 || cover.width >= old * 3 / 2)) runCatching {
                    store.saveDownloadedCover(current, cover.bytes)
                    store.find(b.id)?.let { x -> val copy = Book.from(x.json()); copy.meta.put("coverSource", "auto"); copy.meta.put("coverOrigin", cover.origin); store.replace(copy) }
                    if (old == 0) found++ else better++
                }
            }
            job = job.copy(done = i + 1, checkpoint = i + 1, found = found, applied = found + better); save()
        }
        job = job.copy(done = todo.size, message = if (todo.isEmpty()) "Tutte le copertine sono già presenti e nitide" else "$found copertine nuove" + if (improve) " · $better migliorate" else "")
        save(force = true)
        app.atlante.library.widget.Widgets.libraryChanged(c)
    }

    /**
     * Importazione (backup ZIP di Atlante, CSV, JSON, catalogo HTML, libreria Calibre), from a copy of the file in the
     * app's own folder. Only new works are added (ImportPlanner: same id, ISBN, file, or title and author already
     * here → left exactly as it is on the phone); the same file imported twice adds nothing. One save for all the
     * books, with their files and covers; a damaged file ends with its reason, the library untouched.
     */
    private suspend fun import(c: Context, store: LibraryStore) {
        val file = java.io.File(inputData.getString(PATH) ?: throw java.io.IOException("File da importare non trovato"))
        if (!file.isFile) throw java.io.IOException("Il file da importare non è più disponibile: sceglilo di nuovo")
        phase("Lettura del file")
        val contents = java.io.FileInputStream(file).use { app.atlante.library.storage.CatalogImport.read(it, inputData.getString(FILE_NAME) ?: file.name) }
        job = job.copy(total = contents.books.size); save(force = true)
        phase("Confronto con la biblioteca")
        LibraryIndex.syncNow(c)
        val plan = ImportPlanner.plan(contents.books, store.snapshot())
        job = job.copy(done = contents.books.size, duplicates = plan.reconciled, found = plan.fresh.size); save(force = true)
        if (isStopped) throw CancellationException()
        phase("Aggiunta dei libri")
        val fresh = LinkedHashMap<String, Book>(); for (b in plan.fresh) fresh[b.id] = b
        val staging = store.staging()
        var added = 0; var withoutFile = 0
        try {
            app.atlante.library.storage.LibraryStore.clear(staging)
            var withFiles: Set<String> = emptySet()
            if (contents.atlante && contents.fromZip && fresh.isNotEmpty())
                withFiles = java.io.FileInputStream(file).use { app.atlante.library.storage.BackupArchive.extract(it, fresh, staging, contents.nested, contents.prefix) }
            for ((id, bytes) in contents.covers) if (id in fresh) java.io.File(staging, id).apply { mkdirs() }.resolve("cover").writeBytes(bytes)
            withoutFile = fresh.values.count { it.format != "CATALOGO" && it.id !in withFiles }
            if (fresh.isNotEmpty()) added = store.restore(ArrayList(fresh.values), staging)
        } finally { app.atlante.library.storage.LibraryStore.clear(staging) }
        val result = JSONObject().put("import", JSONObject().put("kind", contents.kind).put("analyzed", contents.books.size).put("added", added)
            .put("present", plan.sameRecord).put("reconciled", plan.reconciled).put("repeated", plan.repeatedInFile).put("withoutFile", withoutFile)
            .put("addedIds", JSONArray(fresh.keys.toList())))
        job = job.copy(applied = added, result = result.toString(), message = "${contents.kind}: $added libri aggiunti · ${plan.sameRecord + plan.reconciled} già presenti" +
            (if (plan.reconciled > 0) " (${plan.reconciled} riconosciuti per ISBN o titolo e autore)" else "") +
            (if (plan.repeatedInFile > 0) " · ${plan.repeatedInFile} ripetuti nel file" else "") +
            (if (withoutFile > 0) " · $withoutFile senza file (reimportali da Importa eBook)" else ""))
        save(force = true)
        dropImportFile()
        // Covers of the new books next, in their own job (an extra: the import is complete without it).
        if (added > 0) { LibraryIndex.syncNow(c); app.atlante.library.widget.Widgets.libraryChanged(c); runCatching { start(c, COVERS, false) } }
    }

    /**
     * Indicizza i generi: every book gets its canonical genres (meta.genres, several allowed) from its own label;
     * books without a recognizable genre get them from reliable sources only, local first: the offline catalog
     * (Wikidata P136), then Hardcover (genres voted by readers, if connected), then the Dewey class (SBN).
     * Free-form subjects are never used (an adaptation is not the book's genre). The label you wrote is kept; an
     * empty one is filled with the genres found.
     */
    private suspend fun genres(c: Context, store: LibraryStore) {
        phase("Analisi locale")
        LibraryIndex.syncNow(c)
        val catalog = runCatching { CatalogDatabase.get(c).catalog() }.getOrNull()
        val links = dao.catalogLinks().associate { it.bookId to it.catalogWork.toLongOrNull() }
        val books = store.snapshot().sortedBy { it.id }
        job = job.copy(total = books.size); save(force = true)
        var indexed = job.applied; var online = job.offline; var none = job.found
        val changed = ArrayList<Book>()
        for ((i, b) in books.withIndex()) {
            if (i < job.checkpoint) continue
            if (isStopped) throw CancellationException()
            var found = app.atlante.library.saga.Genres.all(b.genre); var source = "etichetta della scheda"
            if (found.isEmpty()) links[b.id]?.let { catalog?.work(it) }?.let { w ->
                found = app.atlante.library.saga.Genres.all(w.genres.ifBlank { w.genre ?: "" }); source = "catalogo offline (Wikidata)" }
            if (found.isEmpty() && app.atlante.library.saga.Hardcover.enabled) {
                phase("Recupero online")
                found = app.atlante.library.saga.Genres.all(runCatching { app.atlante.library.saga.Hardcover.genresFor(b) }.getOrDefault(emptyList()).joinToString(", "))
                if (found.isNotEmpty()) { source = "Hardcover (generi votati dai lettori)"; online++ }
            }
            if (found.isEmpty() && inputData.getBoolean(ONLINE, true)) {
                phase("Recupero online")
                val g = runCatching { app.atlante.library.online.MetadataEngine.lookup(b, app.atlante.library.online.MetadataEngine.Settings()) { }.fields.firstOrNull { it.key == "genre" } }.getOrNull()
                if (g != null) { found = app.atlante.library.saga.Genres.all(g.proposed); if (found.isNotEmpty()) { source = g.source; online++ } }
            }
            val before = app.atlante.library.saga.Genres.of(b)
            if (found.isEmpty()) none++
            else if (found != before || b.meta.optJSONArray("genres") == null) {
                val copy = Book.from(b.json())
                copy.meta.put("genres", JSONArray(found))
                val sources = copy.meta.optJSONObject("sources") ?: JSONObject(); sources.put("genres", source); copy.meta.put("sources", sources)
                if (copy.genre.isBlank()) copy.genre = found.joinToString(", ")
                changed += copy; indexed++
            }
            if (changed.size >= 50) { store.replaceAll(changed); changed.clear() }
            job = job.copy(done = i + 1, checkpoint = if (changed.isEmpty()) i + 1 else job.checkpoint, applied = indexed, offline = online, found = none); save()
            if (job.phase != "Analisi locale") phase("Analisi locale")
        }
        if (changed.isNotEmpty()) store.replaceAll(changed)
        job = job.copy(done = books.size, checkpoint = books.size, message = "$indexed libri indicizzati" + (if (online > 0) " ($online con fonti online)" else "") +
            if (none > 0) " · $none senza un genere affidabile: restano senza, nessun genere inventato" else "")
        save(force = true)
    }

    /** The copy of the imported file is removed once the import is over (kept only to resume after Android stops it). */
    private fun dropImportFile() { if (inputData.getString(TYPE) == IMPORT) inputData.getString(PATH)?.let { java.io.File(it).delete() } }

    private fun proposal(p: BibliographicEngine.Pair) = JSONObject().put("keep", p.keep.id).put("other", p.other.id)
        .put("keepLine", line(p.keep)).put("otherLine", line(p.other)).put("confidence", p.confidence.label)
        .put("reasons", JSONArray(p.reasons)).put("conflicts", JSONArray(p.conflicts))

    private fun line(b: Book) = listOf("«${b.title}» · ${b.author}", b.status, b.isbn.takeIf { it.isNotBlank() }?.let { "ISBN $it" },
        b.format.takeIf { it != "CATALOGO" }, b.finished.takeIf { it.isNotBlank() }?.let { "letto il $it" },
        app.atlante.library.model.ReadingJournal.rating(b).takeIf { it > 0 }?.let { "voto $it" },
        (b.journal.optJSONArray("sessions")?.length() ?: 0).takeIf { it > 0 }?.let { "$it sessioni" }).filterNotNull().joinToString(" · ")

    // ---- progress: the record, the notification, the work data -----------------------------------------------

    private suspend fun phase(name: String) { if (job.phase != name) { job = job.copy(phase = name); save(force = true) } }

    /** At most twice a second: the record (bar in the app), WorkManager's progress and the notification. */
    private suspend fun save(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaved < 500) return
        lastSaved = now
        dao.putJob(job)
        runCatching { setProgress(workDataOf("done" to job.done, "total" to job.total)) }
        if (job.state == "running") runCatching { setForeground(info()) }
    }

    private fun info(): ForegroundInfo {
        val c = applicationContext
        val text = progressText(job)
        val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_sync).setContentTitle(title(job.type))
            .setContentText(text).setSubText(job.phase).setOnlyAlertOnce(true).setOngoing(true).setSilent(true).setContentIntent(open(c))
            .setProgress(job.total, job.done, job.total == 0)
            .addAction(0, "Interrompi", WorkManager.getInstance(c).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTICE, n)
    }

    private fun finished(c: Context) {
        val title = when (job.state) { "done" -> title(job.type) + ": fatto"; "failed" -> title(job.type) + ": errore"; else -> title(job.type) }
        val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_sync).setContentTitle(title)
            .setContentText(job.message.ifEmpty { job.phase }).setAutoCancel(true).setContentIntent(open(c)).build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(DONE, n) }
    }

    companion object {
        const val TYPE = "type"; const val EVERYTHING = "everything"
        /** false: only the offline catalog (no cover searched online). */
        const val ONLINE = "online"

        /** The notification's line: "650 / 1.000 libri analizzati — 65%"; the phase alone while the total is unknown. */
        @JvmStatic fun progressText(j: RepairJob): String {
            fun n(x: Int) = "%,d".format(java.util.Locale.ITALY, x)
            return if (j.total > 0) "${n(j.done)} / ${n(j.total)} libri analizzati — ${j.done * 100 / j.total}%" else j.phase.ifEmpty { "Preparazione" }
        }
        const val LIBRARY = "library"; const val HARDCOVER = "hardcover"; const val SAGAS = "sagas"; const val MAPS = "maps"; const val METADATA = "metadata"; const val COVERS = "covers"; const val IMPORT = "import"; const val GENRES = "genres"
        const val PATH = "path"; const val FILE_NAME = "fileName"
        const val NAME = "atlante-repair"; const val TAG = "repair"
        private const val CHANNEL = "repair"; private const val NOTICE = 4201; private const val DONE = 4202

        @JvmStatic fun title(type: String) = when (type) {
            LIBRARY -> "Ripara duplicati della libreria"; HARDCOVER -> "Ripara duplicati Hardcover"; SAGAS -> "Ripara saghe"
            MAPS -> "Ripara mappe delle saghe"; METADATA -> "Completa metadati"; COVERS -> "Ricerca delle copertine"; IMPORT -> "Importazione libreria"; GENRES -> "Indicizza i generi"; else -> "Riparazione"
        }

        private fun channel(c: Context) {
            if (Build.VERSION.SDK_INT >= 26) c.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Riparazioni del catalogo", NotificationManager.IMPORTANCE_LOW).apply { description = "Avanzamento di duplicati, saghe e metadati" })
        }

        private fun open(c: Context) = PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java).setAction(MainActivity.OPEN_OPERATIONS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /** Queued after the repair already running, if any: two repairs never change the same records together. */
        @JvmStatic fun start(c: Context, type: String): java.util.UUID = start(c, type, false)

        @JvmStatic fun start(c: Context, type: String, everything: Boolean): java.util.UUID {
            val request = OneTimeWorkRequestBuilder<RepairWorker>().setInputData(workDataOf(TYPE to type, EVERYTHING to everything)).addTag(TAG).build()
            WorkManager.getInstance(c).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            // Shown at once as "In attesa" in the bar and in "Operazioni in background".
            val app = c.applicationContext
            Thread { kotlinx.coroutines.runBlocking { AtlanteDatabase.get(app).saga().putJob(RepairJob(request.id.toString(), type, "queued", "In attesa", System.currentTimeMillis())) } }.start()
            return request.id
        }

        /** An import of the file at [path] (a copy in the app's folder), queued like every repair. */
        @JvmStatic fun startImport(c: Context, path: String, name: String): java.util.UUID {
            val request = OneTimeWorkRequestBuilder<RepairWorker>().setInputData(workDataOf(TYPE to IMPORT, PATH to path, FILE_NAME to name)).addTag(TAG).build()
            WorkManager.getInstance(c).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            val app = c.applicationContext
            Thread { kotlinx.coroutines.runBlocking { AtlanteDatabase.get(app).saga().putJob(RepairJob(request.id.toString(), IMPORT, "queued", "In attesa", System.currentTimeMillis())) } }.start()
            return request.id
        }

        @JvmStatic fun cancel(c: Context, id: String) = WorkManager.getInstance(c).cancelWorkById(java.util.UUID.fromString(id))
    }
}
