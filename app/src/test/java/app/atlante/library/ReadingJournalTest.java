package app.atlante.library;

import app.atlante.library.model.Book;
import app.atlante.library.model.LibraryQuery;
import app.atlante.library.model.ReadingJournal;

import org.junit.Test;
import org.json.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.Assert.*;

public class ReadingJournalTest {
    private Book book() throws Exception {
        return Book.from(new JSONObject().put("id",String.join("",Collections.nCopies(64,"a"))).put("format","EPUB")
            .put("page",17).put("currentPage",83).put("totalPages",240).put("notes","legacy note")
            .put("futureMetadata",new JSONObject().put("keep",true)));
    }
    @Test public void additiveJournalRetainsExistingAndUnknownFields() throws Exception {
        Book b=book();ReadingJournal.put(b,"rating",4.5);
        ReadingJournal.put(b,"review",new JSONObject().put("body","Private review"));
        ReadingJournal.put(b,"notes",new JSONArray().put(new JSONObject().put("body","Quote").put("page",21)));
        Book c=Book.from(b.json());
        assertEquals(17,c.page);assertEquals(83,c.currentPage);assertEquals("legacy note",c.notes);
        assertTrue(c.json().getJSONObject("futureMetadata").getBoolean("keep"));
        assertEquals(4.5,ReadingJournal.rating(c),0.0);assertEquals("Private review",c.journal.getJSONObject("review").getString("body"));
        c.title="Edited title";Book d=Book.from(c.json());assertEquals(c.journal.toString(),d.journal.toString());
    }
    @Test public void sessionsDoNotCountBackwardPageCorrectionsAsReading() throws Exception {
        Book b=book();LocalDate day=LocalDate.of(2026,11,6);
        ReadingJournal.session(b,100,80,120,day);ReadingJournal.session(b,80,100,600,day);
        ReadingJournal.session(b,100,110,60,day.minusDays(1));
        assertArrayEquals(new long[]{20,720},ReadingJournal.totals(Arrays.asList(b),day));
        assertArrayEquals(new long[]{30,780},ReadingJournal.totals(Arrays.asList(b),null));
        assertEquals(2,ReadingJournal.streak(Arrays.asList(b),day));
        assertEquals(2,ReadingJournal.streak(Arrays.asList(b),day.plusDays(1)));
        assertEquals(0,ReadingJournal.streak(Arrays.asList(b),day.plusDays(2)));
    }
    @Test public void rereadingCountsArchivedCompletionOnceAndKeepsSessions() throws Exception {
        Book b=book();b.finished="2026-11-05";
        ReadingJournal.put(b,"attempts",new JSONArray().put(new JSONObject().put("finished","2026-11-05"))
            .put(new JSONObject().put("finished","2026-10-01")));
        assertEquals(2,ReadingJournal.completed(Arrays.asList(b),LocalDate.of(2026,11,6),false));
        assertEquals(1,ReadingJournal.completed(Arrays.asList(b),LocalDate.of(2026,11,6),true));
        assertEquals(1,ReadingJournal.attempt(b));ReadingJournal.put(b,"attempt",2);
        ReadingJournal.session(b,0,20,600,LocalDate.of(2026,11,6));
        assertEquals(2,ReadingJournal.array(b,"sessions").getJSONObject(0).getInt("attempt"));
    }
    @Test public void collectionsFavoritesAndStructuredNotesAreSearchable() throws Exception {
        Book b=book();ReadingJournal.put(b,"collections",new JSONArray().put("Viaggi"));
        ReadingJournal.put(b,"favorite",true);ReadingJournal.put(b,"notes",new JSONArray().put(new JSONObject().put("body","Filosofia")));
        assertEquals(1,LibraryQuery.select(Arrays.asList(b),"Filosofia","Scaffale: Viaggi","","","Titolo").size());
        assertEquals(1,LibraryQuery.select(Arrays.asList(b),"","Preferiti","","","Titolo").size());
        assertEquals(0,LibraryQuery.select(Arrays.asList(b),"","Scaffale: Altro","","","Titolo").size());
        b.status=Book.PAUSED;assertEquals(1,LibraryQuery.select(Arrays.asList(b),"",Book.PAUSED,"","","Titolo").size());
    }
}
