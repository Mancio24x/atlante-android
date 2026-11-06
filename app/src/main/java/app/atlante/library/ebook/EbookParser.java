package app.atlante.library.ebook;

import app.atlante.library.model.Book;

import android.text.Html;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** All imported markup becomes text; EPUB scripts and network resources never run. */
public final class EbookParser {
    public static final int ENTRY_LIMIT = 32 * 1024 * 1024;
    public static final int TEXT_LIMIT = 32 * 1024 * 1024;
    public static final int MAX_PAGES = 5000;

    public static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384]; int count;
        while ((count = in.read(buffer)) != -1) {
            if ((long)out.size() + count > max) throw new IOException("Contenuto troppo grande");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    public static byte[] entry(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new IOException("File mancante nell'archivio: " + name);
        if (e.getSize() > ENTRY_LIMIT) throw new IOException("Pagina troppo grande");
        try (InputStream in = zip.getInputStream(e)) { return read(in, ENTRY_LIMIT); }
    }

    public static XmlPullParser xml(byte[] data) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(new ByteArrayInputStream(data), null);
        return p;
    }

    public static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) if (name.equals(p.getAttributeName(i))) return p.getAttributeValue(i);
        return "";
    }

    public static String path(String parent, String href) throws IOException {
        String reference = href.split("#", 2)[0];
        try { reference = java.net.URLDecoder.decode(reference.replace("+", "%2B"), "UTF-8"); }
        catch (IllegalArgumentException e) { throw new IOException("Percorso EPUB non valido", e); }
        if (reference.contains(":") || reference.startsWith("/") || reference.contains("\\")) throw new IOException("Percorso EPUB non locale");
        String base = parent.contains("/") ? parent.substring(0, parent.lastIndexOf('/') + 1) : "";
        ArrayList<String> parts = new ArrayList<>();
        for (String part : (base + reference).split("/")) {
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IOException("Percorso EPUB non valido");
                parts.remove(parts.size() - 1);
            } else if (!part.isEmpty() && !part.equals(".")) parts.add(part);
        }
        return String.join("/", parts);
    }

    public static final class Package {
        public String file, coverId = "", nav, ncx;
        public Map<String, String> manifest = new LinkedHashMap<>();
        public List<String> spine = new ArrayList<>();
    }

    public static Package epub(ZipFile zip, Book book) throws Exception {
        Package opf = new Package();
        XmlPullParser container = xml(entry(zip, "META-INF/container.xml"));
        for (int t = container.getEventType(); t != XmlPullParser.END_DOCUMENT; t = container.next()) {
            if (t == XmlPullParser.START_TAG && container.getName().equals("rootfile")) {
                opf.file = path("", attr(container, "full-path")); break;
            }
        }
        if (opf.file == null) throw new IOException("Indice EPUB mancante");
        XmlPullParser p = xml(entry(zip, opf.file));
        List<String> authors = new ArrayList<>(), subjects = new ArrayList<>();
        int metadataDepth = -1;
        for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
            if (t == XmlPullParser.END_TAG && p.getDepth() == metadataDepth) metadataDepth = -1;
            if (t != XmlPullParser.START_TAG) continue;
            String tag = p.getName();
            if (tag.equals("metadata")) { metadataDepth = p.getDepth(); continue; }
            if (tag.equals("item")) {
                String id = attr(p, "id");
                opf.manifest.put(id, path(opf.file, attr(p, "href")));
                List<String> properties = Arrays.asList(attr(p, "properties").split("\\s+"));
                if (properties.contains("cover-image")) opf.coverId = id;
                if (properties.contains("nav")) opf.nav = opf.manifest.get(id);
                if (attr(p, "media-type").equals("application/x-dtbncx+xml")) opf.ncx = opf.manifest.get(id);
            } else if (tag.equals("itemref")) {
                if (!attr(p, "linear").equals("no")) opf.spine.add(attr(p, "idref"));
            } else if (metadataDepth >= 0 && tag.equals("meta")) {
                String name = attr(p, "name"), content = attr(p, "content"), property = attr(p, "property");
                if (name.equals("cover")) opf.coverId = content;
                if (book != null && name.equals("calibre:series")) book.series = content;
                if (book != null && name.equals("calibre:series_index")) {
                    try { book.number = Double.parseDouble(content); } catch (NumberFormatException ignored) { }
                }
                if (book != null && property.equals("belongs-to-collection")) book.series = p.nextText();
                if (book != null && property.equals("group-position")) {
                    try { book.number = Double.parseDouble(p.nextText()); } catch (NumberFormatException ignored) { }
                }
            } else if (book != null && metadataDepth >= 0) {
                if (tag.equals("title")) book.title = p.nextText();
                else if (tag.equals("creator")) authors.add(p.nextText());
                else if (tag.equals("subject")) subjects.add(p.nextText());
                else if (tag.equals("description")) book.description = plain(p.nextText());
                else if (tag.equals("publisher")) book.publisher = p.nextText();
                else if (tag.equals("language")) book.language = p.nextText();
                else if (tag.equals("date")) book.year = p.nextText();
                else if (tag.equals("identifier") && book.isbn.isEmpty()) book.isbn = p.nextText();
            }
            if (opf.manifest.size() > 10000 || opf.spine.size() > MAX_PAGES) throw new IOException("EPUB troppo complesso");
        }
        if (book != null) {
            if (!authors.isEmpty()) book.author = String.join(" & ", authors);
            book.genre = String.join(", ", subjects);
        }
        return opf;
    }

    public static final int MAX_CHAPTERS = 2000;

    /** Chapter titles from the EPUB table of contents: EPUB 3 navigation first, then the EPUB 2 NCX. */
    public static List<String> tableOfContents(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            Package p = epub(zip, null);
            List<String> titles = new ArrayList<>();
            if (p.nav != null && zip.getEntry(p.nav) != null) titles = navTitles(xml(entry(zip, p.nav)));
            if (titles.isEmpty() && p.ncx != null && zip.getEntry(p.ncx) != null) titles = ncxTitles(xml(entry(zip, p.ncx)));
            return titles;
        }
    }

    /** Links inside the {@code <nav epub:type="toc">} of an EPUB 3 navigation document. */
    public static List<String> navTitles(XmlPullParser p) throws Exception {
        List<String> titles = new ArrayList<>(); int tocDepth = -1;
        for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT && titles.size() < MAX_CHAPTERS; t = p.next()) {
            if (t == XmlPullParser.END_TAG && p.getDepth() == tocDepth && p.getName().equals("nav")) tocDepth = -1;
            if (t != XmlPullParser.START_TAG) continue;
            if (p.getName().equals("nav") && Arrays.asList(attr(p, "type").split("\\s+")).contains("toc")) tocDepth = p.getDepth();
            else if (tocDepth >= 0 && p.getName().equals("a")) addTitle(titles, text(p));
        }
        return titles;
    }

    /** {@code navPoint/navLabel/text} entries of an EPUB 2 NCX, in document order. */
    public static List<String> ncxTitles(XmlPullParser p) throws Exception {
        List<String> titles = new ArrayList<>(); boolean label = false;
        for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT && titles.size() < MAX_CHAPTERS; t = p.next()) {
            if (t == XmlPullParser.START_TAG && p.getName().equals("navLabel")) label = true;
            else if (t == XmlPullParser.END_TAG && p.getName().equals("navLabel")) label = false;
            else if (t == XmlPullParser.START_TAG && label && p.getName().equals("text")) addTitle(titles, text(p));
        }
        return titles;
    }

    private static void addTitle(List<String> titles, String title) {
        title = title.replaceAll("\\s+", " ").trim();
        if (!title.isEmpty()) titles.add(title.length() > 200 ? title.substring(0, 200) : title);
    }

    /** All text inside the current element, including nested tags such as {@code <span>}. */
    public static String text(XmlPullParser p) throws Exception {
        StringBuilder out = new StringBuilder(); int depth = p.getDepth();
        for (int t = p.next(); !(t == XmlPullParser.END_TAG && p.getDepth() == depth) && t != XmlPullParser.END_DOCUMENT; t = p.next())
            if (t == XmlPullParser.TEXT) out.append(p.getText());
        return out.toString();
    }

    public static void metadata(File file, File cover, Book book) throws Exception {
        if (book.format.equals("TXT")) return;
        try (ZipFile zip = new ZipFile(file)) {
            String image = null;
            if (book.format.equals("EPUB")) {
                Package p = epub(zip, book);
                image = p.manifest.get(p.coverId);
            } else {
                List<String> pages = comicPages(zip);
                if (pages.isEmpty()) throw new IOException("Il CBZ non contiene immagini");
                image = pages.get(0);
                ZipEntry comicInfo = zip.getEntry("ComicInfo.xml");
                if (comicInfo != null) {
                    XmlPullParser p = xml(entry(zip, "ComicInfo.xml"));
                    for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                        if (t != XmlPullParser.START_TAG) continue;
                        String tag = p.getName();
                        if (tag.equals("Title")) book.title = p.nextText();
                        else if (tag.equals("Writer")) book.author = p.nextText();
                        else if (tag.equals("Series")) book.series = p.nextText();
                        else if (tag.equals("Genre")) book.genre = p.nextText();
                        else if (tag.equals("Summary")) book.description = p.nextText();
                        else if (tag.equals("Number")) {
                            try { book.number = Double.parseDouble(p.nextText()); } catch (NumberFormatException ignored) { }
                        }
                    }
                }
            }
            if (image != null) {
                try (OutputStream out = new FileOutputStream(cover)) { out.write(entry(zip, image)); }
            }
        }
    }

    public static String plain(String html) {
        // Native text rendering: no WebView, scripts, remote fonts or image requests.
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    public static List<String> textPages(File file, String format) throws Exception {
        StringBuilder text = new StringBuilder();
        if (format.equals("TXT")) {
            try (InputStream in = new FileInputStream(file)) { text.append(new String(read(in, TEXT_LIMIT), StandardCharsets.UTF_8)); }
        } else {
            try (ZipFile zip = new ZipFile(file)) {
                Package p = epub(zip, null);
                for (String id : p.spine) {
                    String name = p.manifest.get(id);
                    if (name == null) throw new IOException("Capitolo EPUB mancante");
                    byte[] chapter = entry(zip, name);
                    if ((long)text.length() + chapter.length > TEXT_LIMIT) throw new IOException("Testo oltre 32 MB");
                    text.append(plain(new String(chapter, StandardCharsets.UTF_8))).append("\n\n");
                }
            }
        }
        if (text.toString().trim().isEmpty()) throw new IOException("Nessun testo leggibile: il libro potrebbe essere protetto o composto da immagini");
        List<String> pages = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + 6000);
            if (end < text.length()) {
                int space = text.lastIndexOf(" ", end);
                if (space > start + 3000) end = space + 1;
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            pages.add(text.substring(start, end)); start = end;
        }
        return pages;
    }

    public static List<String> comicPages(ZipFile zip) throws IOException {
        List<String> pages = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        int count = 0; boolean mixed = false; int depth = -1;
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (++count > 20000) throw new IOException("Archivio troppo complesso");
            String name = e.getName(), lower = name.toLowerCase(Locale.ROOT);
            if (e.isDirectory() || name.startsWith("__MACOSX/") || name.contains("/.") || name.startsWith(".")) continue;
            if (!lower.matches(".*\\.(jpg|jpeg|png|gif|webp)")) continue;
            int d = name.split("/").length;
            if (depth != -1 && d != depth) mixed = true;
            depth = d; pages.add(name);
            if (pages.size() > MAX_PAGES) throw new IOException("Oltre 5000 pagine nel fumetto");
        }
        final boolean byBasename = mixed;
        pages.sort((a, b) -> {
            String x = byBasename ? a.substring(a.lastIndexOf('/') + 1) : a;
            String y = byBasename ? b.substring(b.lastIndexOf('/') + 1) : b;
            int order = naturalCompare(x, y);
            return order == 0 ? naturalCompare(a, b) : order;
        });
        return pages;
    }

    public static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        a = a.toLowerCase(Locale.ROOT); b = b.toLowerCase(Locale.ROOT);
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (x >= '0' && x <= '9' && y >= '0' && y <= '9') {
                int ai = i, bj = j;
                while (i < a.length() && a.charAt(i) >= '0' && a.charAt(i) <= '9') i++;
                while (j < b.length() && b.charAt(j) >= '0' && b.charAt(j) <= '9') j++;
                String an = a.substring(ai, i).replaceFirst("^0+(?!$)", "");
                String bn = b.substring(bj, j).replaceFirst("^0+(?!$)", "");
                int c = Integer.compare(an.length(), bn.length());
                if (c == 0) c = an.compareTo(bn);
                if (c != 0) return c;
            } else { if (x != y) return Character.compare(x, y); i++; j++; }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
