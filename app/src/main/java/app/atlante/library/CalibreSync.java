package app.atlante.library;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Calibre content server: reads the library and syncs reading state, page and chapter through three custom
 * columns (by default #stato, #pagina, #capitolo). Writing needs a Calibre user with write permission.
 */
final class CalibreSync {
    static final String SERVICE = "calibre";

    static final class Settings {
        String url = "", user = "", password = "", library = "";
        String statusColumn = "#stato", pageColumn = "#pagina", chapterColumn = "#capitolo";
        boolean createMissing = true;
    }

    static final class Report {
        int remote, linked, pushed, pulled, created, unchanged, onlyHere;
        final List<String> notes = new ArrayList<>();
        String library = "";
    }

    interface Progress { void update(String message); }

    static Report sync(SyncStore store, Settings s, Progress progress) throws Exception {
        String base = s.url.trim().replaceAll("/+$", "");
        if (!base.matches("(?i)https?://[^\\s/]+(:[0-9]+)?(/.*)?")) throw new IOException("Indirizzo del server Calibre non valido, per esempio http://192.168.1.10:8080");
        Report report = new Report();
        progress.update("Collegamento a Calibre…");
        JSONObject info = json(get(s, base + "/ajax/library-info"));
        String library = s.library.isEmpty() ? info.optString("default_library") : s.library;
        report.library = library;
        String lib = URLEncoder.encode(library, "UTF-8");
        JSONArray ids = json(get(s, base + "/ajax/search/" + lib + "?query=&num=100000")).optJSONArray("book_ids");
        Map<Integer, JSONObject> remote = new LinkedHashMap<>();
        for (int i = 0; ids != null && i < ids.length(); i += 100) {
            StringBuilder chunk = new StringBuilder();
            for (int j = i; j < Math.min(ids.length(), i + 100); j++) chunk.append(j > i ? "," : "").append(ids.getInt(j));
            progress.update("Lettura della biblioteca Calibre · " + Math.min(ids.length(), i + 100) + " di " + ids.length());
            JSONObject books = json(get(s, base + "/ajax/books/" + lib + "?ids=" + chunk));
            for (Iterator<String> k = books.keys(); k.hasNext(); ) { String key = k.next(); JSONObject b = books.optJSONObject(key); if (b != null) remote.put(Integer.parseInt(key), b); }
        }
        report.remote = remote.size();
        Set<String> columns = new HashSet<>();
        for (JSONObject b : remote.values()) { JSONObject meta = b.optJSONObject("user_metadata"); if (meta != null) for (Iterator<String> k = meta.keys(); k.hasNext(); ) columns.add(k.next()); break; }
        boolean hasStatus = columns.contains(s.statusColumn), hasPage = columns.contains(s.pageColumn), hasChapter = columns.contains(s.chapterColumn);
        if (!remote.isEmpty() && !hasStatus) report.notes.add("In Calibre manca la colonna " + s.statusColumn + " (testo): senza, lo stato non viene condiviso.");
        if (!remote.isEmpty() && !hasPage) report.notes.add("In Calibre manca la colonna " + s.pageColumn + " (numero intero).");
        if (!remote.isEmpty() && !hasChapter) report.notes.add("In Calibre manca la colonna " + s.chapterColumn + " (testo).");

        String today = LocalDate.now().toString();
        Set<Integer> matched = new HashSet<>();
        for (Book local : store.snapshot()) {
            Integer id = match(local, remote, matched);
            if (id == null) { report.onlyHere++; continue; }
            matched.add(id); report.linked++;
            JSONObject r = remote.get(id);
            SyncCore.Snapshot there = snapshot(r, s, hasStatus, hasPage, hasChapter), here = SyncCore.Snapshot.of(local);
            JSONObject link = SyncCore.link(local, SERVICE);
            SyncCore.Snapshot synced = link == null || link.optInt("id") != id ? null : SyncCore.Snapshot.from(link.optJSONObject("synced"));
            if (!hasStatus) { there.status = here.status; }
            SyncCore.Action action = SyncCore.decide(here, there, synced, hasChapter, false);
            Book copy = Book.from(local.json());
            JSONObject newLink = new JSONObject().put("id", id).put("library", library);
            if (action == SyncCore.Action.PUSH) {
                JSONObject changes = new JSONObject();
                if (hasStatus) changes.put(s.statusColumn, here.status);
                if (hasPage) changes.put(s.pageColumn, here.relevantPage() > 0 ? here.page : here.status.equals(Book.READ) && local.totalPages > 0 ? local.totalPages : JSONObject.NULL);
                if (hasChapter) changes.put(s.chapterColumn, here.chapter.isEmpty() ? JSONObject.NULL : here.chapter);
                if (changes.length() > 0) {
                    progress.update("Invio a Calibre: " + local.title);
                    SyncCore.Response w = SyncCore.http("POST", base + "/cdb/set-fields/" + id + "/" + lib,
                        new JSONObject().put("changes", changes).put("loaded_book_ids", new JSONArray().put(id)).toString(), null, s.user, s.password, 4 * 1024 * 1024);
                    if (w.status == 401 || w.status == 403) throw new IOException("Calibre non permette di scrivere: usa un utente con permesso di modifica (vedi la guida nella sezione Sync).");
                    if (w.status != 200) throw new IOException("Calibre ha risposto " + w.status + " durante l’aggiornamento di «" + local.title + "».");
                }
                report.pushed++; SyncCore.remember(copy, SERVICE, newLink, here);
            } else if (action == SyncCore.Action.PULL) {
                SyncCore.apply(copy, there, hasChapter, false, today);
                report.pulled++; SyncCore.remember(copy, SERVICE, newLink, SyncCore.Snapshot.of(copy));
            } else { report.unchanged++; SyncCore.remember(copy, SERVICE, newLink, here); }
            store.replace(copy);
        }
        if (s.createMissing) {
            for (Map.Entry<Integer, JSONObject> e : remote.entrySet()) {
                if (matched.contains(e.getKey())) continue;
                progress.update("Nuova scheda da Calibre: " + e.getValue().optString("title"));
                Book b = sheet(e.getValue());
                SyncCore.Snapshot there = snapshot(e.getValue(), s, hasStatus, hasPage, hasChapter);
                SyncCore.apply(b, there, hasChapter, false, today);
                SyncCore.remember(b, SERVICE, new JSONObject().put("id", e.getKey()).put("library", library), SyncCore.Snapshot.of(b));
                try { b.onlineCover = bytes(s, base + "/get/thumb/" + e.getKey() + "/" + lib + "?sz=400x600"); }
                catch (Exception ignored) { /* The sheet works without a cover. */ }
                if (store.find(b.id) == null) { store.addCatalog(b); report.created++; }
            }
        }
        return report;
    }

    /** Link saved earlier, then ISBN, then same title with an author in common. */
    static Integer match(Book local, Map<Integer, JSONObject> remote, Set<Integer> taken) {
        JSONObject link = SyncCore.link(local, SERVICE);
        if (link != null && remote.containsKey(link.optInt("id")) && !taken.contains(link.optInt("id"))) return link.optInt("id");
        for (Map.Entry<Integer, JSONObject> e : remote.entrySet()) {
            if (taken.contains(e.getKey())) continue;
            JSONObject ident = e.getValue().optJSONObject("identifiers");
            String isbn = ident == null ? "" : ident.optString("isbn").replaceAll("[^0-9Xx]", "");
            if (!local.isbn.isEmpty() && local.isbn.equalsIgnoreCase(isbn)) return e.getKey();
        }
        for (Map.Entry<Integer, JSONObject> e : remote.entrySet()) {
            if (taken.contains(e.getKey())) continue;
            Book other = new Book(); other.title = e.getValue().optString("title"); other.author = authors(e.getValue());
            if (SbnCatalog.sameWork(local, other)) return e.getKey();
        }
        return null;
    }

    static SyncCore.Snapshot snapshot(JSONObject book, Settings s, boolean status, boolean page, boolean chapter) {
        JSONObject meta = book.optJSONObject("user_metadata");
        String st = status ? value(meta, s.statusColumn) : "";
        String pg = page ? value(meta, s.pageColumn) : "";
        int number = 0; try { if (!pg.isEmpty()) number = (int) Double.parseDouble(pg); } catch (NumberFormatException ignored) { }
        return new SyncCore.Snapshot(CatalogImport.status(st), number, chapter ? value(meta, s.chapterColumn) : "", 0);
    }

    private static String value(JSONObject meta, String column) {
        JSONObject c = meta == null ? null : meta.optJSONObject(column);
        Object v = c == null ? null : c.opt("#value#");
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v).trim();
    }

    static Book sheet(JSONObject r) throws Exception {
        Map<String, String> f = new HashMap<>();
        f.put("title", r.optString("title")); f.put("author", authors(r));
        JSONObject ident = r.optJSONObject("identifiers"); if (ident != null) f.put("isbn", ident.optString("isbn"));
        if (!r.isNull("series")) f.put("series", r.optString("series"));
        if (!r.isNull("series_index")) f.put("number", r.optString("series_index"));
        if (!r.isNull("publisher")) f.put("publisher", r.optString("publisher"));
        f.put("year", r.optString("pubdate")); f.put("description", r.optString("comments", ""));
        JSONArray tags = r.optJSONArray("tags"); if (tags != null) { List<String> t = new ArrayList<>(); for (int i = 0; i < tags.length(); i++) t.add(tags.optString(i)); f.put("tags", String.join(", ", t)); }
        JSONArray langs = r.optJSONArray("languages"); if (langs != null && langs.length() > 0) f.put("language", langs.optString(0));
        Book b = CatalogImport.fromFields(f);
        if (b == null) throw new IOException("Scheda Calibre senza titolo");
        JSONArray formats = r.optJSONArray("formats"); if (formats != null && formats.length() > 0) b.medium = "Ebook";
        return b;
    }

    static String authors(JSONObject r) {
        JSONArray a = r.optJSONArray("authors");
        if (a == null || a.length() == 0) return "Autore sconosciuto";
        List<String> names = new ArrayList<>(); for (int i = 0; i < a.length(); i++) names.add(a.optString(i));
        return String.join(" & ", names);
    }

    private static String get(Settings s, String url) throws IOException {
        SyncCore.Response r = SyncCore.http("GET", url, null, null, s.user, s.password, 16 * 1024 * 1024);
        if (r.status == 401) throw new IOException("Calibre chiede nome utente e password: controlla le credenziali.");
        if (r.status == 403) throw new IOException("Calibre ha rifiutato l’accesso (403).");
        if (r.status != 200) throw new IOException("Calibre ha risposto con codice " + r.status + ".");
        return r.body;
    }

    /** Binary download (covers) with the same challenge handling as {@link SyncCore#http}. */
    private static byte[] bytes(Settings s, String url) throws IOException {
        String authorization = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(12000); c.setReadTimeout(30000);
            if (authorization != null) c.setRequestProperty("Authorization", authorization);
            try {
                int status = c.getResponseCode();
                if (status == 401 && authorization == null && !s.user.isEmpty()) { authorization = SyncCore.answer(c.getHeaderField("WWW-Authenticate"), "GET", url, s.user, s.password); continue; }
                if (status != 200) return null;
                try (java.io.InputStream in = c.getInputStream()) { return EbookParser.read(in, 3 * 1024 * 1024); }
            } finally { c.disconnect(); }
        }
        return null;
    }

    private static JSONObject json(String body) throws IOException {
        try { return new JSONObject(body); } catch (org.json.JSONException e) { throw new IOException("Risposta di Calibre non valida"); }
    }
}
