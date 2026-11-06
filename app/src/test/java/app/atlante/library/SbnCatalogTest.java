package app.atlante.library;

import app.atlante.library.model.Book;
import app.atlante.library.online.SbnCatalog;

import static org.junit.Assert.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/** Uses real OPAC SBN responses saved on 2026-11-06. */
public class SbnCatalogTest {
    private String resource(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/sbn/" + name)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test public void editionsOfTheSameWorkBecomeOneResultWithTheLatestIsbn() throws Exception {
        List<Book> books = SbnCatalog.parseSearch(resource("otto-montagne.json"));
        Book cognetti = books.stream().filter(b -> b.author.equals("Paolo Cognetti")).findFirst().orElseThrow(AssertionError::new);
        assertEquals("Le otto montagne", cognetti.title);
        assertEquals(1, books.stream().filter(b -> b.author.equals("Paolo Cognetti") && b.title.equals("Le otto montagne")).count());
        assertTrue(cognetti.isbn.matches("97888[0-9]{8}")); assertEquals("Einaudi", cognetti.publisher);
        assertEquals("CATALOGO", cognetti.format); assertEquals("ita", cognetti.language);
        assertTrue(cognetti.source.startsWith("sbn:IT\\ICCU\\")); assertTrue(cognetti.id.matches("[0-9a-f]{64}"));
    }

    @Test public void onlyPrintedBooksAreKept() throws Exception {
        for (Book b : SbnCatalog.parseSearch(resource("harry-potter.json")))
            assertFalse(b.publisher, b.publisher.contains("Electronic Arts") || b.publisher.contains("Mattel") || b.publisher.contains("Warner"));
        assertTrue(SbnCatalog.parseSearch(resource("harry-potter.json")).stream().anyMatch(b -> b.title.equals("Harry Potter e la pietra filosofale")));
    }

    @Test public void fullRecordGivesPagesAndLanguage() throws Exception {
        Book b = new Book(); SbnCatalog.enrichFrom(b, new JSONObject(resource("portalettere-full.json")));
        assertEquals(416, b.totalPages); assertEquals("ita", b.language); assertEquals("9788842937876", b.isbn);
    }

    @Test public void librarianNamesAndDuplicatesAcrossCatalogs() {
        assertEquals("Francesca Giannone", SbnCatalog.person("Giannone, Francesca <1982-    >"));
        Book a = new Book(); a.title = "L'amica geniale"; a.author = "Elena Ferrante";
        Book b = new Book(); b.title = "L’amica  geniale"; b.author = "Ferrante, Elena";
        assertTrue(SbnCatalog.sameWork(a, b));
        b.author = "Altro Autore"; assertFalse(SbnCatalog.sameWork(a, b));
        b.isbn = "9788876418957"; a.isbn = "9788876418957"; assertTrue(SbnCatalog.sameWork(a, b));
    }
}
