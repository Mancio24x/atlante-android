package app.atlante.library;

import app.atlante.library.model.Book;
import app.atlante.library.storage.CatalogImport;
import app.atlante.library.sync.HardcoverSync;
import app.atlante.library.sync.KindleClippings;
import app.atlante.library.model.ReadingJournal;
import app.atlante.library.sync.SyncCore;
import app.atlante.library.storage.SyncStore;

import static org.junit.Assert.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SyncTest {
    static final class MemoryStore implements SyncStore {
        final ArrayList<Book> books = new ArrayList<>();
        public List<Book> snapshot() { return new ArrayList<>(books); }
        public Book find(String id) { for (Book b : books) if (b.id.equals(id)) return b; return null; }
        public void replace(Book u) { for (int i = 0; i < books.size(); i++) if (books.get(i).id.equals(u.id)) books.set(i, u); }
        public Book addCatalog(Book b) { books.add(b); return b; }
    }
    static Book book(String title, String author, String isbn, String status, int page, int total) throws Exception {
        Map<String, String> f = new HashMap<>(); f.put("title", title); f.put("author", author); f.put("isbn", isbn);
        Book b = CatalogImport.fromFields(f); b.status = status; b.currentPage = page; b.totalPages = total; return b;
    }

    @Test public void mergeRule() {
        SyncCore.Snapshot synced = new SyncCore.Snapshot(Book.READING, 50, "", 0);
        SyncCore.Snapshot phone = new SyncCore.Snapshot(Book.READING, 80, "", 0), same = new SyncCore.Snapshot(Book.READING, 50, "", 0);
        assertEquals(SyncCore.Action.PUSH, SyncCore.decide(phone, same, synced, false, false));      // only the phone changed
        assertEquals(SyncCore.Action.PULL, SyncCore.decide(same, phone, synced, false, false));      // only the service changed
        assertEquals(SyncCore.Action.PUSH, SyncCore.decide(phone, new SyncCore.Snapshot(Book.READ, 0, "", 0), synced, false, false)); // both: phone wins
        assertEquals(SyncCore.Action.NONE, SyncCore.decide(same, same, synced, false, false));
        // First link: the more advanced reading wins; an empty side always receives.
        assertEquals(SyncCore.Action.PULL, SyncCore.decide(new SyncCore.Snapshot(Book.READING, 10, "", 0), new SyncCore.Snapshot(Book.READ, 0, "", 0), null, false, false));
        assertEquals(SyncCore.Action.PUSH, SyncCore.decide(new SyncCore.Snapshot(Book.READING, 10, "", 0), new SyncCore.Snapshot(), null, false, false));
        // A finished book has no "current page": different pages do not count as a change.
        assertEquals(SyncCore.Action.NONE, SyncCore.decide(new SyncCore.Snapshot(Book.READ, 300, "", 0), new SyncCore.Snapshot(Book.READ, 0, "", 0), null, false, false));
        // Chapters only count where the service has them.
        SyncCore.Snapshot ch = new SyncCore.Snapshot(Book.READING, 50, "Capitolo 3", 0);
        assertEquals(SyncCore.Action.NONE, SyncCore.decide(ch, same, synced, false, false));
        assertEquals(SyncCore.Action.PUSH, SyncCore.decide(ch, same, synced, true, false));
    }

    @Test public void kindleClippingsInItalianAndEnglish() {
        String text = "﻿Il nome della rosa (Eco, Umberto)\r\n- La tua evidenziazione a pagina 12 | posizione 180-182 | Aggiunto in data lunedì 1 gennaio 2024 10:00:00\r\n\r\nStat rosa pristina nomine.\r\n==========\r\n"
            + "Il nome della rosa (Eco, Umberto)\r\n- Il tuo segnalibro a pagina 20 | posizione 300 | Aggiunto in data lunedì 1 gennaio 2024\r\n\r\n\r\n==========\r\n"
            + "Dune (Frank Herbert)\r\n- Your Note on page 5 | Location 70 | Added on Monday, January 1, 2024 10:00:00 AM\r\n\r\nFear is the mind-killer.\r\n==========\r\n";
        List<KindleClippings.Clip> clips = KindleClippings.parse(text);
        assertEquals(2, clips.size());
        assertEquals("Il nome della rosa", clips.get(0).title); assertEquals("Umberto Eco", clips.get(0).author);
        assertEquals("Citazione", clips.get(0).type); assertEquals(12, clips.get(0).page); assertEquals(180, clips.get(0).location);
        assertEquals("Nota", clips.get(1).type); assertEquals("Frank Herbert", clips.get(1).author); assertEquals("Fear is the mind-killer.", clips.get(1).text);
        Book b = new Book(); assertEquals(1, KindleClippings.addTo(b, clips.subList(0, 1), "2026-11-06"));
        assertEquals(0, KindleClippings.addTo(b, clips.subList(0, 1), "2026-11-06")); // never twice
    }

    /** A Hardcover API that answers like the real one (schema of the official documentation). */
    static final class FakeHardcover implements HardcoverSync.Api {
        final List<String> mutations = new ArrayList<>();
        JSONArray userBooks;
        public JSONObject call(String q, JSONObject v) throws Exception {
            if (q.contains("me {")) return new JSONObject().put("me", new JSONArray().put(new JSONObject().put("id", 7).put("username", "lettrice")));
            if (q.contains("user_books(where:{user_id")) return new JSONObject().put("user_books", v.getInt("offset") == 0 ? userBooks : new JSONArray());
            if (q.contains("isbn_13:{_in")) { JSONArray found = new JSONArray(); JSONArray asked = v.getJSONArray("i");
                for (int i = 0; i < asked.length(); i++) if (asked.getString(i).equals("9788835700777")) found.put(new JSONObject().put("isbn_13", "9788835700777").put("book_id", 555).put("book", new JSONObject().put("canonical_id", JSONObject.NULL)));
                return new JSONObject().put("editions", found); }
            if (q.contains("editions(where")) return new JSONObject().put("editions", v.getString("i").equals("9788835700777") ? new JSONArray().put(new JSONObject().put("id", 1).put("book_id", 555)) : new JSONArray());
            if (q.contains("search(")) return new JSONObject().put("search", new JSONObject().put("results", new JSONObject().put("hits", new JSONArray())));
            if (q.contains("user_books(where:{id")) return new JSONObject().put("user_books", new JSONArray().put(new JSONObject().put("user_book_reads", new JSONArray().put(new JSONObject().put("id", 900)))));
            mutations.add(q.substring(q.indexOf('{') + 1).trim().split("\\(")[0] + " " + v);
            if (q.contains("insert_user_book(")) return new JSONObject().put("insert_user_book", new JSONObject().put("id", 4242));
            return new JSONObject().put("x", new JSONObject());
        }
    }
    static JSONObject userBook(int id, int status, Integer page, String title, String author, String isbn, double rating) throws Exception {
        JSONObject ub = new JSONObject().put("id", id).put("book_id", id * 10).put("status_id", status).put("rating", rating == 0 ? JSONObject.NULL : rating)
            .put("edition", new JSONObject().put("pages", 300).put("isbn_13", isbn == null ? JSONObject.NULL : isbn).put("isbn_10", JSONObject.NULL))
            .put("book", new JSONObject().put("title", title).put("pages", 300).put("contributions", new JSONArray().put(new JSONObject().put("author", new JSONObject().put("name", author)))));
        ub.put("user_book_reads", page == null ? new JSONArray() : new JSONArray().put(new JSONObject().put("id", id + 1000).put("progress_pages", page)));
        return ub;
    }

    @Test public void hardcoverPushesPullsAndCreates() throws Exception {
        MemoryStore store = new MemoryStore();
        Book rosa = book("Il nome della rosa", "Umberto Eco", "9788845292613", Book.READING, 120, 500); store.books.add(rosa);
        Book dune = book("Dune", "Frank Herbert", "", Book.TO_READ, 0, 0); store.books.add(dune);
        Book nona = book("La nona casa", "Leigh Bardugo", "9788835700777", Book.READ, 0, 400); store.books.add(nona);
        FakeHardcover api = new FakeHardcover();
        api.userBooks = new JSONArray()
            .put(userBook(1, 2, 80, "Il nome della rosa", "Umberto Eco", "9788845292613", 0))   // phone is further on: push page
            .put(userBook(2, 2, 40, "Dune", "Frank Herbert", null, 4.5))                          // phone empty: pull state, page, rating
            .put(userBook(3, 3, null, "Solo su Hardcover", "Autrice", null, 0))                  // new sheet in Atlante
            .put(userBook(4, 6, null, "Ignorato", "X", null, 0));                                 // ignored: skipped
        HardcoverSync.Settings s = new HardcoverSync.Settings();
        HardcoverSync.Report r = HardcoverSync.sync(store, s, m -> { }, api);
        assertEquals("lettrice", r.user); assertEquals(3, r.remote); assertEquals(2, r.linked);
        assertEquals(1, r.pushed); assertEquals(1, r.pulled); assertEquals(1, r.created); assertEquals(1, r.added);
        assertTrue(api.mutations.toString(), api.mutations.stream().anyMatch(m -> m.startsWith("update_user_book_read") && m.contains("\"progress_pages\":120")));
        assertTrue(api.mutations.stream().anyMatch(m -> m.startsWith("insert_user_book ") && m.contains("\"book_id\":555") && m.contains("\"status_id\":3")));
        Book d = store.find(dune.id);
        assertEquals(Book.READING, d.status); assertEquals(40, d.currentPage); assertEquals(4.5, ReadingJournal.rating(d), 0); assertEquals(300, d.totalPages);
        assertTrue(store.books.stream().anyMatch(b -> b.title.equals("Solo su Hardcover") && b.status.equals(Book.READ)));
        assertFalse(store.books.stream().anyMatch(b -> b.title.equals("Ignorato")));
        // Second run: nothing changed on either side, nothing is sent again.
        api.mutations.clear();
        api.userBooks = new JSONArray().put(userBook(1, 2, 120, "Il nome della rosa", "Umberto Eco", "9788845292613", 0)).put(userBook(2, 2, 40, "Dune", "Frank Herbert", null, 4.5))
            .put(userBook(3, 3, null, "Solo su Hardcover", "Autrice", null, 0)).put(userBook(4242, 3, null, "La nona casa", "Leigh Bardugo", "9788835700777", 0));
        HardcoverSync.Report again = HardcoverSync.sync(store, s, m -> { }, api);
        assertEquals(0, again.pushed); assertEquals(0, again.pulled); assertEquals(0, again.created); assertTrue(api.mutations.toString(), api.mutations.isEmpty());
    }

    @Test public void statusMapping() {
        assertEquals(Book.PAUSED, HardcoverSync.status(4)); assertEquals(Book.TO_READ, HardcoverSync.status(5));
        assertEquals(4, HardcoverSync.statusId(Book.PAUSED)); assertEquals(1, HardcoverSync.statusId(Book.TO_READ));
    }

    @Test public void digestFollowsRfc() {
        String h = SyncCore.digest("Digest realm=\"calibre\", nonce=\"abc\", qop=\"auth\", algorithm=MD5", "GET", "/ajax/library-info", "u", "p");
        assertTrue(h.startsWith("Digest username=\"u\", realm=\"calibre\", nonce=\"abc\", uri=\"/ajax/library-info\", response=\""));
        assertTrue(h.contains("qop=auth, nc=00000001"));
        assertEquals("5d41402abc4b2a76b9719d911017c592", SyncCore.md5("hello"));
    }
}
