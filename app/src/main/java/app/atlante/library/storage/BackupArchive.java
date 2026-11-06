package app.atlante.library.storage;

import app.atlante.library.model.Book;
import app.atlante.library.ui.Covers;
import app.atlante.library.ebook.EbookParser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup ZIP of the private library: catalog, notes, progress, covers and optionally book files.
 * Platform independent so that it can be tested on the JVM.
 */
public final class BackupArchive {
    public static final String MANIFEST = "atlante-backup.json";
    public static final String KIND = "atlante-backup";
    public static final int VERSION = 1;
    public static final int MANIFEST_LIMIT = 16 * 1024 * 1024;
    public static final int COVER_LIMIT = 12 * 1024 * 1024;
    public static final long BOOK_LIMIT = 512L * 1024 * 1024;
    public static final int MAX_BOOKS = 100_000;
    private static final Pattern ENTRY = Pattern.compile("books/([0-9a-f]{64})/(cover|book\\.(epub|txt|cbz))");

    public static final class Contents {
        public final ArrayList<Book> books = new ArrayList<>();
        public long createdAt;
        public boolean includesFiles;
        /** What was recognized, shown in the summary (e.g. "Backup di Atlante", "Goodreads (CSV)"). */
        public String kind = "Backup di Atlante";
        /** Atlante data with its own identifiers and possibly files; otherwise new catalog sheets. */
        public boolean atlante = true;
        /** Inner ZIP that holds the backup, and the folder of the manifest inside it ("" at the root). */
        public String nested, prefix = "";
        /** Read from a ZIP (book files and covers can then be extracted from it). */
        public boolean fromZip;
        /** Covers carried inside the imported file (HTML catalogs, Calibre folders), by book id. */
        public final Map<String, byte[]> covers = new HashMap<>();
    }

    /** Writes the manifest first, then covers and, if requested, the imported book files. */
    public static void write(OutputStream target, JSONArray catalog, File booksRoot, boolean includeFiles, long createdAt) throws Exception {
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(target));
        JSONObject manifest = new JSONObject().put("format", KIND).put("version", VERSION)
            .put("createdAt", createdAt).put("includesFiles", includeFiles).put("books", catalog);
        zip.putNextEntry(new ZipEntry(MANIFEST));
        zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject book = catalog.getJSONObject(i);
            String id = book.getString("id"), format = book.getString("format");
            File directory = new File(booksRoot, id);
            copy(zip, new File(directory, "cover"), "books/" + id + "/cover");
            if (includeFiles && !format.equals("CATALOGO")) {
                String name = "book." + format.toLowerCase(Locale.ROOT);
                copy(zip, new File(directory, name), "books/" + id + "/" + name);
            }
        }
        zip.finish();
        zip.flush();
    }

    private static void copy(ZipOutputStream zip, File file, String name) throws IOException {
        if (!file.isFile()) return;
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = in.read(buffer)) != -1) zip.write(buffer, 0, count);
        }
        zip.closeEntry();
    }

    /** Reads and validates only the catalog; files are extracted later, after confirmation. */
    public static Contents readManifest(InputStream source) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().equals(MANIFEST)) continue;
                JSONObject manifest = new JSONObject(new String(EbookParser.read(zip, MANIFEST_LIMIT), StandardCharsets.UTF_8));
                if (!KIND.equals(manifest.optString("format"))) throw new IOException("Il file non è un backup di Atlante");
                if (manifest.optInt("version") > VERSION) throw new IOException("Backup creato da una versione più recente di Atlante: aggiorna l’app");
                JSONArray array = manifest.getJSONArray("books");
                if (array.length() > MAX_BOOKS) throw new IOException("Backup con troppi libri");
                Contents contents = new Contents();
                contents.createdAt = manifest.optLong("createdAt");
                contents.includesFiles = manifest.optBoolean("includesFiles");
                HashSet<String> seen = new HashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    Book book = Book.from(array.getJSONObject(i));
                    if (seen.add(book.id)) contents.books.add(book);
                }
                return contents;
            }
        } catch (JSONException e) {
            throw new IOException("Catalogo del backup non valido: " + e.getMessage(), e);
        }
        throw new IOException("Il file non è un backup di Atlante");
    }

    /**
     * Extracts covers and book files of the wanted books into staging/&lt;id&gt;/.
     * Unknown, unsafe or mismatching entries are skipped; book files must match their SHA-256 identity.
     * Returns the identifiers whose book file was restored.
     */
    public static Set<String> extract(InputStream source, Map<String, Book> wanted, File staging) throws Exception {
        return extract(source, wanted, staging, null, "");
    }

    /** As above, for a backup kept in a folder ({@code prefix}) or inside another ZIP ({@code nested}). */
    public static Set<String> extract(InputStream source, Map<String, Book> wanted, File staging, String nested, String prefix) throws Exception {
        try (ZipInputStream outer = new ZipInputStream(new BufferedInputStream(source))) {
            if (nested == null) return extractEntries(outer, wanted, staging, prefix);
            ZipEntry entry;
            while ((entry = outer.getNextEntry()) != null)
                if (entry.getName().equals(nested)) return extractEntries(new ZipInputStream(outer), wanted, staging, prefix);
        }
        return new HashSet<>();
    }

    private static Set<String> extractEntries(ZipInputStream zip, Map<String, Book> wanted, File staging, String prefix) throws Exception {
        HashSet<String> restored = new HashSet<>();
        {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!prefix.isEmpty()) { if (!name.startsWith(prefix)) continue; name = name.substring(prefix.length()); }
                Matcher m = ENTRY.matcher(name);
                if (entry.isDirectory() || !m.matches()) continue;
                Book book = wanted.get(m.group(1));
                if (book == null) continue;
                boolean cover = m.group(2).equals("cover");
                if (!cover && !book.format.equalsIgnoreCase(m.group(3))) continue;
                File directory = new File(staging, book.id);
                File target = new File(directory, m.group(2));
                if (target.exists()) continue;
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Impossibile preparare il ripristino");
                File partial = new File(directory, m.group(2) + ".part");
                String hash = copyLimited(zip, partial, cover ? COVER_LIMIT : BOOK_LIMIT);
                if (!cover && !hash.equals(book.id)) { partial.delete(); continue; }
                if (!partial.renameTo(target)) throw new IOException("Impossibile preparare il ripristino");
                if (!cover) restored.add(book.id);
            }
        }
        return restored;
    }

    private static String copyLimited(InputStream in, File file, long limit) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[32768]; int count; long total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IOException("File del backup troppo grande");
                digest.update(buffer, 0, count); out.write(buffer, 0, count);
            }
        } catch (Exception e) { file.delete(); throw e; }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
