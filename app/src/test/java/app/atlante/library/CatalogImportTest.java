package app.atlante.library;

import app.atlante.library.storage.BackupArchive;
import app.atlante.library.model.Book;
import app.atlante.library.storage.CatalogImport;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class CatalogImportTest {
    private static BackupArchive.Contents read(String text, String name) throws Exception {
        return CatalogImport.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), name);
    }
    private static byte[] zip(String[][] entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (String[] e : entries) { out.putNextEntry(new ZipEntry(e[0])); out.write(e[1].getBytes(StandardCharsets.UTF_8)); out.closeEntry(); }
        }
        return bytes.toByteArray();
    }

    @Test public void goodreadsExportKeepsShelfRatingDatesAndReview() throws Exception {
        String csv = "Book Id,Title,Author,Author l-f,ISBN,ISBN13,My Rating,Publisher,Number of Pages,Year Published,Date Read,Exclusive Shelf,My Review\n"
            + "1,\"Il nome della rosa\",Umberto Eco,\"Eco, Umberto\",\"=\"\"8845292614\"\"\",\"=\"\"9788845292613\"\"\",5,Bompiani,503,1980,2024/03/02,read,\"Bellissimo, \"\"giallo\"\"\nmedievale\"\n"
            + "2,Dune,Frank Herbert,\"Herbert, Frank\",,,0,,,,,currently-reading,\n"
            + "3,Emma,Jane Austen,\"Austen, Jane\",,,0,,,,,to-read,\n";
        BackupArchive.Contents c = read(csv, "goodreads_library_export.csv");
        assertEquals("Goodreads (CSV)", c.kind); assertFalse(c.atlante); assertEquals(3, c.books.size());
        Book eco = c.books.get(0);
        assertEquals("Il nome della rosa", eco.title); assertEquals("Umberto Eco", eco.author); assertEquals("9788845292613", eco.isbn);
        assertEquals(5, eco.rating); assertEquals(Book.READ, eco.status); assertEquals("2024-03-02", eco.finished);
        assertEquals(503, eco.totalPages); assertEquals(503, eco.currentPage); assertEquals("1980", eco.year);
        assertEquals("Bellissimo, \"giallo\"\nmedievale", eco.notes); assertEquals("CATALOGO", eco.format);
        assertEquals(Book.READING, c.books.get(1).status); assertEquals(Book.TO_READ, c.books.get(2).status);
        assertTrue(eco.id.matches("[0-9a-f]{64}"));
    }

    @Test public void storyGraphAndItalianSemicolonLists() throws Exception {
        BackupArchive.Contents sg = read("Title,Authors,ISBN/UID,Read Status,Star Rating,Last Date Read\nCirce,Madeline Miller,9781408890080,read,4.25,2023/08/01\n", "sg.csv");
        assertEquals("StoryGraph (CSV)", sg.kind); assertEquals(4, sg.books.get(0).rating); assertEquals(Book.READ, sg.books.get(0).status);
        BackupArchive.Contents it = read("﻿Titolo;Autore;Saga;Numero;Stato;Voto;Trama\nLa nona casa;Leigh Bardugo;Alex Stern;1;Letto;4;Yale e i suoi segreti\n", "libri.csv");
        Book b = it.books.get(0);
        assertEquals("Elenco CSV", it.kind); assertEquals("Alex Stern", b.series); assertEquals(1, b.number, 0);
        assertEquals(Book.READ, b.status); assertEquals("Yale e i suoi segreti", b.description);
    }

    @Test public void atlanteHtmlCatalogWithEmbeddedCovers() throws Exception {
        String html = "<!DOCTYPE html><html><body><script id=\"library-data\" type=\"application/json\">"
            + "{\"books\":[{\"title\":\"La peste\",\"author\":\"Albert Camus\",\"saga\":\"None\",\"order\":null,\"tags\":[\"Classici\",\"Romanzi\"],"
            + "\"synopsis\":\"Orano e l'epidemia\",\"cover\":\"data:image/jpeg;base64,/9j/4AAQ\",\"files\":[{\"format\":\"EPUB\"}]}]}</script></body></html>";
        BackupArchive.Contents c = read(html, "Atlante_dei_libri.html");
        assertEquals("Catalogo HTML di Atlante", c.kind);
        Book b = c.books.get(0);
        assertEquals("", b.series); assertEquals("Classici, Romanzi", b.tags); assertEquals("Orano e l'epidemia", b.description);
        assertEquals("Ebook", b.medium); assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0x10}, c.covers.get(b.id));
    }

    @Test public void calibreLibraryZipWithOpfAndCover() throws Exception {
        String opf = "<?xml version='1.0' encoding='utf-8'?><package xmlns='http://www.idpf.org/2007/opf'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/' xmlns:opf='http://www.idpf.org/2007/opf'>"
            + "<dc:title>Il Gattopardo</dc:title><dc:creator opf:role='aut'>Giuseppe Tomasi di Lampedusa</dc:creator>"
            + "<dc:description>&lt;p&gt;Sicilia, 1860&lt;/p&gt;</dc:description><dc:publisher>Feltrinelli</dc:publisher><dc:date>1958-01-01T00:00:00+00:00</dc:date>"
            + "<dc:identifier opf:scheme='ISBN'>9788807881909</dc:identifier><dc:subject>Classici</dc:subject>"
            + "<meta name='calibre:series' content='Romanzi'/><meta name='calibre:series_index' content='2.0'/><meta name='calibre:rating' content='8'/></metadata></package>";
        byte[] archive = zip(new String[][]{{"Calibre Library/Tomasi/Il Gattopardo (7)/metadata.opf", opf}, {"Calibre Library/Tomasi/Il Gattopardo (7)/cover.jpg", "JPEG"}, {"__MACOSX/._x", "junk"}});
        BackupArchive.Contents c = CatalogImport.read(new ByteArrayInputStream(archive), "calibre.zip");
        assertEquals("Libreria Calibre (metadata.opf)", c.kind);
        Book b = c.books.get(0);
        assertEquals("Il Gattopardo", b.title); assertEquals("Giuseppe Tomasi di Lampedusa", b.author); assertEquals("Sicilia, 1860", b.description);
        assertEquals("1958", b.year); assertEquals("9788807881909", b.isbn); assertEquals("Romanzi", b.series); assertEquals(2, b.number, 0);
        assertEquals(4, b.rating); assertEquals("JPEG", new String(c.covers.get(b.id), StandardCharsets.UTF_8));
    }

    @Test public void atlanteBackupInAFolderOrInsideAnotherZip() throws Exception {
        String manifest = "{\"format\":\"atlante-backup\",\"version\":1,\"books\":[{\"id\":\"" + new String(new char[64]).replace('\0', 'a') + "\",\"format\":\"CATALOGO\",\"title\":\"X\"}]}";
        BackupArchive.Contents folder = CatalogImport.read(new ByteArrayInputStream(zip(new String[][]{{"Backup/atlante-backup.json", manifest}})), "b.zip");
        assertTrue(folder.atlante); assertTrue(folder.fromZip); assertEquals("Backup/", folder.prefix); assertEquals(1, folder.books.size());
        ByteArrayOutputStream outer = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(outer)) { out.putNextEntry(new ZipEntry("dentro.zip")); out.write(zip(new String[][]{{"atlante-backup.json", manifest}})); out.closeEntry(); }
        BackupArchive.Contents nested = CatalogImport.read(new ByteArrayInputStream(outer.toByteArray()), "fuori.zip");
        assertEquals("dentro.zip", nested.nested); assertEquals(1, nested.books.size());
        BackupArchive.Contents plain = read(manifest, "atlante-backup.json");
        assertTrue(plain.atlante); assertFalse(plain.fromZip);
    }

    @Test public void unknownFilesExplainWhatCanBeImported() throws Exception {
        try { read("solo una riga di testo", "nota.txt"); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("Formato non riconosciuto")); }
    }
}
