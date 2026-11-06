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

    public static final class Settings { public String token = ""; public boolean createMissing = true, sendToRead = false; }
    public static final class Report {
        public String user = ""; public int remote, linked, pushed, pulled, created, added, onlyHere, recognized;
        public final List<String> notes = new ArrayList<>();
    }
    public interface Progress { void update(String message); }
    public interface Api { JSONObject call(String query, JSONObject variables) throws Exception; }

    public static Report sync(SyncStore store, Settings s, Progress progress) throws Exception {
        String token = s.token.trim();
        if (token.isEmpty()) throw new IOException("Incolla prima la chiave personale di Hardcover.");
        return sync(store, s, progress, (query, variables) -> request(token, query, variables));
    }

    /** The API is injected so that the merge can be tested without a network. */
    public static Report sync(SyncStore store, Settings s, Progress progress, Api api) throws Exception {
        Report report = new Report();
        progress.update("Collegamento a Hardcover…");
        Object me = api.call("query { me { id username } }", new JSONObject()).opt("me");
        JSONObject user = me instanceof JSONArray ? ((JSONArray) me).optJSONObject(0) : (JSONObject) me;
        if (user == null) throw new IOException("Hardcover non ha riconosciuto la chiave.");
        int userId = user.getInt("id"); report.user = user.optString("username");

        List<JSONObject> remote = new ArrayList<>();
        for (int offset = 0; ; offset += 100) {
            progress.update("Lettura della libreria Hardcover · " + remote.size() + " libri");
            JSONArray page = api.call("query($id:Int!,$offset:Int!){ user_books(where:{user_id:{_eq:$id}}, order_by:{id:asc}, limit:100, offset:$offset) {"
                + " id book_id status_id rating edition { id pages isbn_13 isbn_10 }"
                + " book { id title pages image { url } contributions { author { name } } }"
                + " user_book_reads(where:{finished_at:{_is_null:true}}, order_by:{id:desc}, limit:1) { id progress_pages } } }",
                new JSONObject().put("id", userId).put("offset", offset)).optJSONArray("user_books");
            if (page == null) break;
            for (int i = 0; i < page.length(); i++) { JSONObject ub = page.optJSONObject(i); if (ub != null && ub.optInt("status_id") != 6) remote.add(ub); }
            if (page.length() < 100) break;
        }
        report.remote = remote.size();

        String today = LocalDate.now().toString();
        Set<Integer> matched = new HashSet<>();
        for (Book local : store.snapshot()) {
            JSONObject ub = match(local, remote, matched);
            SyncCore.Snapshot here = SyncCore.Snapshot.of(local);
            Book copy = Book.from(local.json());
            if (ub == null) {
                // Books not yet on Hardcover are sent when they have a reading (or when the user asks for all of them).
                if (here.status.equals(Book.TO_READ) && !s.sendToRead) { report.onlyHere++; continue; }
                progress.update("Ricerca su Hardcover: " + local.title);
                Integer bookId = findBook(api, local);
                if (bookId == null) { report.onlyHere++; report.notes.add("Non trovato su Hardcover: " + local.title); continue; }
                JSONObject object = new JSONObject().put("book_id", bookId).put("status_id", statusId(here.status));
                if (here.rating > 0) object.put("rating", here.rating);
                JSONObject created = api.call("mutation($o:UserBookCreateInput!){ insert_user_book(object:$o) { id error } }", new JSONObject().put("o", object)).optJSONObject("insert_user_book");
                if (created == null || created.optInt("id") == 0) { report.notes.add("Hardcover non ha aggiunto «" + local.title + "»" + (created == null ? "" : ": " + created.optString("error"))); continue; }
                pushProgress(api, created.getInt("id"), here, null);
                SyncCore.remember(copy, SERVICE, new JSONObject().put("id", created.getInt("id")).put("book", bookId), here);
                store.replace(copy); report.added++; continue;
            }
            matched.add(ub.getInt("id")); report.linked++;
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
            store.replace(copy);
        }
        if (s.createMissing) for (JSONObject ub : remote) {
            if (matched.contains(ub.getInt("id"))) continue;
            Book b = sheet(ub);
            if (b == null || store.find(b.id) != null) continue;
            // Never a duplicate: a book already here (another edition, a different title form) is not added again.
            if (BookMatcher.best(b, store.snapshot(), x -> x) != null) { report.recognized++; continue; }
            progress.update("Nuova scheda da Hardcover: " + b.title);
            SyncCore.apply(b, snapshot(ub), false, true, today);
            SyncCore.remember(b, SERVICE, new JSONObject().put("id", ub.getInt("id")).put("book", ub.optInt("book_id")), SyncCore.Snapshot.of(b));
            store.addCatalog(b); report.created++;
        }
        return report;
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
    public static Integer findBook(Api api, Book b) throws Exception {
        if (b.isbn.matches("97[89][0-9]{10}|[0-9]{9}[0-9Xx]")) {
            String field = b.isbn.length() == 13 ? "isbn_13" : "isbn_10";
            JSONArray editions = api.call("query($i:String!){ editions(where:{" + field + ":{_eq:$i}}, limit:1) { id book_id } }", new JSONObject().put("i", b.isbn)).optJSONArray("editions");
            if (editions != null && editions.optJSONObject(0) != null) return editions.getJSONObject(0).getInt("book_id");
        }
        String query = b.title + (b.author.equals("Autore sconosciuto") ? "" : " " + b.author);
        JSONObject search = api.call("query($q:String!){ search(query:$q, query_type:\"Book\", per_page:5, page:1) { results } }", new JSONObject().put("q", query)).optJSONObject("search");
        Object results = search == null ? null : search.opt("results");
        JSONObject parsed = results instanceof JSONObject ? (JSONObject) results : results instanceof String ? new JSONObject((String) results) : null;
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

    /** Link saved earlier, otherwise the best record by ISBN or title and author (see BookMatcher). */
    public static JSONObject match(Book local, List<JSONObject> remote, Set<Integer> taken) {
        JSONObject link = SyncCore.link(local, SERVICE);
        if (link != null) for (JSONObject ub : remote) if (ub.optInt("id") == link.optInt("id") && !taken.contains(ub.optInt("id"))) return ub;
        List<JSONObject> free = new ArrayList<>();
        for (JSONObject ub : remote) if (!taken.contains(ub.optInt("id"))) free.add(ub);
        return BookMatcher.best(local, free, ub -> { Book b = sheetIdentity(ub); return b == null ? new Book() : b; });
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
        f.put("title", id.title); f.put("author", id.author); f.put("isbn", id.isbn); f.put("pages", Integer.toString(pages(ub)));
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
