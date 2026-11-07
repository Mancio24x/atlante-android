package app.atlante.library.sync;

import app.atlante.library.model.Book;
import app.atlante.library.model.BookMatcher;
import app.atlante.library.storage.CatalogImport;
import app.atlante.library.online.OnlineCatalog;
import app.atlante.library.online.SbnCatalog;
import app.atlante.library.storage.SyncStore;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hardcover (hardcover.app) through its official GraphQL API and a personal access token:
 * reading state, current page and half-star rating, both ways. Hardcover has no chapter field.
 * Statuses: 1 want to read, 2 currently reading, 3 read, 4 paused, 5 did not finish, 6 ignored.
 */
public final class HardcoverSync {
    public static final String SERVICE = "hardcover", ENDPOINT = "https://api.hardcover.app/v1/graphql";
    private static long nextRequest;

    public static final class Settings { public String token = ""; public boolean createMissing = true, sendToRead = false; public volatile boolean cancelled; }
    /** True while a sync runs: the background saga scan waits, so the sync gets Hardcover's whole request budget. */
    public static volatile boolean syncing;
    /** A book searched on Hardcover without result is not searched again for this long. */
    static final long MISSING_RETRY = 30L * 86_400_000L;
    public static final class Report {
        /** Doubles of earlier syncs merged automatically. */
        public int merged;
        public String user = ""; public int remote, linked, pushed, pulled, created, added, onlyHere, recognized;
        public final List<String> notes = new ArrayList<>();
    }
    public interface Progress {
        void update(String message);
        /** How far the sync is (books compared out of all), for the notification's progress bar. */
        default void count(int done, int total) { }
    }
    public interface Api { JSONObject call(String query, JSONObject variables) throws Exception; }

    public static Report sync(SyncStore store, Settings s, Progress progress) throws Exception {
        String token = s.token.trim();
        if (token.isEmpty()) throw new IOException("Incolla prima la chiave personale di Hardcover.");
        return sync(store, s, progress, (query, variables) -> request(token, query, variables));
    }

    /** The API is injected so that the merge can be tested without a network. */
    public static Report sync(SyncStore store, Settings s, Progress progress, Api api) throws Exception {
        syncing = true;
        List<Book> pending = new ArrayList<>();
        try { return run(store, s, progress, api, pending); }
        finally { syncing = false; store.replaceAll(pending); }
    }

    /** Changed books are saved in groups of 100 (and at the end, also when interrupted or failing). */
    private static void keep(SyncStore store, List<Book> pending, Book local, Book copy) throws Exception {
        if (copy.json().toString().equals(local.json().toString())) return;
        pending.add(copy);
        if (pending.size() >= 100) { store.replaceAll(pending); pending.clear(); }
    }

    private static Report run(SyncStore store, Settings s, Progress progress, Api api, List<Book> pending) throws Exception {
        Report report = new Report();
        List<JSONObject> remote = remote(api, s, progress, report);
        if (remote == null) { report.notes.add("Sincronizzazione interrotta: niente è stato cambiato."); return report; }
        report.remote = remote.size();
        // Doubles left by earlier syncs: the certain ones are merged at once (the copy holds nothing of the user's own);
        // only the doubtful ones wait for "Ripara duplicati Hardcover".
        List<Duplicate> certain = new ArrayList<>();
        for (Duplicate d : duplicates(store, s, progress, api, remote)) if (d.certain) certain.add(d);
        if (!certain.isEmpty() && !s.cancelled) {
            progress.update("Unisco " + certain.size() + " doppioni di sincronizzazioni precedenti…");
            merge(store, certain); report.merged = certain.size();
            for (Duplicate d : certain) report.notes.add("Unito: «" + d.copy.title + "» in «" + d.original.title + "»");
        }
        return compare(store, s, progress, api, pending, report, remote);
    }

    /** The user's Hardcover library (null when interrupted). */
    static List<JSONObject> remote(Api api, Settings s, Progress progress, Report report) throws Exception {
        progress.update("Collegamento a Hardcover…");
        Object me = api.call("query { me { id username } }", new JSONObject()).opt("me");
        JSONObject user = me instanceof JSONArray ? ((JSONArray) me).optJSONObject(0) : (JSONObject) me;
        if (user == null) throw new IOException("Hardcover non ha riconosciuto la chiave.");
        int userId = user.getInt("id"); report.user = user.optString("username");
        List<JSONObject> remote = new ArrayList<>();
        for (int offset = 0; ; offset += 100) {
            if (s.cancelled) return null;
            progress.update("Lettura della libreria Hardcover · " + remote.size() + " libri");
            JSONArray page = api.call("query($id:Int!,$offset:Int!){ user_books(where:{user_id:{_eq:$id}}, order_by:{id:asc}, limit:100, offset:$offset) {"
                + " id book_id status_id rating edition { id pages isbn_13 isbn_10 }"
                + " book { id title pages image { url } contributions { author { name } } editions(where:{language_id:{_eq:76}}, limit:3) { title isbn_13 isbn_10 } }"
                + " user_book_reads(where:{finished_at:{_is_null:true}}, order_by:{id:desc}, limit:1) { id progress_pages } } }",
                new JSONObject().put("id", userId).put("offset", offset)).optJSONArray("user_books");
            if (page == null) break;
            for (int i = 0; i < page.length(); i++) { JSONObject ub = page.optJSONObject(i); if (ub != null && ub.optInt("status_id") != 6) remote.add(ub); }
            if (page.length() < 100) break;
        }
        return remote;
    }

    /** Hardcover book ids of the ISBNs, 50 per request. */
    static Map<String, Integer> bookIds(Api api, List<String> isbns, Settings s, Progress progress) throws Exception {
        Map<String, Integer> byIsbn = new HashMap<>(); List<String> wanted = new ArrayList<>(new LinkedHashSet<>(isbns));
        for (int i = 0; i < wanted.size(); i += 50) {
            if (s.cancelled) break;
            progress.update("Ricerca degli ISBN su Hardcover · " + Math.min(i + 50, wanted.size()) + " di " + wanted.size());
            JSONArray found = api.call("query($i:[String!]){ editions(where:{isbn_13:{_in:$i}}) { isbn_13 book_id book { canonical_id } } }",
                new JSONObject().put("i", new JSONArray(wanted.subList(i, Math.min(i + 50, wanted.size()))))).optJSONArray("editions");
            for (int j = 0; found != null && j < found.length(); j++) {
                JSONObject e = found.optJSONObject(j); if (e == null) continue;
                JSONObject bk = e.optJSONObject("book"); int id = bk != null && !bk.isNull("canonical_id") ? bk.optInt("canonical_id") : e.optInt("book_id");
                if (id > 0) byIsbn.putIfAbsent(e.optString("isbn_13"), id);
            }
        }
        return byIsbn;
    }

    private static Report compare(SyncStore store, Settings s, Progress progress, Api api, List<Book> pending, Report report, List<JSONObject> remote) throws Exception {
        String today = LocalDate.now().toString();
        Set<Integer> matched = new HashSet<>();
        List<Book> all = store.snapshot(); int done = 0; long now = System.currentTimeMillis();
        // The ISBNs of the books that may need looking up, asked 50 at a time instead of one request per book.
        List<String> wanted = new ArrayList<>();
        // Every book not yet linked: also the "Da leggere" ones, so that a Hardcover book in English is recognized as a
        // book already here (same Hardcover book id) instead of becoming a second sheet.
        for (Book b : all) {
            JSONObject l = SyncCore.link(b, SERVICE);
            if (l != null && l.optInt("id") > 0) continue;
            wanted.addAll(BookMatcher.isbns(b.isbn));
        }
        Map<String, Integer> byIsbn = bookIds(api, wanted, s, progress);
        Map<Integer, JSONObject> remoteByBook = new HashMap<>();
        for (JSONObject ub : remote) remoteByBook.putIfAbsent(ub.optInt("book_id"), ub);
        Set<Integer> localBooks = new HashSet<>();     // Hardcover book ids of the books in Atlante
        for (Book local : all) {
            if (s.cancelled) { report.notes.add("Sincronizzazione interrotta: i libri già controllati sono salvati."); return report; }
            done++;
            if (done % 25 == 0) { progress.update("Confronto con Hardcover · " + done + " di " + all.size()); progress.count(done, all.size()); }
            JSONObject ub = match(local, remote, matched);
            // Not recognized by title (Italian here, English there): the same Hardcover book through the ISBN.
            Integer viaIsbn = bookIdOf(local, byIsbn);
            JSONObject known = SyncCore.link(local, SERVICE);
            if (viaIsbn == null && known != null && known.optInt("id") == 0 && known.optInt("book") > 0) viaIsbn = known.optInt("book");
            if (viaIsbn != null) localBooks.add(viaIsbn);
            if (ub == null && viaIsbn != null && remoteByBook.containsKey(viaIsbn) && !matched.contains(remoteByBook.get(viaIsbn).optInt("id"))) ub = remoteByBook.get(viaIsbn);
            SyncCore.Snapshot here = SyncCore.Snapshot.of(local);
            Book copy = Book.from(local.json());
            if (ub == null) {
                // Books not yet on Hardcover are sent when they have a reading (or when the user asks for all of them).
                if (here.status.equals(Book.TO_READ) && !s.sendToRead) { report.onlyHere++; continue; }
                // Searched recently without result: not again at every sync (each search costs about a second).
                JSONObject old = SyncCore.link(local, SERVICE);
                if (old != null && old.optInt("id") == 0 && now - old.optLong("missing") < MISSING_RETRY) { report.onlyHere++; continue; }
                progress.update("Ricerca su Hardcover (" + done + " di " + all.size() + "): " + local.title);
                Integer bookId = viaIsbn != null ? viaIsbn : findBook(api, local, false);
                if (bookId != null) localBooks.add(bookId);
                // Already in the user's Hardcover library under another title: link it, never add it twice.
                JSONObject already = bookId == null ? null : remoteByBook.get(bookId);
                if (already != null && !matched.contains(already.optInt("id"))) {
                    matched.add(already.optInt("id")); report.linked++;
                    SyncCore.remember(copy, SERVICE, new JSONObject().put("id", already.optInt("id")).put("book", bookId), here);
                    keep(store, pending, local, copy); continue;
                }
                if (bookId == null) {
                    report.onlyHere++; report.notes.add("Non trovato su Hardcover: " + local.title);
                    SyncCore.remember(copy, SERVICE, new JSONObject().put("missing", now), here); keep(store, pending, local, copy);
                    continue;
                }
                JSONObject object = new JSONObject().put("book_id", bookId).put("status_id", statusId(here.status));
                if (here.rating > 0) object.put("rating", here.rating);
                JSONObject created = api.call("mutation($o:UserBookCreateInput!){ insert_user_book(object:$o) { id error } }", new JSONObject().put("o", object)).optJSONObject("insert_user_book");
                if (created == null || created.optInt("id") == 0) { report.notes.add("Hardcover non ha aggiunto «" + local.title + "»" + (created == null ? "" : ": " + created.optString("error"))); continue; }
                pushProgress(api, created.getInt("id"), here, null);
                SyncCore.remember(copy, SERVICE, new JSONObject().put("id", created.getInt("id")).put("book", bookId), here);
                keep(store, pending, local, copy); report.added++; continue;
            }
            matched.add(ub.getInt("id")); report.linked++; localBooks.add(ub.optInt("book_id"));
            SyncCore.Snapshot there = snapshot(ub);
            JSONObject link = SyncCore.link(local, SERVICE);
            SyncCore.Snapshot synced = link == null || link.optInt("id") != ub.getInt("id") ? null : SyncCore.Snapshot.from(link.optJSONObject("synced"));
            SyncCore.Action action = SyncCore.decide(here, there, synced, false, true);
            JSONObject newLink = new JSONObject().put("id", ub.getInt("id")).put("book", ub.optInt("book_id"));
            if (action == SyncCore.Action.PUSH) {
                progress.update("Invio a Hardcover: " + local.title);
                JSONObject object = new JSONObject().put("status_id", statusId(here.status));
                if (here.rating > 0) object.put("rating", here.rating);
                JSONObject updated = api.call("mutation($id:Int!,$o:UserBookUpdateInput!){ update_user_book(id:$id, object:$o) { id error } }",
                    new JSONObject().put("id", ub.getInt("id")).put("o", object)).optJSONObject("update_user_book");
                if (updated != null && !updated.optString("error").isEmpty() && !updated.isNull("error")) report.notes.add("«" + local.title + "»: " + updated.optString("error"));
                boolean statusChanged = statusId(here.status) != ub.optInt("status_id");
                pushProgress(api, ub.getInt("id"), here, statusChanged ? null : read(ub));
                SyncCore.remember(copy, SERVICE, newLink, here); report.pushed++;
            } else if (action == SyncCore.Action.PULL) {
                if (copy.totalPages == 0) copy.totalPages = pages(ub);
                SyncCore.apply(copy, there, false, true, today);
                SyncCore.remember(copy, SERVICE, newLink, SyncCore.Snapshot.of(copy)); report.pulled++;
            } else SyncCore.remember(copy, SERVICE, newLink, here);
            keep(store, pending, local, copy);
        }
        store.replaceAll(pending); pending.clear();
        if (s.createMissing) for (JSONObject ub : remote) {
            if (s.cancelled) { report.notes.add("Sincronizzazione interrotta."); return report; }
            if (matched.contains(ub.getInt("id"))) continue;
            // The same Hardcover book is already in Atlante (found through an ISBN): no second sheet.
            if (localBooks.contains(ub.optInt("book_id"))) { report.recognized++; continue; }
            Book b = sheet(ub);
            if (b == null || store.find(b.id) != null) continue;
            // Never a duplicate: a book already here (another edition, a different title form) is not added again.
            if (bestIdentity(store.snapshot(), ub) != null) { report.recognized++; continue; }
            // Same author, title only in Italian here and not among Hardcover's editions: the book here is looked up on
            // Hardcover (once: the answer is kept with it) and, when it is the same Hardcover book, it is linked.
            Book same = sameBookByAuthor(api, store, ub, matched, progress);
            if (same != null) {
                Book copy = Book.from(same.json());
                SyncCore.remember(copy, SERVICE, new JSONObject().put("id", ub.getInt("id")).put("book", ub.optInt("book_id")), SyncCore.Snapshot.of(copy));
                store.replace(copy); matched.add(ub.getInt("id")); report.recognized++; report.linked++; continue;
            }
            progress.update("Nuova scheda da Hardcover: " + b.title);
            SyncCore.apply(b, snapshot(ub), false, true, today);
            SyncCore.remember(b, SERVICE, new JSONObject().put("id", ub.getInt("id")).put("book", ub.optInt("book_id")), SyncCore.Snapshot.of(b));
            store.addCatalog(b); report.created++;
        }
        return report;
    }

    // ---- doubles left by earlier syncs ------------------------------------------------------------------

    /** A sheet made by an earlier sync from a Hardcover record (often English) and the book that was already here. */
    public static final class Duplicate {
        public final Book copy, original;
        /** Same Hardcover book through an ISBN, or same title and author as the record or one of its editions (not a title part). */
        public final boolean certain;
        Duplicate(Book copy, Book original, boolean certain) { this.copy = copy; this.original = original; this.certain = certain; }
    }

    /**
     * Sheets that an earlier sync created from a Hardcover record while the same book was already in Atlante
     * under another title ("The Remains of the Day" next to "Quel che resta del giorno"): same Hardcover book
     * through the ISBN, or the same title and author through one of its Italian editions. Only sheets without a
     * file and without notes or reading sessions of their own are proposed; nothing is changed here.
     */
    public static List<Duplicate> duplicates(SyncStore store, Settings s, Progress progress) throws Exception {
        String token = s.token.trim();
        return duplicates(store, s, progress, (query, variables) -> request(token, query, variables));
    }

    public static List<Duplicate> duplicates(SyncStore store, Settings s, Progress progress, Api api) throws Exception {
        List<JSONObject> remote = remote(api, s, progress, new Report());
        return remote == null ? new ArrayList<>() : duplicates(store, s, progress, api, remote);
    }

    static List<Duplicate> duplicates(SyncStore store, Settings s, Progress progress, Api api, List<JSONObject> remote) throws Exception {
        List<Duplicate> out = new ArrayList<>();
        Map<Integer, JSONObject> byId = new HashMap<>(); for (JSONObject ub : remote) byId.put(ub.optInt("id"), ub);
        List<Book> all = store.snapshot(); List<Book> copies = new ArrayList<>(); List<String> isbns = new ArrayList<>();
        for (Book b : all) {
            JSONObject l = SyncCore.link(b, SERVICE);
            if (l != null && byId.containsKey(l.optInt("id")) && onlySyncData(b)) copies.add(b);
            isbns.addAll(BookMatcher.isbns(b.isbn));
        }
        if (copies.isEmpty()) return out;
        Map<String, Integer> byIsbn = bookIds(api, isbns, s, progress);
        Set<String> used = new HashSet<>();
        progress.update("Confronto delle schede…");
        for (Book c : copies) {
            JSONObject ub = byId.get(SyncCore.link(c, SERVICE).optInt("id"));
            Book best = null; int top = 0; boolean sure = false;
            List<Book> whole = wholeIdentities(ub);
            for (Book o : all) {
                if (o == c || used.contains(o.id) || used.contains(c.id)) continue;
                JSONObject ol = SyncCore.link(o, SERVICE);
                if (ol != null && ol.optInt("id") > 0 && ol.optInt("id") != ub.optInt("id")) continue;   // linked to another Hardcover book
                Integer viaIsbn = bookIdOf(o, byIsbn);
                int score = viaIsbn != null && viaIsbn == ub.optInt("book_id") ? 200 : 0;
                int wholeScore = score;
                for (Book id : whole) wholeScore = Math.max(wholeScore, BookMatcher.score(o, id));
                for (Book id : identities(ub)) score = Math.max(score, BookMatcher.score(o, id));
                if (score > top) { top = score; best = o; sure = wholeScore >= BookMatcher.SAME_TITLE_AND_AUTHOR; }
            }
            // The one kept is never a newer bare sheet than the copy.
            if (best != null && top >= BookMatcher.SAME_TITLE_AND_AUTHOR && !(onlySyncData(best) && best.addedAt > c.addedAt)) {
                out.add(new Duplicate(c, best, sure)); used.add(c.id); used.add(best.id);
            }
        }
        return out;
    }

    /** A sheet with nothing of the user's own: no file, notes, sessions or highlights (only what Sync wrote). */
    static boolean onlySyncData(Book b) {
        if (!b.format.equals("CATALOGO") || !b.notes.isEmpty()) return false;
        Iterator<String> keys = b.journal.keys();
        while (keys.hasNext()) { String k = keys.next(); if (!k.equals("sync") && !k.equals("attempt")) return false; }
        return true;
    }

    /** Keeps the original: it takes the Hardcover link and the reading data it lacks; the copy is removed. */
    public static void merge(SyncStore store, List<Duplicate> pairs) throws Exception {
        for (Duplicate d : pairs) {
            Book o = store.find(d.original.id), c = store.find(d.copy.id);
            if (o == null || c == null) continue;
            Book kept = Book.from(o.json());
            if (kept.status.equals(Book.TO_READ) && !c.status.equals(Book.TO_READ)) kept.status = c.status;
            if (kept.currentPage == 0) kept.currentPage = c.currentPage;
            if (kept.totalPages == 0) kept.totalPages = c.totalPages;
            if (kept.percent == 0) kept.percent = c.percent;
            if (kept.rating == 0) kept.rating = c.rating;
            if (kept.started.isEmpty()) kept.started = c.started;
            if (kept.finished.isEmpty()) kept.finished = c.finished;
            if (kept.isbn.isEmpty()) kept.isbn = c.isbn;
            JSONObject sync = kept.journal.optJSONObject("sync"); if (sync == null) sync = new JSONObject();
            JSONObject theirs = c.journal.optJSONObject("sync");
            if (theirs != null && theirs.optJSONObject(SERVICE) != null) sync.put(SERVICE, theirs.getJSONObject(SERVICE));
            kept.journal.put("sync", sync);
            store.replace(kept);
            store.remove(c);
        }
    }

    /** Sets the page on the read in progress, starting one if the server did not. */
    private static void pushProgress(Api api, int userBookId, SyncCore.Snapshot here, JSONObject knownRead) throws Exception {
        if (here.relevantPage() <= 0) return;
        JSONObject read = knownRead;
        if (read == null) {
            JSONArray books = api.call("query($id:Int!){ user_books(where:{id:{_eq:$id}}) { user_book_reads(where:{finished_at:{_is_null:true}}, order_by:{id:desc}, limit:1) { id progress_pages } } }",
                new JSONObject().put("id", userBookId)).optJSONArray("user_books");
            JSONObject ub = books == null ? null : books.optJSONObject(0);
            read = ub == null ? null : read(ub);
        }
        JSONObject dates = new JSONObject().put("progress_pages", here.page);
        if (read != null) api.call("mutation($id:Int!,$o:DatesReadInput!){ update_user_book_read(id:$id, object:$o) { id error } }", new JSONObject().put("id", read.getInt("id")).put("o", dates));
        else api.call("mutation($id:Int!,$o:DatesReadInput!){ insert_user_book_read(user_book_id:$id, user_book_read:$o) { id error } }",
            new JSONObject().put("id", userBookId).put("o", dates.put("started_at", LocalDate.now().toString())));
    }

    /** Hardcover book id: by ISBN among editions, otherwise the first search result with the same title and an author in common. */
    public static Integer findBook(Api api, Book b) throws Exception { return findBook(api, b, true); }

    /** [byIsbn] false: the ISBN was already looked up in the batch, only the title search is left. */
    public static Integer findBook(Api api, Book b, boolean byIsbn) throws Exception {
        if (byIsbn && b.isbn.matches("97[89][0-9]{10}|[0-9]{9}[0-9Xx]")) {
            String field = b.isbn.length() == 13 ? "isbn_13" : "isbn_10";
            JSONArray editions = api.call("query($i:String!){ editions(where:{" + field + ":{_eq:$i}}, limit:1) { id book_id } }", new JSONObject().put("i", b.isbn)).optJSONArray("editions");
            if (editions != null && editions.optJSONObject(0) != null) return editions.getJSONObject(0).getInt("book_id");
        }
        String query = b.title + (b.author.equals("Autore sconosciuto") ? "" : " " + b.author);
        JSONObject search = api.call("query($q:String!){ search(query:$q, query_type:\"Book\", per_page:5, page:1) { results } }", new JSONObject().put("q", query)).optJSONObject("search");
        Object results = search == null ? null : search.opt("results");
        JSONObject parsed = results instanceof JSONObject ? (JSONObject) results : results instanceof String ? new JSONObject((String) results) : null;
        // Same title (also one of Hardcover's alternative titles, often the Italian one) and the same author.
        kotlin.Pair<Integer, Integer> hit0 = parsed == null ? null : app.atlante.library.saga.Hardcover.INSTANCE.pickHit(b, parsed);
        if (hit0 != null && hit0.getSecond() >= 88) return hit0.getFirst();
        JSONArray hits = parsed == null ? null : parsed.optJSONArray("hits");
        for (int i = 0; hits != null && i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i), doc = hit == null ? null : hit.optJSONObject("document");
            if (doc == null) continue;
            Book other = new Book(); other.title = doc.optString("title");
            JSONArray names = doc.optJSONArray("author_names"); List<String> a = new ArrayList<>();
            for (int j = 0; names != null && j < names.length(); j++) a.add(names.optString(j));
            other.author = String.join(" & ", a);
            if (OnlineCatalog.sameTitle(b.title, other.title) && (b.author.equals("Autore sconosciuto") || SbnCatalog.sameWork(b, other) || a.isEmpty()))
                return Integer.parseInt(doc.optString("id"));
        }
        return null;
    }

    /** Link saved earlier, otherwise the best record by ISBN or title and author, also through its Italian editions. */
    public static JSONObject match(Book local, List<JSONObject> remote, Set<Integer> taken) {
        JSONObject link = SyncCore.link(local, SERVICE);
        if (link != null && link.optInt("id") > 0) for (JSONObject ub : remote) if (ub.optInt("id") == link.optInt("id") && !taken.contains(ub.optInt("id"))) return ub;
        JSONObject found = null; int top = 0;
        for (JSONObject ub : remote) {
            if (taken.contains(ub.optInt("id"))) continue;
            int score = 0; for (Book id : identities(ub)) score = Math.max(score, BookMatcher.score(local, id));
            if (score > top) { top = score; found = ub; }
        }
        return top >= BookMatcher.SAME_TITLE_UNKNOWN_AUTHOR ? found : null;
    }

    /** The local book that is this Hardcover record (any of its titles and ISBNs), or null. */
    static Book bestIdentity(List<Book> books, JSONObject ub) {
        Book found = null; int top = 0;
        for (Book b : books) { int score = 0; for (Book id : identities(ub)) score = Math.max(score, BookMatcher.score(b, id)); if (score > top) { top = score; found = b; } }
        return top >= BookMatcher.SAME_TITLE_UNKNOWN_AUTHOR ? found : null;
    }

    /**
     * A book here, by the same author and not linked to Hardcover yet, that Hardcover knows as the book of [ub]
     * (searched by its own title, Hardcover's Italian alternative titles included). The answer is kept with the
     * book ("book" in its link), so each one is searched once.
     */
    static Book sameBookByAuthor(Api api, SyncStore store, JSONObject ub, Set<Integer> matched, Progress progress) throws Exception {
        Book identity = sheetIdentity(ub); if (identity == null) return null;
        Set<String> surnames = new HashSet<>(BookMatcher.surnames(identity.author));
        if (surnames.isEmpty()) return null;
        for (Book o : store.snapshot()) {
            JSONObject l = SyncCore.link(o, SERVICE);
            if (l != null && l.optInt("id") > 0) continue;
            boolean sameAuthor = false; for (String n : BookMatcher.surnames(o.author)) if (surnames.contains(n)) { sameAuthor = true; break; }
            if (!sameAuthor) continue;
            int id = l == null ? 0 : l.optInt("book");
            if (id == 0 && (l == null || l.optLong("checked") == 0)) {
                progress.update("Controllo su Hardcover: " + o.title);
                Integer found; try { found = findBook(api, o, true); } catch (IOException e) { continue; }
                id = found == null ? 0 : found;
                Book copy = Book.from(o.json());
                JSONObject link = l == null ? new JSONObject() : new JSONObject(l.toString());
                link.put("id", 0).put("book", id).put("checked", System.currentTimeMillis());
                JSONObject sync = copy.journal.optJSONObject("sync"); if (sync == null) sync = new JSONObject();
                sync.put(SERVICE, link); copy.journal.put("sync", sync);
                store.replace(copy);
            }
            if (id > 0 && id == ub.optInt("book_id")) return store.find(o.id);
        }
        return null;
    }

    /** Hardcover book id of a local book through the ISBNs looked up in the batch. */
    static Integer bookIdOf(Book b, Map<String, Integer> byIsbn) {
        for (String i : BookMatcher.isbns(b.isbn)) if (byIsbn.containsKey(i)) return byIsbn.get(i);
        return null;
    }

    /**
     * The record as it is on Hardcover (often the English edition) and as its Italian editions: "The Remains of the
     * Day" is also "Quel che resta del giorno", with that edition's ISBN.
     */
    static List<Book> identities(JSONObject ub) {
        List<Book> out = wholeIdentities(ub);
        // Titles led by the saga name: "Mistborn. L'ultimo impero", "Mistborn: The Final Empire" are also "L'ultimo impero"…
        List<Book> withParts = new ArrayList<>(out);
        for (Book b : out) for (String sep : new String[]{": ", ". ", " - "}) {
            int at = b.title.indexOf(sep);
            if (at > 0 && b.title.length() - at - sep.length() >= 4) {
                Book part = new Book(); part.author = b.author; part.isbn = b.isbn; part.title = b.title.substring(at + sep.length()).trim();
                withParts.add(part);
            }
        }
        return withParts;
    }

    /** The record and its Italian editions, with their whole titles. */
    static List<Book> wholeIdentities(JSONObject ub) {
        List<Book> out = new ArrayList<>();
        Book main = sheetIdentity(ub); if (main == null) return out;
        out.add(main);
        JSONObject book = ub.optJSONObject("book"); JSONArray eds = book == null ? null : book.optJSONArray("editions");
        for (int i = 0; eds != null && i < eds.length(); i++) {
            JSONObject e = eds.optJSONObject(i); if (e == null) continue;
            Book it = new Book(); it.author = main.author;
            it.title = app.atlante.library.saga.Hardcover.INSTANCE.italianTitle(e.optString("title", ""));
            if (it.title == null) it.title = e.optString("title", "");
            it.isbn = (e.isNull("isbn_13") ? "" : e.optString("isbn_13")) + " " + (e.isNull("isbn_10") ? "" : e.optString("isbn_10"));
            if (!it.title.isEmpty()) out.add(it);
        }
        return out;
    }

    public static SyncCore.Snapshot snapshot(JSONObject ub) {
        JSONObject read = read(ub);
        double rating = ub.isNull("rating") ? 0 : ub.optDouble("rating", 0);
        return new SyncCore.Snapshot(status(ub.optInt("status_id")), read == null || read.isNull("progress_pages") ? 0 : read.optInt("progress_pages"), "", rating);
    }

    public static String status(int id) { return id == 2 ? Book.READING : id == 3 ? Book.READ : id == 4 ? Book.PAUSED : Book.TO_READ; }
    public static int statusId(String status) { return status.equals(Book.READING) ? 2 : status.equals(Book.READ) ? 3 : status.equals(Book.PAUSED) ? 4 : 1; }

    private static JSONObject read(JSONObject ub) { JSONArray reads = ub.optJSONArray("user_book_reads"); return reads == null ? null : reads.optJSONObject(0); }
    private static int pages(JSONObject ub) {
        JSONObject e = ub.optJSONObject("edition"), b = ub.optJSONObject("book");
        int p = e == null || e.isNull("pages") ? 0 : e.optInt("pages");
        return p > 0 ? p : b == null || b.isNull("pages") ? 0 : b.optInt("pages");
    }

    private static Book sheetIdentity(JSONObject ub) {
        JSONObject book = ub.optJSONObject("book"); if (book == null) return null;
        Book b = new Book(); b.title = book.optString("title");
        JSONArray c = book.optJSONArray("contributions"); List<String> names = new ArrayList<>();
        for (int i = 0; c != null && i < c.length(); i++) { JSONObject entry = c.optJSONObject(i), a = entry == null ? null : entry.optJSONObject("author"); if (a != null) names.add(a.optString("name")); }
        b.author = names.isEmpty() ? "Autore sconosciuto" : String.join(" & ", names);
        JSONObject e = ub.optJSONObject("edition");
        if (e != null) b.isbn = !e.optString("isbn_13").isEmpty() && !e.isNull("isbn_13") ? e.optString("isbn_13") : e.isNull("isbn_10") ? "" : e.optString("isbn_10");
        return b;
    }

    public static Book sheet(JSONObject ub) throws Exception {
        Book id = sheetIdentity(ub); if (id == null || id.title.isEmpty()) return null;
        Map<String, String> f = new HashMap<>();
        // A new sheet gets the Italian title when Hardcover has an Italian edition.
        JSONObject bk = ub.optJSONObject("book"); JSONArray eds = bk == null ? null : bk.optJSONArray("editions");
        JSONObject it = eds == null ? null : eds.optJSONObject(0);
        String italian = it == null ? null : app.atlante.library.saga.Hardcover.INSTANCE.italianTitle(it.optString("title", ""));
        f.put("title", italian != null && !italian.isEmpty() ? italian : id.title); f.put("author", id.author); f.put("isbn", id.isbn); f.put("pages", Integer.toString(pages(ub)));
        return CatalogImport.fromFields(f);
    }

    /** Hardcover's own reason, so that the user (and we) can see what it said. */
    private static String detail(SyncCore.Response r) {
        String body = r.body == null ? "" : r.body.trim();
        try { JSONObject j = new JSONObject(body); String d = j.optString("error_description", j.optString("error", j.optString("message"))); if (!d.isEmpty()) return ". Risposta di Hardcover: " + d; }
        catch (Exception ignored) { }
        return body.isEmpty() ? "." : ". Risposta di Hardcover: " + body.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim().substring(0, Math.min(160, body.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim().length()));
    }

    /**
     * One GraphQL call with the user's personal key (with or without "Bearer "). Shared by Sync and the saga
     * engine, so that together they stay under Hardcover's limit of 60 requests a minute.
     */
    public static JSONObject request(String token, String query, JSONObject variables) throws Exception {
        String t = token == null ? "" : token.trim();
        if (t.isEmpty()) throw new IOException("Manca la chiave personale di Hardcover.");
        String auth = t.regionMatches(true, 0, "Bearer ", 0, 7) ? t : "Bearer " + t;
        synchronized (HardcoverSync.class) {   // Hardcover allows 60 requests a minute.
            long wait = nextRequest - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            nextRequest = System.currentTimeMillis() + 1100;
        }
        Map<String, String> headers = new HashMap<>(); headers.put("authorization", auth);
        SyncCore.Response r = SyncCore.http("POST", ENDPOINT, new JSONObject().put("query", query).put("variables", variables).toString(), headers, null, null, 8 * 1024 * 1024);
        if (r.status == 401) throw new IOException("Hardcover non accetta questa chiave (non valida, scaduta o sostituita da una nuova). Copia la chiave personale da hardcover.app/account/api" + detail(r));
        if (r.status == 403) throw new IOException("Hardcover ha rifiutato la richiesta (403). Usa la chiave personale di hardcover.app/account/api, non quella di un'applicazione" + detail(r));
        if (r.status == 429) throw new IOException("Hardcover chiede di rallentare: riprova tra qualche minuto.");
        if (r.status != 200) throw new IOException("Hardcover ha risposto con codice " + r.status + ".");
        JSONObject body = new JSONObject(r.body);
        JSONArray errors = body.optJSONArray("errors");
        if (errors != null && errors.length() > 0) {
            Object first = errors.opt(0);
            throw new IOException("Hardcover: " + (first instanceof JSONObject ? ((JSONObject) first).optString("message") : String.valueOf(first)));
        }
        return body.optJSONObject("data") == null ? new JSONObject() : body.getJSONObject("data");
    }
}
