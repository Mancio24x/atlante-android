package app.atlante.library;

import app.atlante.library.model.Book;
import app.atlante.library.model.BookMatcher;

import static org.junit.Assert.*;
import org.junit.Test;

public class BookMatcherTest {
    static Book b(String title, String author, String isbn) { Book x = new Book(); x.title = title; x.author = author; x.isbn = isbn; return x; }

    @Test public void recognizesTheSameBookWrittenDifferently() {
        assertTrue(BookMatcher.same(b("Camus Albert - 1987 - Lo straniero", "Camus Albert", ""), b("Lo straniero", "Albert Camus", "")));
        assertTrue(BookMatcher.same(b("Il colibrì : romanzo", "Sandro Veronesi", ""), b("Il colibrì", "Veronesi, Sandro", "")));
        assertTrue(BookMatcher.same(b("L’amica geniale", "Elena Ferrante", ""), b("L'amica geniale", "Ferrante Elena", "")));
        assertTrue(BookMatcher.same(b("Hell Bent - Portale per l'inferno", "Leigh Bardugo", ""), b("Hell Bent", "Leigh Bardugo", "")));
        assertTrue(BookMatcher.same(b("Qualsiasi", "X", "88-452-9261-X"), b("Altro titolo", "Y", "9788845292613")));   // ISBN-10 = ISBN-13
        assertTrue(BookMatcher.same(b("A", "B", "9788806219352, 9788806241513"), b("C", "D", "9788806241513"))); // several ISBNs in a field
        assertTrue(BookMatcher.same(b("Il nome della rosa", "Autore sconosciuto", ""), b("Il nome della rosa", "Umberto Eco", "")));
        assertTrue(BookMatcher.same(b("Ballata dell'usignolo e del serpente", "Suzanne Collins", ""), b("Hunger Games - Ballata dell'usignolo e del serpente: Un romanzo della saga di Hunger Games (Italian Edition)", "Suzanne Collins", "")));
        assertTrue(BookMatcher.same(b("Manuale di Sopravvivenza Bushcraft e Tecniche", "David J. Harvey", ""), b("Manuale di Sopravvivenza: Bushcraft e Tecniche (Italian Edition)", "David J. Harvey", "")));
        assertTrue(BookMatcher.same(b("Chess Patterns", "Rodolfo Pardi, Evelyne Nicod", ""), b("Chess Patterns, una scelta di schemi fondamentali di scacchi", "Pardi, Rodolfo", "")));
        assertTrue(BookMatcher.same(b("Gilgamesh", "Anonimo", ""), b("Gilgamesh. Il poema epico babilonese (Z-Library)", "Unknown", "")));
    }

    @Test public void keepsDifferentBooksApart() {
        assertFalse(BookMatcher.same(b("La casa", "Michael McDowell", ""), b("La casa degli spiriti", "Isabel Allende", "")));
        assertFalse(BookMatcher.same(b("La casa", "Michael McDowell", ""), b("La casa", "Altro Scrittore", "")));        // same title, other author
        assertFalse(BookMatcher.same(b("Harry Potter - Volume 1", "J.K. Rowling", ""), b("Harry Potter - Volume 2", "J.K. Rowling", "")));
        assertFalse(BookMatcher.same(b("Camus Albert - 1987 - Lo straniero", "Camus Albert", ""), b("Camus Albert - 1990 - La peste", "Camus Albert", "")));
        assertFalse(BookMatcher.same(b("La casa", "Autore sconosciuto", ""), b("La casa", "Michael McDowell", "")));      // too generic without author
        assertFalse(BookMatcher.same(b("Hunger Games - Ballata dell'usignolo e del serpente", "Suzanne Collins", ""), b("HUNGER GAMES - Il canto della rivolta", "Suzanne Collins", "")));
        assertFalse(BookMatcher.same(b("Red Rising - Il canto proibito", "Pierce Brown", ""), b("Red Rising. La prima trilogia", "Pierce Brown", "")));
        assertTrue(BookMatcher.same(b("Collins Suzanne - 2009 - Hunger Games", "Collins Suzanne", ""), b("Hunger Games", "Suzanne Collins", "")));
        Book one = b("Mistborn", "Brandon Sanderson", ""), two = b("Mistborn", "Brandon Sanderson", "");
        one.series = "Mistborn"; one.number = 1; two.series = "Mistborn"; two.number = 2;
        assertFalse(BookMatcher.same(one, two));
    }

    @Test public void isbnTenBecomesThirteen() {
        assertEquals("9788845292613", BookMatcher.to13("884529261X"));
        assertTrue(BookMatcher.isbns("ISBN 88-452-9261-X").contains("9788845292613"));
    }
}
