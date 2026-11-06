package app.atlante.library;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * Recognizes a library file and turns it into catalog sheets:
 * Atlante backups (ZIP, extracted JSON, in a folder or inside another ZIP), CSV lists (also Goodreads and
 * StoryGraph exports), Atlante HTML catalogs with embedded data, Calibre libraries (metadata.opf) and JSON lists.
 * Nothing is written here: the caller shows a summary and restores after confirmation.
 */
final class CatalogImport {
    static final int TEXT_LIMIT = 32 * 1024 * 1024, COVER_LIMIT = 2 * 1024 * 1024, COVERS_TOTAL = 64 * 1024 * 1024, MAX_BOOKS = 20000;
    static final String UNKNOWN = "Formato non riconosciuto. Puoi importare: backup di Atlante (ZIP, anche estratto o dentro un altro ZIP), "
        + "elenchi CSV (anche da Goodreads e StoryGraph), cataloghi HTML di Atlante e librerie Calibre (ZIP con i file metadata.opf).";

    static BackupArchive.Contents read(InputStream source, String fileName) throws Exception {
        BufferedInputStream in = new BufferedInputStream(source);
        in.mark(4);
        byte[] magic = new byte[4]; int n = in.read(magic); in.reset();
        if (n >= 2 && magic[0] == 'P' && magic[1] == 'K') return readZip(in);
        byte[] bytes = EbookParser.read(in, TEXT_LIMIT);
        BackupArchive.Contents contents = readText(bytes, fileName);
        if (contents == null) throw new IOException(UNKNOWN);
        return contents;
    }

    // ---- ZIP archives ---------------------------------------------------------------------------

    private static final class Scan {
        BackupArchive.Contents atlante, text;
        final ArrayList<Book> opf = new ArrayList<>();
        final Map<String, String> opfFolders = new HashMap<>(); // book id -> folder inside the ZIP
        final Map<String, byte[]> folderCovers = new HashMap<>();
        long coverBytes;
    }

    private static BackupArchive.Contents readZip(InputStream in) throws Exception {
        Scan scan = new Scan();
        scanZip(new ZipInputStream(in), scan, null);
        if (scan.atlante != null) { scan.atlante.fromZip = true; return scan.atlante; }
        if (!scan.opf.isEmpty()) {
            BackupArchive.Contents c = contents("Libreria Calibre (metadata.opf)", scan.opf);
            for (Book b : scan.opf) {
                byte[] cover = scan.folderCovers.get(scan.opfFolders.get(b.id));
                if (cover != null) c.covers.put(b.id, cover);
            }
            return c;
        }
        if (scan.text != null) return scan.text;
        throw new IOException(UNKNOWN);
    }

    /** Walks the entries; stops at an Atlante manifest. {@code nested} is the outer entry when inside a ZIP in the ZIP. */
    private static void scanZip(ZipInputStream zip, Scan scan, String nested) throws Exception {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null && scan.atlante == null) {
            String name = entry.getName(), lower = name.toLowerCase(Locale.ROOT);
            if (entry.isDirectory() || lower.contains("__macosx/") || lower.endsWith(".ds_store")) continue;
            String base = lower.substring(lower.lastIndexOf('/') + 1);
            if (base.equals(BackupArchive.MANIFEST)) {
                BackupArchive.Contents c = atlanteJson(EbookParser.read(zip, BackupArchive.MANIFEST_LIMIT));
                if (c != null) { c.nested = nested; c.prefix = name.substring(0, name.length() - BackupArchive.MANIFEST.length()); scan.atlante = c; }
            } else if (base.endsWith(".zip") && nested == null) {
                scanZip(new ZipInputStream(new FilterInputStream(zip) { @Override public void close() { } }), scan, name);
            } else if (base.endsWith(".opf")) {
                Book b = opf(EbookParser.read(zip, 4 * 1024 * 1024));
                if (b != null && scan.opf.size() < MAX_BOOKS) { scan.opf.add(b); scan.opfFolders.put(b.id, folder(name)); }
            } else if (base.matches("cover\\.(jpe?g|png)") && scan.coverBytes < COVERS_TOTAL) {
                byte[] cover = EbookParser.read(zip, COVER_LIMIT);
                scan.folderCovers.put(folder(name), cover); scan.coverBytes += cover.length;
            } else if (scan.text == null && base.matches(".*\\.(csv|tsv|html?|json)")) {
                scan.text = readText(EbookParser.read(zip, TEXT_LIMIT), base);
            }
        }
    }

    private static String folder(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }

    // ---- Text formats ---------------------------------------------------------------------------

    static BackupArchive.Contents readText(byte[] bytes, String fileName) throws Exception {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String trimmed = text.trim(), lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            BackupArchive.Contents c = atlanteJson(bytes);
            return c != null ? c : json(trimmed, "Catalogo JSON");
        }
        if (trimmed.regionMatches(true, 0, "<!doctype html", 0, 14) || trimmed.regionMatches(true, 0, "<html", 0, 5) || lower.endsWith(".html") || lower.endsWith(".htm")) {
            Matcher m = Pattern.compile("<script[^>]*id=[\"']library-data[\"'][^>]*>(.*?)</script>", Pattern.DOTALL).matcher(text);
            return m.find() ? json(m.group(1).trim(), "Catalogo HTML di Atlante") : null;
        }
        if (trimmed.startsWith("<")) {
            Book b = opf(bytes);
            return b == null ? null : contents("Scheda Calibre (metadata.opf)", new ArrayList<>(Collections.singletonList(b)));
        }
        return csv(text);
    }

    /** An Atlante backup manifest, or Atlante's own library.json array; null if it is something else. */
    private static BackupArchive.Contents atlanteJson(byte[] bytes) throws Exception {
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        try {
            if (text.startsWith("{")) {
                JSONObject manifest = new JSONObject(text);
                if (!BackupArchive.KIND.equals(manifest.optString("format"))) return null;
                if (manifest.optInt("version") > BackupArchive.VERSION) throw new IOException("Backup creato da una versione più recente di Atlante: aggiorna l’app");
                BackupArchive.Contents c = atlanteBooks(manifest.getJSONArray("books"), "Backup di Atlante");
                c.createdAt = manifest.optLong("createdAt"); c.includesFiles = manifest.optBoolean("includesFiles");
                return c;
            }
            JSONArray array = new JSONArray(text);
            JSONObject first = array.optJSONObject(0);
            if (first == null || !first.optString("id").matches("[0-9a-f]{64}") || first.optString("format").isEmpty()) return null;
            return atlanteBooks(array, "Catalogo di Atlante");
        } catch (org.json.JSONException e) { return null; }
    }

    private static BackupArchive.Contents atlanteBooks(JSONArray array, String kind) throws Exception {
        if (array.length() > MAX_BOOKS) throw new IOException("Troppi libri nel file");
        BackupArchive.Contents c = new BackupArchive.Contents(); c.kind = kind; c.atlante = true;
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            Book b = Book.from(array.getJSONObject(i));
            if (seen.add(b.id)) c.books.add(b);
        }
        return c;
    }

    /** Generic JSON: an array of books or an object with a "books" array (as in Atlante's HTML catalog). */
    static BackupArchive.Contents json(String text, String kind) throws Exception {
        JSONArray array;
        try {
            Object root = text.startsWith("[") ? new JSONArray(text) : new JSONObject(text);
            array = root instanceof JSONArray ? (JSONArray) root : ((JSONObject) root).optJSONArray("books");
        } catch (org.json.JSONException e) { return null; }
        if (array == null) return null;
        ArrayList<Book> books = new ArrayList<>(); Map<String, byte[]> covers = new HashMap<>(); long coverBytes = 0;
        for (int i = 0; i < array.length() && books.size() < MAX_BOOKS; i++) {
            JSONObject o = array.optJSONObject(i); if (o == null) continue;
            Map<String, String> fields = new HashMap<>();
            for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
                String key = keys.next(); Object value = o.opt(key);
                fields.put(key, value instanceof JSONArray ? join((JSONArray) value) : String.valueOf(value));
            }
            Book b = fromFields(fields);
            if (b == null) continue;
            Object files = o.opt("files");
            if (files instanceof JSONArray && ((JSONArray) files).length() > 0 && b.medium.isEmpty()) b.medium = "Ebook";
            String cover = o.optString("cover");
            if (cover.startsWith("data:image/") && cover.contains(";base64,") && coverBytes < COVERS_TOTAL) {
                try {
                    byte[] image = Base64.getDecoder().decode(cover.substring(cover.indexOf(',') + 1).trim());
                    if (image.length <= COVER_LIMIT) { covers.put(b.id, image); coverBytes += image.length; }
                } catch (IllegalArgumentException ignored) { /* A broken image is just skipped. */ }
            }
            books.add(b);
        }
        if (books.isEmpty()) return null;
        BackupArchive.Contents c = contents(kind, books); c.covers.putAll(covers);
        return c;
    }

    private static String join(JSONArray values) {
        ArrayList<String> parts = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            Object v = values.opt(i);
            if (v instanceof String || v instanceof Number) parts.add(String.valueOf(v));
        }
        return String.join(", ", parts);
    }

    // ---- CSV ------------------------------------------------------------------------------------

    static BackupArchive.Contents csv(String text) throws Exception {
        String firstLine = text.split("\r?\n", 2)[0];
        char delimiter = ',';
        int commas = count(firstLine, ','), semicolons = count(firstLine, ';'), tabs = count(firstLine, '\t');
        if (semicolons > commas && semicolons >= tabs) delimiter = ';'; else if (tabs > commas) delimiter = '\t';
        List<List<String>> rows = parseCsv(text, delimiter);
        if (rows.size() < 2) return null;
        List<String> header = rows.get(0);
        Map<String, String> probe = new HashMap<>();
        for (String h : header) probe.put(h, "x");
        if (field(probe, TITLE) == null) return null;
        String joined = String.join("|", header).toLowerCase(Locale.ROOT);
        String kind = joined.contains("exclusive shelf") ? "Goodreads (CSV)" : joined.contains("read status") ? "StoryGraph (CSV)" : "Elenco CSV";
        ArrayList<Book> books = new ArrayList<>();
        for (int r = 1; r < rows.size() && books.size() < MAX_BOOKS; r++) {
            List<String> row = rows.get(r); Map<String, String> fields = new HashMap<>();
            for (int i = 0; i < header.size() && i < row.size(); i++) fields.put(header.get(i), row.get(i));
            Book b = fromFields(fields);
            if (b != null) books.add(b);
        }
        return books.isEmpty() ? null : contents(kind, books);
    }

    private static int count(String s, char c) { int n = 0; boolean quoted = false; for (char x : s.toCharArray()) { if (x == '"') quoted = !quoted; else if (x == c && !quoted) n++; } return n; }

    /** RFC 4180: quoted fields may contain delimiters, doubled quotes and line breaks. */
    static List<List<String>> parseCsv(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; } else quoted = false; }
                else cell.append(c);
            } else if (c == '"') quoted = true;
            else if (c == delimiter) { row.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else cell.append(c);
        }
        row.add(cell.toString());
        if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
        return rows;
    }

    // ---- Field mapping shared by CSV and JSON ---------------------------------------------------

    static final String[] TITLE = {"titolo", "title", "book title", "nome"};
    static final String[] AUTHOR = {"autore", "autori", "author", "authors", "autore principale", "author l f"};
    static final String[] SERIES = {"serie", "saga", "series", "ciclo"};
    static final String[] NUMBER = {"numero", "number", "volume", "order", "series index", "numero serie", "numero nella saga"};
    static final String[] ISBN = {"isbn13", "isbn", "isbn uid", "ean"};
    static final String[] PUBLISHER = {"editore", "publisher", "casa editrice"};
    static final String[] YEAR = {"anno", "year", "year published", "original publication year", "anno di pubblicazione"};
    static final String[] PAGES = {"pagine", "pages", "number of pages", "numero pagine", "numero di pagine"};
    static final String[] GENRE = {"genere", "genre", "genres", "categoria"};
    static final String[] TAGS = {"tag", "tags", "bookshelves", "scaffali", "shelves"};
    static final String[] STATUS = {"stato", "status", "exclusive shelf", "read status", "stato di lettura"};
    static final String[] RATING = {"voto", "rating", "my rating", "star rating", "valutazione"};
    static final String[] NOTES = {"note", "notes", "my review", "review", "commento", "recensione"};
    static final String[] PLOT = {"trama", "descrizione", "description", "synopsis", "sinossi"};
    static final String[] STARTED = {"date started", "data inizio", "iniziato", "inizio lettura"};
    static final String[] FINISHED = {"date read", "last date read", "data fine", "finito", "fine lettura", "data lettura"};
    static final String[] LANGUAGE = {"lingua", "language"};
    static final String[] LOCATION = {"posizione", "location", "scaffale"};
    static final String[] FILE = {"file originale", "file", "path", "percorso"};

    private static String key(String header) {
        String k = java.text.Normalizer.normalize(header.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return k.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** First non-empty value among the aliases; "None"/"null" count as empty. */
    static String field(Map<String, String> fields, String[] aliases) {
        Map<String, String> byKey = new HashMap<>();
        for (Map.Entry<String, String> e : fields.entrySet()) byKey.put(key(e.getKey()), e.getValue());
        for (String alias : aliases) {
            String v = byKey.get(alias);
            if (v != null) { v = v.trim(); if (!v.isEmpty() && !v.equalsIgnoreCase("none") && !v.equalsIgnoreCase("null")) return v; }
        }
        return null;
    }

    static Book fromFields(Map<String, String> f) throws Exception {
        String title = field(f, TITLE);
        if (title == null) return null;
        Book b = new Book(); b.format = "CATALOGO"; b.addedAt = System.currentTimeMillis();
        b.title = title; b.author = or(field(f, AUTHOR), "Autore sconosciuto");
        if (b.author.contains(",") && !b.author.contains(" & ") && f.keySet().stream().anyMatch(k -> key(k).equals("author l f")) && field(f, new String[]{"author"}) == null) {
            String[] p = b.author.split(",\\s*", 2); b.author = (p[1] + " " + p[0]).trim();
        }
        b.series = or(field(f, SERIES), ""); b.number = number(field(f, NUMBER));
        b.isbn = or(field(f, ISBN), "").replaceAll("[^0-9Xx]", "");
        if (!b.isbn.matches("[0-9]{9}[0-9Xx]|97[89][0-9]{10}")) b.isbn = "";
        b.publisher = or(field(f, PUBLISHER), ""); b.language = or(field(f, LANGUAGE), "");
        String year = or(field(f, YEAR), ""); Matcher y = Pattern.compile("(1[5-9]|20)[0-9]{2}").matcher(year); b.year = y.find() ? y.group() : "";
        b.totalPages = (int) Math.max(0, Math.min(100000, number(field(f, PAGES))));
        b.genre = or(field(f, GENRE), ""); b.tags = or(field(f, TAGS), "").replace(";", ",");
        b.location = or(field(f, LOCATION), "");
        b.description = plain(or(field(f, PLOT), ""));
        b.notes = plain(or(field(f, NOTES), ""));
        b.rating = (int) Math.max(0, Math.min(5, Math.round(number(field(f, RATING)))));
        b.status = status(or(field(f, STATUS), ""));
        b.started = date(field(f, STARTED)); b.finished = date(field(f, FINISHED));
        if (b.status.equals(Book.TO_READ) && !b.finished.isEmpty()) b.status = Book.READ;
        if (b.status.equals(Book.READ) && b.totalPages > 0) b.currentPage = b.totalPages;
        String file = or(field(f, FILE), "").toLowerCase(Locale.ROOT);
        if (file.matches(".*\\.(epub|pdf|mobi|azw3?|txt|cbz|cbr|fb2)$")) b.medium = "Ebook";
        b.id = sha("import:" + SbnCatalog.normalize(b.title) + "|" + SbnCatalog.normalize(b.author));
        return b;
    }

    static String status(String value) {
        String v = key(value);
        if (v.matches("read|letto|letti|finished|finito|completed|completato")) return Book.READ;
        if (v.matches("currently reading|reading|in lettura|sto leggendo|in corso|currently-reading")) return Book.READING;
        if (v.matches("in pausa|pausa|paused|on hold|sospeso")) return Book.PAUSED;
        return Book.TO_READ;
    }

    /** yyyy-MM-dd from the usual forms (2023/05/14, 2023-05-14, 14/05/2023, 14.05.2023); empty if unclear. */
    static String date(String value) {
        if (value == null) return "";
        Matcher iso = Pattern.compile("((?:19|20)[0-9]{2})[-/.]([0-9]{1,2})[-/.]([0-9]{1,2})").matcher(value);
        Matcher eu = Pattern.compile("([0-9]{1,2})[-/.]([0-9]{1,2})[-/.]((?:19|20)[0-9]{2})").matcher(value);
        try {
            if (iso.find()) return java.time.LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3))).toString();
            if (eu.find()) return java.time.LocalDate.of(Integer.parseInt(eu.group(3)), Integer.parseInt(eu.group(2)), Integer.parseInt(eu.group(1))).toString();
        } catch (java.time.DateTimeException ignored) { /* Not a real date. */ }
        return "";
    }

    private static double number(String value) {
        if (value == null) return 0;
        Matcher m = Pattern.compile("[0-9]+(?:[.,][0-9]+)?").matcher(value);
        if (!m.find()) return 0;
        try { double d = Double.parseDouble(m.group().replace(',', '.')); return Double.isFinite(d) ? d : 0; } catch (NumberFormatException e) { return 0; }
    }

    private static String or(String value, String fallback) { return value == null ? fallback : value; }

    /** Text without HTML tags (Calibre and some exports store HTML descriptions). */
    static String plain(String html) {
        if (!html.contains("<") && !html.contains("&")) return html.trim();
        return html.replaceAll("(?i)<br\\s*/?>|</p>", "\n").replaceAll("<[^>]+>", "")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    // ---- Calibre metadata.opf -------------------------------------------------------------------

    static Book opf(byte[] bytes) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance(); factory.setNamespaceAware(true);
        XmlPullParser p = factory.newPullParser();
        p.setInput(new ByteArrayInputStream(bytes), null);
        Map<String, String> f = new HashMap<>(); List<String> authors = new ArrayList<>(), subjects = new ArrayList<>();
        try {
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t != XmlPullParser.START_TAG) continue;
                String tag = p.getName();
                switch (tag) {
                    case "title": f.putIfAbsent("title", EbookParser.text(p).trim()); break;
                    case "creator": authors.add(EbookParser.text(p).trim()); break;
                    case "description": f.put("description", EbookParser.text(p)); break;
                    case "publisher": f.put("publisher", EbookParser.text(p)); break;
                    case "date": f.put("year", EbookParser.text(p)); break;
                    case "language": f.put("language", EbookParser.text(p)); break;
                    case "subject": subjects.add(EbookParser.text(p).trim()); break;
                    case "identifier": {
                        String scheme = EbookParser.attr(p, "scheme").toLowerCase(Locale.ROOT), value = EbookParser.text(p).trim();
                        if (scheme.equals("isbn") || value.toLowerCase(Locale.ROOT).startsWith("isbn") || value.replaceAll("[^0-9]", "").matches("97[89][0-9]{10}"))
                            f.put("isbn", value.replaceAll("(?i)^(urn:)?isbn:", ""));
                        break;
                    }
                    case "meta": {
                        String name = EbookParser.attr(p, "name"), content = EbookParser.attr(p, "content");
                        if (name.equals("calibre:series")) f.put("series", content);
                        if (name.equals("calibre:series_index")) f.put("number", content);
                        if (name.equals("calibre:rating")) f.put("rating", Double.toString(number(content) / 2));
                        break;
                    }
                    default: break;
                }
            }
        } catch (Exception e) { return null; }
        if (!f.containsKey("title") || f.get("title").isEmpty()) return null;
        if (!authors.isEmpty()) f.put("author", String.join(" & ", authors));
        if (!subjects.isEmpty()) f.put("tags", String.join(", ", subjects));
        Book b = fromFields(f);
        if (b != null && b.medium.isEmpty()) b.medium = "Ebook";
        return b;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static BackupArchive.Contents contents(String kind, ArrayList<Book> books) {
        BackupArchive.Contents c = new BackupArchive.Contents(); c.kind = kind; c.atlante = false;
        HashSet<String> seen = new HashSet<>();
        for (Book b : books) if (seen.add(b.id)) c.books.add(b);
        return c;
    }

    private static String sha(String value) throws Exception {
        StringBuilder id = new StringBuilder();
        for (byte v : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) id.append(String.format(Locale.ROOT, "%02x", v & 255));
        return id.toString();
    }
}
