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
        assertEquals(1, LibraryQuery.select(java.util.Arrays.asList(copy), "viaggio", "Tutti i libri", "", "", "Titolo").size());
    }
    @Test public void fourReadingStatesAndAbandonedGoesBackToRead() throws Exception {
        String id = new String(new char[64]).replace('\0', 'd');
        String[][] cases = {{"In pausa", "In pausa"}, {"Abbandonato", "Da leggere"}, {"In lettura", "In lettura"}, {"Letto", "Letto"}, {"qualcosa", "Da leggere"}};
        for (String[] c : cases)
            assertEquals(c[1], Book.from(new JSONObject().put("id", id).put("format", "EPUB").put("status", c[0])).status);
    }
    @Test public void stateChangesFillDatesAndPagesWithoutOverwriting() {
        Book b = new Book(); b.totalPages = 300; b.currentPage = 40;
        b.status = Book.READING; Book.applyStatusEffects(b, "2026-11-06");
        assertEquals("2026-11-06", b.started); assertEquals("", b.finished); assertEquals(40, b.currentPage);
        b.status = Book.READ; Book.applyStatusEffects(b, "2026-12-01");
        assertEquals("2026-11-06", b.started); assertEquals("2026-12-01", b.finished); assertEquals(300, b.currentPage);
        Book.applyStatusEffects(b, "2027-01-01"); assertEquals("2026-12-01", b.finished);
    }
    @Test public void preferredLanguageComesFirstWithItsEditionTitle() throws Exception {
        String json = "{\"docs\":["
            + "{\"key\":\"/works/OL1W\",\"title\":\"Dune\",\"language\":[\"eng\"],\"editions\":{\"docs\":[{\"key\":\"/books/OL9M\",\"title\":\"Dune (FR)\",\"language\":[\"fre\"]}]}},"
            + "{\"key\":\"/works/OL2W\",\"title\":\"Cr\u00f3nica de una muerte anunciada\",\"language\":[\"spa\",\"ita\"],\"editions\":{\"docs\":[{\"key\":\"/books/OL5M\",\"title\":\"Cronaca di una morte annunciata\",\"language\":[\"ita\"],\"cover_i\":42}]}}]}";
        java.util.List<Book> any = OnlineCatalog.parseSearch(json, "ita", false);
        assertEquals("Cronaca di una morte annunciata", any.get(0).title);
        assertEquals("/books/OL5M", any.get(0).edition); assertEquals(42, any.get(0).coverId); assertEquals("ita", any.get(0).language);
        assertEquals("Dune", any.get(1).title); // a French edition is not used for an Italian search
        assertEquals(1, OnlineCatalog.parseSearch(json, "ita", true).size());
        assertEquals("Dune", OnlineCatalog.parseSearch(json, "", false).get(0).title); // no preference: relevance order
    }
    @Test public void searchedEditionWinsOverOtherEditions() throws Exception {
        Book b = new Book(); b.edition = "/books/OL7M";
        OnlineCatalog.enrichEdition(b, new JSONObject("{\"entries\":[{\"key\":\"/books/OL6M\",\"title\":\"Altra\",\"languages\":[{\"key\":\"/languages/ita\"}]},{\"key\":\"/books/OL7M\",\"title\":\"Trovata\"}]}"), "ita");
        assertEquals("Trovata", b.title);
    }
    @Test public void coverSearchOnlyAcceptsTheSameTitle() {
        assertTrue(OnlineCatalog.sameTitle("La peste", "La peste"));
        assertTrue(OnlineCatalog.sameTitle("Il Gattopardo", "Il gattopardo : romanzo"));
        assertTrue(OnlineCatalog.sameTitle("L’amica geniale", "L'amica geniale"));
        assertFalse(OnlineCatalog.sameTitle("La casa", "La casa degli spiriti"));
        assertFalse(OnlineCatalog.sameTitle("Dune", "Dune Messiah"));
        assertFalse(OnlineCatalog.sameTitle("", "Qualcosa"));
    }
}
