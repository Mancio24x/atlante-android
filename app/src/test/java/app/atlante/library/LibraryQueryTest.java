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

    @Test public void normalizedGenreAndSagaFilters() {
        Book a = book("L'ultimo impero", "Letto", "EPUB"); a.genre = "Epic Fantasy"; a.series = "Mistborn";
        Book b = book("Eragon", "Da leggere", "EPUB"); b.genre = "Fantasy per ragazzi"; b.series = "Ciclo dell'Eredità";
        Book c = book("Il nome della rosa", "Letto", "CATALOGO"); c.genre = "Giallo storico";
        Book d = book("Senza genere", "Letto", "TXT"); d.genre = "Varie";
        java.util.List<Book> all = Arrays.asList(a, b, c, d);
        assertEquals(Arrays.asList(b, a), LibraryQuery.select(all, "", "Tutti i libri", "Fantasy", "", "Titolo", ""));
        assertEquals(Arrays.asList(c), LibraryQuery.select(all, "", "Tutti i libri", "Giallo", "", "Titolo", ""));
        assertEquals(Arrays.asList(d), LibraryQuery.select(all, "", "Tutti i libri", LibraryQuery.OTHER_GENRE, "", "Titolo", ""));
        assertEquals(Arrays.asList(a), LibraryQuery.select(all, "", "Tutti i libri", "Epic Fantasy", "", "Titolo", ""));   // a raw label still works
        assertEquals(Arrays.asList(b, a), LibraryQuery.select(all, "", "Tutti i libri", "", "", "Titolo", LibraryQuery.IN_SAGA));
        assertEquals(Arrays.asList(c, d), LibraryQuery.select(all, "", "Tutti i libri", "", "", "Titolo", LibraryQuery.SINGLE));
        assertEquals(Arrays.asList(a), LibraryQuery.select(all, "", "Tutti i libri", "", "", "Titolo", "mistborn"));
    }
}
