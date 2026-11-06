package app.atlante.library;

import app.atlante.library.storage.BackupArchive;
import app.atlante.library.model.Book;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static String sha(byte[] data) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    private Book book(String id, String format) {
        Book b = new Book(); b.id = id; b.format = format; b.title = "Titolo " + format;
        b.notes = "Nota"; b.currentPage = 42; b.page = 3; return b;
    }

    private File library(Book... books) throws Exception {
        File root = temp.newFolder("books");
        for (Book b : books) {
            File dir = new File(root, b.id); dir.mkdirs();
            Files.write(new File(dir, "cover").toPath(), new byte[]{9, 9});
            if (!b.format.equals("CATALOGO")) Files.write(new File(dir, "book." + b.format.toLowerCase(Locale.ROOT)).toPath(), b.title.getBytes(StandardCharsets.UTF_8));
        }
        return root;
    }

    private byte[] backup(File root, boolean files, Book... books) throws Exception {
        JSONArray catalog = new JSONArray();
        for (Book b : books) catalog.put(b.json());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupArchive.write(out, catalog, root, files, 1234L);
        return out.toByteArray();
    }

    private static Map<String, Book> byId(List<Book> books) {
        LinkedHashMap<String, Book> map = new LinkedHashMap<>();
        for (Book b : books) map.put(b.id, b);
        return map;
    }

    @Test public void roundTripKeepsNotesProgressCoversAndFiles() throws Exception {
        Book epub = book(sha("Titolo EPUB".getBytes(StandardCharsets.UTF_8)), "EPUB");
        Book sheet = book(new String(new char[64]).replace('\0', 'b'), "CATALOGO");
        byte[] zip = backup(library(epub, sheet), true, epub, sheet);

        BackupArchive.Contents contents = BackupArchive.readManifest(new ByteArrayInputStream(zip));
        assertEquals(2, contents.books.size()); assertTrue(contents.includesFiles); assertEquals(1234L, contents.createdAt);
        assertEquals("Nota", contents.books.get(0).notes); assertEquals(42, contents.books.get(0).currentPage); assertEquals(3, contents.books.get(0).page);

        File staging = temp.newFolder("staging");
        Set<String> restored = BackupArchive.extract(new ByteArrayInputStream(zip), byId(contents.books), staging);
        assertEquals(Collections.singleton(epub.id), restored);
        assertTrue(new File(staging, epub.id + "/book.epub").isFile());
        assertTrue(new File(staging, sheet.id + "/cover").isFile());
    }

    @Test public void lightBackupLeavesBookFilesOut() throws Exception {
        Book epub = book(sha("Titolo EPUB".getBytes(StandardCharsets.UTF_8)), "EPUB");
        byte[] zip = backup(library(epub), false, epub);
        File staging = temp.newFolder("staging");
        assertTrue(BackupArchive.extract(new ByteArrayInputStream(zip), byId(Collections.singletonList(epub)), staging).isEmpty());
        assertTrue(new File(staging, epub.id + "/cover").isFile());
        assertFalse(new File(staging, epub.id + "/book.epub").exists());
    }

    @Test public void rejectsUnsafeEntriesAndTamperedFiles() throws Exception {
        Book epub = book(new String(new char[64]).replace('\0', 'c'), "EPUB");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            JSONObject manifest = new JSONObject().put("format", "atlante-backup").put("version", 1)
                .put("books", new JSONArray().put(epub.json()));
            zip.putNextEntry(new ZipEntry("atlante-backup.json")); zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            for (String name : new String[]{"../evil", "books/" + epub.id + "/../../evil", "books/" + epub.id + "/book.epub", "books/" + epub.id + "/book.cbz"}) {
                zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[]{1, 2, 3}); zip.closeEntry();
            }
        }
        File staging = temp.newFolder("staging");
        Set<String> restored = BackupArchive.extract(new ByteArrayInputStream(bytes.toByteArray()), byId(Collections.singletonList(epub)), staging);
        assertTrue(restored.isEmpty());
        assertFalse(new File(staging.getParentFile(), "evil").exists());
        assertFalse(new File(staging, epub.id + "/book.epub").exists());
        assertFalse(new File(staging, epub.id + "/book.cbz").exists());
    }

    @Test public void rejectsZipWithoutManifest() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry("mimetype")); zip.closeEntry(); }
        try { BackupArchive.readManifest(new ByteArrayInputStream(bytes.toByteArray())); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("non è un backup")); }
    }

    @Test public void rejectsNewerBackupVersion() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("atlante-backup.json"));
            zip.write(new JSONObject().put("format", "atlante-backup").put("version", 99).put("books", new JSONArray()).toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        try { BackupArchive.readManifest(new ByteArrayInputStream(bytes.toByteArray())); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("più recente")); }
    }
}
