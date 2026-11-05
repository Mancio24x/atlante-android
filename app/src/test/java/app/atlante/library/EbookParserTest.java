package app.atlante.library;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public class EbookParserTest {
    private File zip(String... names) throws Exception {
        File f = File.createTempFile("atlante-", ".cbz"); f.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(f))) {
            for (String name : names) { out.putNextEntry(new ZipEntry(name)); out.write(new byte[]{1, 2, 3}); out.closeEntry(); }
        }
        return f;
    }

    @Test public void resolvesLocalEpubLinksAndKeepsLiteralPlus() throws Exception {
        assertEquals("text/intro.xhtml", EbookParser.path("OPS/content.opf", "../text/intro.xhtml#section"));
        assertEquals("OPS/a+b.xhtml", EbookParser.path("OPS/content.opf", "a+b.xhtml"));
        assertEquals("OPS/à.xhtml", EbookParser.path("OPS/content.opf", "%C3%A0.xhtml"));
    }
    @Test public void rejectsNonLocalAndEscapingLinks() throws Exception {
        for (String value : new String[]{"https://example.com/a", "/etc/passwd", "../../a", "%2E%2E/%2E%2E/a", "..\\a"}) {
            try { EbookParser.path("OPS/package.opf", value); fail(value); }
            catch (IOException expected) { }
        }
    }
    @Test public void naturalOrderHandlesNumbersWithoutOverflow() {
        assertTrue(EbookParser.naturalCompare("page2.jpg", "page10.jpg") < 0);
        assertTrue(EbookParser.naturalCompare("99999999999999999999", "100000000000000000000") < 0);
        assertEquals(0, EbookParser.naturalCompare("PAGE02.JPG", "page2.jpg"));
    }
    @Test public void mixedDepthComicsSortByBasenameAndIgnoreMetadata() throws Exception {
        File file = zip("z/2.jpg", "10.png", "a/1.jpg", "__MACOSX/0.jpg", ".hidden.png", "ComicInfo.xml");
        try (ZipFile z = new ZipFile(file)) {
            assertEquals(Arrays.asList("a/1.jpg", "z/2.jpg", "10.png"), EbookParser.comicPages(z));
        } finally { file.delete(); }
    }
    @Test public void equalDepthComicsKeepFolderOrder() throws Exception {
        File file = zip("b/1.jpg", "a/10.png", "a/2.jpg");
        try (ZipFile z = new ZipFile(file)) {
            assertEquals(Arrays.asList("a/2.jpg", "a/10.png", "b/1.jpg"), EbookParser.comicPages(z));
        } finally { file.delete(); }
    }
    @Test public void boundedReadsRejectOversizedInput() throws Exception {
        try { EbookParser.read(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 3); fail("oversize"); }
        catch (IOException expected) { }
    }
    @Test public void textPaginationPreservesUnicodeAndEveryCharacter() throws Exception {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 1000; i++) source.append("Città, perché? 🌍\n");
        File file = File.createTempFile("atlante-unicode-", ".txt");
        try {
            try (OutputStream out = new FileOutputStream(file)) { out.write(source.toString().getBytes(StandardCharsets.UTF_8)); }
            List<String> pages = EbookParser.textPages(file, "TXT");
            assertTrue(pages.size() > 1);
            assertEquals(source.toString(), String.join("", pages));
            for (String page : pages) assertFalse(Character.isHighSurrogate(page.charAt(page.length() - 1)));
        } finally { file.delete(); }
    }
}
