package app.atlante.library;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.net.Socket;
import org.junit.Test;

/** Runs only when a test Calibre server is listening on 127.0.0.1:8099 (user lettore/segreta); skipped on CI. */
public class CalibreLiveTest {
    @Test public void syncsStatePageAndChapterBothWays() throws Exception {
        boolean up; try (Socket s = new Socket("127.0.0.1", 8099)) { up = true; } catch (Exception e) { up = false; }
        assumeTrue(up);
        SyncTest.MemoryStore store = new SyncTest.MemoryStore();
        Book rosa = SyncTest.book("Il nome della rosa", "Umberto Eco", "9788845292613", Book.READING, 200, 500);
        ReadingJournal.put(rosa, "chapter", "Quarto giorno"); store.books.add(rosa);
        Book nona = SyncTest.book("La nona casa", "Leigh Bardugo", "", Book.TO_READ, 0, 0); store.books.add(nona);
        CalibreSync.Settings s = new CalibreSync.Settings(); s.url = "http://127.0.0.1:8099"; s.user = "lettore"; s.password = "segreta";
        // Known starting point in Calibre: behind the phone, so the first sync sends the phone's reading.
        assertEquals(200, SyncCore.http("POST", "http://127.0.0.1:8099/cdb/set-fields/1/lib",
            "{\"changes\":{\"#stato\":\"In lettura\",\"#pagina\":142,\"#capitolo\":\"Terzo giorno\"},\"loaded_book_ids\":[1]}", null, "lettore", "segreta", 1 << 20).status);
        CalibreSync.Report r = CalibreSync.sync(store, s, m -> { });
        System.out.println("CALIBRE remote=" + r.remote + " linked=" + r.linked + " pushed=" + r.pushed + " pulled=" + r.pulled + " created=" + r.created + " notes=" + r.notes);
        assertEquals(3, r.remote); assertEquals(2, r.linked); assertEquals(1, r.created); assertEquals(1, r.pushed);
        assertTrue(store.books.stream().anyMatch(b -> b.title.equals("Libro solo in Calibre")));
        // The other direction: a change made in Calibre reaches Atlante on the next sync.
        SyncCore.Response w = SyncCore.http("POST", "http://127.0.0.1:8099/cdb/set-fields/1/lib",
            "{\"changes\":{\"#stato\":\"In pausa\",\"#pagina\":260,\"#capitolo\":\"Quinto giorno\"},\"loaded_book_ids\":[1]}", null, "lettore", "segreta", 1 << 20);
        assertEquals(200, w.status);
        CalibreSync.Report second = CalibreSync.sync(store, s, m -> { });
        Book after = store.find(rosa.id);
        System.out.println("CALIBRE second pulled=" + second.pulled + " status=" + after.status + " page=" + after.currentPage + " chapter=" + after.journal.optString("chapter"));
        assertEquals(1, second.pulled); assertEquals(0, second.pushed); assertEquals(0, second.created);
        assertEquals(Book.PAUSED, after.status); assertEquals(260, after.currentPage); assertEquals("Quinto giorno", after.journal.optString("chapter"));
    }
}
