package app.atlante.library;

import static org.junit.Assert.*;
import java.io.StringReader;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

public class ChaptersTest {
    private static XmlPullParser parser(String xml) throws Exception {
        KXmlParser p = new KXmlParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(new StringReader(xml));
        return p;
    }

    @Test public void epub3NavigationReadsOnlyTheTocWithNestedText() throws Exception {
        String nav = "<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><body>"
            + "<nav epub:type='landmarks'><ol><li><a href='c.xhtml'>Copertina</a></li></ol></nav>"
            + "<nav epub:type='toc'><ol><li><a href='1.xhtml'><span>1.</span> Il   viaggio</a>"
            + "<ol><li><a href='1a.xhtml'>Parte prima</a></li></ol></li><li><a href='2.xhtml'>Ritorno</a></li></ol></nav></body></html>";
        assertEquals(Arrays.asList("1. Il viaggio", "Parte prima", "Ritorno"), EbookParser.navTitles(parser(nav)));
    }

    @Test public void epub2NcxReadsNavLabels() throws Exception {
        String ncx = "<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><docTitle><text>Libro</text></docTitle><navMap>"
            + "<navPoint><navLabel><text>Prologo</text></navLabel><navPoint><navLabel><text>Capitolo 1</text></navLabel></navPoint></navPoint>"
            + "</navMap></ncx>";
        assertEquals(Arrays.asList("Prologo", "Capitolo 1"), EbookParser.ncxTitles(parser(ncx)));
    }

    @Test public void chaptersAndPercentRoundTripAndClampRatings() throws Exception {
        Book b = new Book(); b.id = new String(new char[64]).replace('\0', 'e'); b.format = "CATALOGO"; b.percent = 40;
        Book.Chapter c = new Book.Chapter("Uno"); c.rating = 4; c.comment = "Bellissimo"; b.chapters.add(c);
        Book copy = Book.from(b.json());
        assertEquals(40, copy.percent); assertEquals(1, copy.chapters.size());
        assertEquals("Uno", copy.chapters.get(0).title); assertEquals(4, copy.chapters.get(0).rating); assertEquals("Bellissimo", copy.chapters.get(0).comment);
        JSONObject tampered = b.json(); tampered.getJSONArray("chapters").getJSONObject(0).put("rating", 9); tampered.put("percent", 300);
        Book clamped = Book.from(tampered);
        assertEquals(5, clamped.chapters.get(0).rating); assertEquals(100, clamped.percent);
    }

    @Test public void finishingABookWithoutPagesSetsOneHundredPercent() {
        Book b = new Book(); b.status = Book.READ; Book.applyStatusEffects(b, "2026-11-06");
        assertEquals(100, b.percent); assertEquals("2026-11-06", b.finished);
    }
}
