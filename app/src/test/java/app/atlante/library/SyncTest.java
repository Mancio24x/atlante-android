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
        @Override public void remove(Book b) { books.removeIf(x -> x.id.equals(b.id)); }
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
        final Map<String, Integer> isbnBooks = new HashMap<>();
        /** Search answers: a word of the query → hits. */
        final Map<String, JSONArray> searchHits = new HashMap<>();
        int searches;
        JSONArray userBooks;
        public JSONObject call(String q, JSONObject v) throws Exception {
            if (q.contains("me {")) return new JSONObject().put("me", new JSONArray().put(new JSONObject().put("id", 7).put("username", "lettrice")));
            if (q.contains("user_books(where:{user_id")) return new JSONObject().put("user_books", v.getInt("offset") == 0 ? userBooks : new JSONArray());
            if (q.contains("isbn_13:{_in")) { JSONArray found = new JSONArray(); JSONArray asked = v.getJSONArray("i");
                for (int i = 0; i < asked.length(); i++) {
                    Integer id = asked.getString(i).equals("9788835700777") ? Integer.valueOf(555) : isbnBooks.get(asked.getString(i));
                    if (id != null) found.put(new JSONObject().put("isbn_13", asked.getString(i)).put("book_id", id).put("book", new JSONObject().put("canonical_id", JSONObject.NULL)));
                }
                return new JSONObject().put("editions", found); }
            if (q.contains("editions(where")) return new JSONObject().put("editions", v.getString("i").equals("9788835700777") ? new JSONArray().put(new JSONObject().put("id", 1).put("book_id", 555)) : new JSONArray());
            if (q.contains("search(")) { searches++; JSONArray hits = new JSONArray();
                for (Map.Entry<String, JSONArray> e : searchHits.entrySet()) if (v.getString("q").contains(e.getKey())) hits = e.getValue();
                return new JSONObject().put("search", new JSONObject().put("results", new JSONObject().put("hits", hits))); }
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

    /** A Hardcover record as it often is: English edition, with its Italian editions listed. */
    static JSONObject withItalian(JSONObject ub, String title, String isbn) throws Exception {
        ub.getJSONObject("book").put("editions", new JSONArray().put(new JSONObject().put("title", title).put("isbn_13", isbn == null ? JSONObject.NULL : isbn).put("isbn_10", JSONObject.NULL)));
        return ub;
    }

    /** Caso 1 + 4: Italian book here, English on Hardcover; synced twice: one book, linked, never a second sheet. */
    @Test public void englishHardcoverRecordIsTheItalianBookHere() throws Exception {
        MemoryStore store = new MemoryStore();
        Book impero = book("L'ultimo impero", "Brandon Sanderson", "", Book.TO_READ, 0, 0); store.books.add(impero);
        Book giorno = book("Quel che resta del giorno", "Kazuo Ishiguro", "9788806219352", Book.READ, 0, 0); store.books.add(giorno);
        FakeHardcover api = new FakeHardcover();
        api.isbnBooks.put("9788806219352", 90);                                             // the Italian ISBN is that Hardcover book
        api.userBooks = new JSONArray()
            .put(withItalian(userBook(5, 3, null, "Mistborn: The Final Empire", "Brandon Sanderson", "9780765311788", 0), "Mistborn. L'ultimo impero", "9788804748809"))
            .put(userBook(9, 3, null, "The Remains of the Day", "Kazuo Ishiguro", "9780679731726", 0));   // book_id 90, no Italian edition listed
        HardcoverSync.Settings s = new HardcoverSync.Settings();
        for (int run = 0; run < 2; run++) {
            HardcoverSync.Report r = HardcoverSync.sync(store, s, m -> { }, api);
            assertEquals("run " + run, 0, r.created);
            assertEquals(2, store.books.size());
            assertEquals(2, r.linked);
        }
        assertEquals(5, SyncCore.link(store.find(impero.id), HardcoverSync.SERVICE).optInt("id"));
        assertEquals(9, SyncCore.link(store.find(giorno.id), HardcoverSync.SERVICE).optInt("id"));
        assertEquals("L'ultimo impero", store.find(impero.id).title);                      // the Italian edition stays
        assertEquals(Book.READ, store.find(impero.id).status);                              // the reading state came over
        assertTrue(api.mutations.stream().noneMatch(m -> m.startsWith("insert_user_book ")));  // nothing added twice on Hardcover
    }

    /** Caso 2: same title, different authors: two works. */
    @Test public void sameTitleOtherAuthorIsAnotherBook() throws Exception {
        MemoryStore store = new MemoryStore();
        store.books.add(book("La casa", "Michael McDowell", "", Book.TO_READ, 0, 0));
        FakeHardcover api = new FakeHardcover();
        api.userBooks = new JSONArray().put(userBook(3, 3, null, "La casa", "Mario Rossi", null, 0));
        assertEquals(1, HardcoverSync.sync(store, new HardcoverSync.Settings(), m -> { }, api).created);
        assertEquals(2, store.books.size());
    }

    /** Ripara duplicati: the English sheet made by an old sync is merged into the Italian book, which keeps its data. */
    @Test public void oldEnglishCopyIsProposedAndMerged() throws Exception {
        MemoryStore store = new MemoryStore();
        Book original = book("Quel che resta del giorno", "Kazuo Ishiguro", "", Book.TO_READ, 0, 0); original.notes = "Bellissimo"; original.addedAt = 1;
        Book copy = book("The Remains of the Day", "Kazuo Ishiguro", "9780679731726", Book.READ, 0, 245); copy.addedAt = 2; copy.finished = "2026-08-29";
        SyncCore.remember(copy, HardcoverSync.SERVICE, new JSONObject().put("id", 9).put("book", 90), SyncCore.Snapshot.of(copy));
        Book other = book("La casa", "Michael McDowell", "", Book.TO_READ, 0, 0);
        store.books.add(original); store.books.add(copy); store.books.add(other);
        FakeHardcover api = new FakeHardcover();
        api.userBooks = new JSONArray().put(withItalian(userBook(9, 3, null, "The Remains of the Day", "Kazuo Ishiguro", "9780679731726", 0), "Quel che resta del giorno", "9788806219352"));
        List<HardcoverSync.Duplicate> found = HardcoverSync.duplicates(store, new HardcoverSync.Settings(), m -> { }, api);
        assertEquals(1, found.size());
        assertEquals(copy.id, found.get(0).copy.id); assertEquals(original.id, found.get(0).original.id);
        HardcoverSync.merge(store, found);
        assertEquals(2, store.books.size());
        Book kept = store.find(original.id);
        assertEquals("Quel che resta del giorno", kept.title); assertEquals("Bellissimo", kept.notes);
        assertEquals(Book.READ, kept.status); assertEquals("2026-08-29", kept.finished); assertEquals(245, kept.totalPages);
        assertEquals(9, SyncCore.link(kept, HardcoverSync.SERVICE).optInt("id"));
        assertNotNull(store.find(other.id));
        // Nothing left to propose afterwards.
        assertTrue(HardcoverSync.duplicates(store, new HardcoverSync.Settings(), m -> { }, api).isEmpty());
    }

    /** Nothing to repair by hand: the next sync merges the certain double by itself and keeps the user's book. */
    @Test public void syncMergesOldCopiesByItself() throws Exception {
        MemoryStore store = new MemoryStore();
        Book original = book("Quel che resta del giorno", "Kazuo Ishiguro", "", Book.TO_READ, 0, 0); original.notes = "Bellissimo"; original.addedAt = 1;
        Book copy = book("The Remains of the Day", "Kazuo Ishiguro", "9780679731726", Book.READ, 0, 245); copy.addedAt = 2; copy.finished = "2026-08-29";
        SyncCore.remember(copy, HardcoverSync.SERVICE, new JSONObject().put("id", 9).put("book", 90), SyncCore.Snapshot.of(copy));
        store.books.add(original); store.books.add(copy);
        FakeHardcover api = new FakeHardcover();
        api.userBooks = new JSONArray().put(withItalian(userBook(9, 3, null, "The Remains of the Day", "Kazuo Ishiguro", "9780679731726", 0), "Quel che resta del giorno", "9788806219352"));
        HardcoverSync.Report r = HardcoverSync.sync(store, new HardcoverSync.Settings(), m -> { }, api);
        assertEquals(1, r.merged); assertEquals(1, store.books.size()); assertEquals(0, r.created);
        Book kept = store.books.get(0);
        assertEquals("Quel che resta del giorno", kept.title); assertEquals("Bellissimo", kept.notes); assertEquals(Book.READ, kept.status);
        assertEquals(9, SyncCore.link(kept, HardcoverSync.SERVICE).optInt("id"));
        HardcoverSync.Report again = HardcoverSync.sync(store, new HardcoverSync.Settings(), m -> { }, api);
        assertEquals(0, again.merged); assertEquals(0, again.created); assertEquals(1, store.books.size());
    }

    /**
     * The Hardcover record lists no Italian edition and the book here has no ISBN: the Italian title is one of
     * Hardcover's alternative titles. The book here is looked up once and linked; no English sheet appears.
     */
    @Test public void italianTitleOnlyAmongAlternativeTitlesIsNotDoubled() throws Exception {
        MemoryStore store = new MemoryStore();
        Book mine = book("Il buio oltre la siepe", "Harper Lee", "", Book.TO_READ, 0, 0); store.books.add(mine);
        Book other = book("Va', metti una sentinella", "Harper Lee", "", Book.TO_READ, 0, 0); store.books.add(other);
        FakeHardcover api = new FakeHardcover();
        api.userBooks = new JSONArray().put(userBook(5, 3, null, "To Kill a Mockingbird", "Harper Lee", "9780061120084", 0));
        api.searchHits.put("buio", new JSONArray().put(new JSONObject().put("document", new JSONObject().put("id", "50").put("title", "To Kill a Mockingbird")
            .put("alternative_titles", new JSONArray().put("Il buio oltre la siepe")).put("author_names", new JSONArray().put("Harper Lee")).put("users_count", 9000))));
        HardcoverSync.Report r = HardcoverSync.sync(store, new HardcoverSync.Settings(), m -> { }, api);
        assertEquals(0, r.created); assertEquals(2, store.books.size());
        assertEquals(5, SyncCore.link(store.find(mine.id), HardcoverSync.SERVICE).optInt("id"));
        JSONObject otherLink = SyncCore.link(store.find(other.id), HardcoverSync.SERVICE);
        assertTrue(otherLink == null || otherLink.optInt("id") == 0);
        int searches = api.searches;
        HardcoverSync.Report again = HardcoverSync.sync(store, new HardcoverSync.Settings(), m -> { }, api);
        assertEquals(0, again.created); assertEquals(2, store.books.size());
        assertEquals("each book is searched once", searches, api.searches);
    }

    /** "Sono libri diversi" is remembered: the pair is not proposed again and the next sync does not merge it. */
    @Test public void differentBooksDecisionIsRemembered() throws Exception {
        MemoryStore store = new MemoryStore();
        Book original = book("Quel che resta del giorno", "Kazuo Ishiguro", "", Book.TO_READ, 0, 0); original.addedAt = 1;
        Book copy = book("The Remains of the Day", "Kazuo Ishiguro", "9780679731726", Book.READ, 0, 245); copy.addedAt = 2;
        SyncCore.remember(copy, HardcoverSync.SERVICE, new JSONObject().put("id", 9).put("book", 90), SyncCore.Snapshot.of(copy));
        store.books.add(original); store.books.add(copy);
        FakeHardcover api = new FakeHardcover();
        api.userBooks = new JSONArray().put(withItalian(userBook(9, 3, null, "The Remains of the Day", "Kazuo Ishiguro", "9780679731726", 0), "Quel che resta del giorno", "9788806219352"));
        List<HardcoverSync.Duplicate> found = HardcoverSync.duplicates(store, new HardcoverSync.Settings(), m -> { }, api);
        assertEquals(1, found.size());
        HardcoverSync.markDifferent(store, found);
        assertTrue(HardcoverSync.duplicates(store, new HardcoverSync.Settings(), m -> { }, api).isEmpty());
        HardcoverSync.Report r = HardcoverSync.sync(store, new HardcoverSync.Settings(), m -> { }, api);
        assertEquals(0, r.merged); assertEquals(2, store.books.size()); assertEquals(0, r.created);
    }

    /** Case 8: changed here and on Hardcover differently. Nothing is overwritten; your choice is applied. */
    @Test public void conflictsAreShownAndYourChoiceWins() throws Exception {
        MemoryStore store = new MemoryStore();
        Book b = book("Dune", "Frank Herbert", "", Book.READ, 0, 500);
        SyncCore.remember(b, HardcoverSync.SERVICE, new JSONObject().put("id", 2).put("book", 20), new SyncCore.Snapshot(Book.TO_READ, 0, "", 0));
        store.books.add(b);
        FakeHardcover api = new FakeHardcover();
        api.userBooks = new JSONArray().put(userBook(2, 2, 80, "Dune", "Frank Herbert", null, 0));     // Hardcover: in lettura, pagina 80
        HardcoverSync.Report r = HardcoverSync.sync(store, new HardcoverSync.Settings(), m -> { }, api);
        assertEquals(1, r.conflicts.size()); assertEquals(0, r.pushed); assertEquals(0, r.pulled);
        assertTrue(api.mutations.toString(), api.mutations.isEmpty());
        assertEquals(Book.READ, store.find(b.id).status);
        // Keep Hardcover: applied here, nothing left to decide.
        HardcoverSync.resolve(store, r.conflicts.get(0), false);
        assertEquals(Book.READING, store.find(b.id).status); assertEquals(80, store.find(b.id).currentPage);
        HardcoverSync.Report again = HardcoverSync.sync(store, new HardcoverSync.Settings(), m -> { }, api);
        assertTrue(again.conflicts.isEmpty()); assertEquals(0, again.pushed); assertEquals(0, again.pulled);
        // Keep Atlante instead: the next sync sends this phone's state.
        Book local = Book.from(store.find(b.id).json()); local.status = Book.READ; store.replace(local);
        api.userBooks = new JSONArray().put(userBook(2, 2, 120, "Dune", "Frank Herbert", null, 0));
        HardcoverSync.Report third = HardcoverSync.sync(store, new HardcoverSync.Settings(), m -> { }, api);
        assertEquals(1, third.conflicts.size());
        HardcoverSync.resolve(store, third.conflicts.get(0), true);
        api.mutations.clear();
        HardcoverSync.Report fourth = HardcoverSync.sync(store, new HardcoverSync.Settings(), m -> { }, api);
        assertEquals(1, fourth.pushed); assertTrue(fourth.conflicts.isEmpty());
        assertEquals(Book.READ, store.find(b.id).status);
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
