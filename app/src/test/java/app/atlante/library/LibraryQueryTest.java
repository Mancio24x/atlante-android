package app.atlante.library;

import app.atlante.library.model.Book;
import app.atlante.library.model.LibraryQuery;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class LibraryQueryTest {
    private Book book(String title, String status, String format) {
        Book b = new Book(); b.title = title; b.status = status; b.format = format; b.genre = "Fantasy"; return b;
    }
    @Test public void combinesFiltersAndSearchesIsbnWithoutMutatingInput() {
        Book a = book("Libro 10", "In lettura", "EPUB"); a.isbn = "9781234567890";
        Book b = book("Libro 2", "Letto", "CATALOGO");
        assertEquals(Arrays.asList(a), LibraryQuery.select(Arrays.asList(a,b), "978123", "In lettura", "fantasy", "EPUB", "Titolo"));
        assertEquals(Arrays.asList(b), LibraryQuery.select(Arrays.asList(a,b), "", "Letti", "", "", "Titolo"));
        assertTrue(LibraryQuery.select(Arrays.asList(a,b), "", "Da leggere", "", "", "Titolo").isEmpty());
        assertEquals(Arrays.asList(b,a), LibraryQuery.select(Arrays.asList(a,b), "", "Tutti i libri", "", "", "Titolo"));
        assertEquals("Libro 10", a.title); assertEquals("In lettura", a.status);
    }
    @Test public void seriesOrderUsesVolumeNumberAndPlacesStandaloneLast() {
        Book a = book("Volume finale", "Da leggere", "EPUB"); a.series = "Saga"; a.number = 10;
        Book b = book("Volume iniziale", "Da leggere", "EPUB"); b.series = "Saga"; b.number = 2;
        Book c = book("Autonomo", "Da leggere", "TXT");
        assertEquals(Arrays.asList(b,a,c), LibraryQuery.select(Arrays.asList(c,a,b), "", "Tutti i libri", "", "", "Saga"));
    }
}
