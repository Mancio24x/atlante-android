package app.atlante.library;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class CatalogTest {
    @Test public void sameWorkHasStableIdentityAndRejectsExternalKeys() throws Exception {
        String json = "{\"docs\":[{\"key\":\"/works/OL1W\",\"title\":\"Test\",\"author_name\":[\"Autore\"]},{\"key\":\"https://evil.invalid\",\"title\":\"Bad\"}]}";
        assertEquals(1, OnlineCatalog.parseSearch(json).size());
        Book a = OnlineCatalog.parseSearch(json).get(0), b = OnlineCatalog.parseSearch(json).get(0);
        assertEquals(a.id, b.id); assertTrue(a.id.matches("[0-9a-f]{64}"));
        assertEquals("CATALOGO", a.format);
    }
    @Test public void oldCatalogMigratesWithoutLosingReaderPosition() throws Exception {
        Book b = Book.from(new JSONObject().put("id", new String(new char[64]).replace('\0','a')).put("format", "EPUB").put("page", 17));
        assertEquals(17, b.page); assertEquals(0, b.currentPage); assertEquals("", b.notes);
    }
    @Test public void catalogAndProgressRoundTripIndependentlyFromReader() throws Exception {
        Book b = OnlineCatalog.parseSearch("{\"docs\":[{\"key\":\"OL2W\",\"title\":\"Test\"}]}").get(0);
        b.page = 7; b.currentPage = 40; b.totalPages = 200; b.notes = "Una nota";
        Book copy = Book.from(b.json()); assertEquals(40, copy.currentPage); assertEquals(200, copy.totalPages);
        assertEquals(7, copy.page); assertEquals("Una nota", copy.notes); assertEquals("/works/OL2W", copy.source);
    }
    @Test public void descriptionSupportsObjectAndString() throws Exception {
        Book b = new Book(); OnlineCatalog.enrichFrom(b, "{\"description\":{\"value\":\"Trama\"}}");
        assertEquals("Trama", b.description); OnlineCatalog.enrichFrom(b, "{\"description\":\"Altra trama\"}");
        assertEquals("Altra trama", b.description);
    }
    @Test public void italianEditionFieldsComeFromOneEdition() throws Exception {
        Book b = new Book(); OnlineCatalog.enrichEdition(b, new JSONObject("{\"entries\":[{\"title\":\"English\"},{\"key\":\"/books/OL3M\",\"title\":\"Italiano\",\"languages\":[{\"key\":\"/languages/ita\"}],\"publishers\":[\"Editore\"],\"isbn_13\":[\"123\"],\"number_of_pages\":300}]}"));
        assertEquals("Italiano", b.title); assertEquals("Editore", b.publisher); assertEquals("123", b.isbn); assertEquals(300, b.totalPages);
    }
    @Test public void manualBookWithoutIsbnRetainsPersonalFieldsAndReaderPosition() throws Exception {
        Book b = new Book(); b.id = new String(new char[64]).replace('\0', 'b');
        b.format = "CATALOGO"; b.title = "Libro senza ISBN"; b.author = "Ada; Bruno";
        b.subtitle = "Sottotitolo"; b.tags = "preferiti, viaggio"; b.medium = "Cartaceo";
        b.location = "Scaffale 2"; b.rating = 4; b.started = "2026-10-01";
        b.finished = "2026-11-06"; b.addedAt = 123456789L; b.page = 9;
        Book copy = Book.from(b.json());
        assertEquals("", copy.isbn); assertEquals(b.subtitle, copy.subtitle);
        assertEquals(b.tags, copy.tags); assertEquals(b.location, copy.location);
        assertEquals(b.medium, copy.medium); assertEquals(4, copy.rating);
        assertEquals(b.started, copy.started); assertEquals(b.finished, copy.finished);
        assertEquals(b.addedAt, copy.addedAt); assertEquals(9, copy.page);
        assertEquals(1, LibraryQuery.select(java.util.Arrays.asList(copy), "viaggio", "Tutti i libri", "", "", "", "Titolo").size());
    }
}
