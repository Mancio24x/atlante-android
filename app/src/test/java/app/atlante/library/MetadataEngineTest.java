package app.atlante.library;

import static org.junit.Assert.*;
import app.atlante.library.model.Book;
import app.atlante.library.online.Candidate;
import app.atlante.library.online.MetadataEngine;
import app.atlante.library.online.MetadataSources;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

/** Real SBN and Open Library records of 1984 (Mondadori 2016, translated by Stefano Manferlotti), saved on 2026-11-06. */
public class MetadataEngineTest {
    private JSONObject res(String n) throws Exception { try (InputStream in = getClass().getResourceAsStream("/meta/" + n)) { return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)); } }
    private Candidate sbn() throws Exception { Candidate c = MetadataSources.sbn(res("sbn-1984-full.json")); c.byIsbn = true; return c; }
    private Candidate ol() throws Exception { Candidate c = MetadataSources.openLibrary(res("ol-1984-edition.json"), res("ol-1984-work.json")); c.byIsbn = true; return c; }

    static MetadataEngine.Catalogs fixed(List<Candidate> byIsbn, List<Candidate> search) {
        return new MetadataEngine.Catalogs() {
            public List<Candidate> byIsbn(String isbn, List<String> notes) { return byIsbn; }
            public List<Candidate> search(Book b, List<String> notes) { return search; }
        };
    }
    static Map<String, MetadataEngine.Field> byKey(MetadataEngine.Result r) { Map<String, MetadataEngine.Field> m = new HashMap<>(); for (MetadataEngine.Field f : r.fields) m.put(f.key, f); return m; }

    @Test public void sbnGivesTheItalianEditionWithRoles() throws Exception {
        Candidate c = sbn();
        assertEquals("1984", c.title); assertEquals(Collections.singletonList("George Orwell"), c.authors);
        assertEquals(Collections.singletonList("Stefano Manferlotti"), c.translators);
        assertEquals("Mondadori", c.publisher); assertEquals("2016", c.year); assertEquals(341, c.pages); assertEquals("ita", c.language);
        assertEquals("Oscar moderni · 36", c.collection); assertTrue(c.dewey.startsWith("823.914")); assertTrue(c.isbns.contains("9788804668237"));
    }

    @Test public void openLibraryGivesTheWork() throws Exception {
        Candidate c = ol();
        assertEquals("Nineteen Eighty-Four", c.originalTitle); assertFalse(c.subjects.isEmpty());
        assertTrue(c.covers.get(0).url.endsWith("-L.jpg?default=false")); assertEquals("eng", c.descriptions.get(0).language);
    }

    @Test public void editionFromTheItalianCatalogWorkFromOpenLibrary() throws Exception {
        Book b = new Book(); b.title = "1984"; b.author = "George Orwell"; b.isbn = "9788804668237";
        MetadataEngine.Settings s = new MetadataEngine.Settings();
        MetadataEngine.Result r = MetadataEngine.lookup(b, s, fixed(Arrays.asList(sbn(), ol()), new ArrayList<>()), null);
        Map<String, MetadataEngine.Field> f = byKey(r);
        assertEquals("Mondadori", f.get("publisher").proposed); assertEquals("SBN", f.get("publisher").source);
        assertEquals("341", f.get("totalPages").proposed);                       // pages of this edition, from SBN
        assertEquals("Stefano Manferlotti", f.get("translators").proposed);
        assertEquals("Nineteen Eighty-Four", f.get("originalTitle").proposed); assertEquals("Open Library", f.get("originalTitle").source);
        assertTrue(f.get("description").label.contains("eng"));                    // only an English plot exists: shown as such
        assertFalse(r.covers.isEmpty()); assertTrue(r.ambiguous.isEmpty());
        assertTrue(f.get("publisher").accept);                                     // empty field: proposed checked
        assertEquals("Narrativa inglese", f.get("genre").proposed);               // from SBN's Dewey class, not from free subjects
    }

    @Test public void italianPlotWinsOverALongerEnglishOne() throws Exception {
        Book b = new Book(); b.title = "1984"; b.author = "George Orwell"; b.isbn = "9788804668237";
        Candidate google = new Candidate("ISBNdb"); google.byIsbn = true; google.title = "1984"; google.authors.add("George Orwell"); google.isbns.add("9788804668237"); google.language = "ita";
        google.descriptions.add(new Candidate.Description("Nel mondo di Oceania il Grande Fratello controlla ogni cosa e la storia viene riscritta ogni giorno dal Ministero della Verità, dove lavora Winston Smith, che sogna di ribellarsi.", "ita", "ISBNdb"));
        MetadataEngine.Result r = MetadataEngine.lookup(b, new MetadataEngine.Settings(), fixed(Arrays.asList(sbn(), ol(), google), new ArrayList<>()), null);
        MetadataEngine.Field d = byKey(r).get("description");
        assertEquals("ISBNdb", d.source); assertTrue(d.proposed.startsWith("Nel mondo di Oceania")); assertEquals("Trama", d.label);
    }

    @Test public void differentEditionsWithoutIsbnAreAskedNotGuessed() {
        Book b = new Book(); b.title = "1984"; b.author = "George Orwell";
        Candidate a = new Candidate("SBN"); a.title = "1984"; a.authors.add("George Orwell"); a.publisher = "Mondadori"; a.year = "2016"; a.language = "ita"; a.isbns.add("9788804668237");
        Candidate e = new Candidate("SBN"); e.title = "1984"; e.authors.add("George Orwell"); e.publisher = "Feltrinelli"; e.year = "2021"; e.language = "ita"; e.isbns.add("9788807903953");
        MetadataEngine.Result r = MetadataEngine.lookup(b, new MetadataEngine.Settings(), fixed(new ArrayList<>(), Arrays.asList(a, e)), null);
        assertEquals(2, r.ambiguous.size()); assertNull(byKey(r).get("publisher"));       // no edition fields until the user chooses
        MetadataEngine.Result chosen = MetadataEngine.lookup(b, new MetadataEngine.Settings(), fixed(new ArrayList<>(), Arrays.asList(a, e)), e);
        assertEquals("Feltrinelli", byKey(chosen).get("publisher").proposed);
    }

    @Test public void manualFieldsAreProposedUncheckedAndSourcesRecorded() throws Exception {
        Book before = new Book(); before.title = "1984"; before.author = "George Orwell"; before.isbn = "9788804668237"; before.publisher = "";
        Book after = Book.from(withId(before).json()); after.publisher = "Editore scritto a mano";
        MetadataEngine.markManual(before, after);
        MetadataEngine.Result r = MetadataEngine.lookup(after, new MetadataEngine.Settings(), fixed(Arrays.asList(sbn(), ol()), new ArrayList<>()), null);
        MetadataEngine.Field p = byKey(r).get("publisher");
        assertTrue(p.manual); assertFalse(p.accept);
        MetadataEngine.apply(after, r.fields);
        assertEquals("Editore scritto a mano", after.publisher);                    // untouched: not accepted
        assertEquals("Stefano Manferlotti", after.meta.optString("translators")); assertEquals("SBN", after.meta.optJSONObject("sources").optString("translators"));
        assertEquals("Nineteen Eighty-Four", Book.from(after.json()).meta.optString("originalTitle"));   // survives saving
    }
    private static Book withId(Book b) { b.id = new String(new char[64]).replace('\0', 'f'); b.format = "CATALOGO"; return b; }
}
