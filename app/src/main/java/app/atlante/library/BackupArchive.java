package app.atlante.library;

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
final class BackupArchive {
    static final String MANIFEST = "atlante-backup.json";
    static final String KIND = "atlante-backup";
    static final int VERSION = 1;
    static final int MANIFEST_LIMIT = 16 * 1024 * 1024;
    static final int COVER_LIMIT = 12 * 1024 * 1024;
    static final long BOOK_LIMIT = 512L * 1024 * 1024;
    static final int MAX_BOOKS = 100_000;
    private static final Pattern ENTRY = Pattern.compile("books/([0-9a-f]{64})/(cover|book\\.(epub|txt|cbz))");

    static final class Contents {
        final ArrayList<Book> books = new ArrayList<>();
        long createdAt;
        boolean includesFiles;
    }

    /** Writes the manifest first, then covers and, if requested, the imported book files. */
    static void write(OutputStream target, JSONArray catalog, File booksRoot, boolean includeFiles, long createdAt) throws Exception {
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
    static Contents readManifest(InputStream source) throws Exception {
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
    static Set<String> extract(InputStream source, Map<String, Book> wanted, File staging) throws Exception {
        HashSet<String> restored = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Matcher m = ENTRY.matcher(entry.getName());
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
