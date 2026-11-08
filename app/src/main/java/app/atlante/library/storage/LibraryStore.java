package app.atlante.library.storage;

import app.atlante.library.model.Book;
import app.atlante.library.ebook.EbookParser;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.AtomicFile;
import org.json.JSONArray;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public final class LibraryStore implements SyncStore {
    public final ArrayList<Book> books = new ArrayList<>();
    private final Context context;
    private final File root;
    private final AtomicFile catalog;
    /** Grows at every save: a screen that drew an older version knows the books changed elsewhere (a widget). */
    private volatile long version;

    private static LibraryStore shared;
    /**
     * The library's own data (library.json, a backup's manifest, an Atlante catalog): what was saved must open again.
     * It was 16 MB, which a big library with plots, notes and sessions passes: it saved, then never opened.
     */
    public static final int DATA_LIMIT = 256 * 1024 * 1024;

    /**
     * The one library of the process. The app, the home screen widgets and the background work read and write
     * the same books, so a page added from a widget is never overwritten by the app's next save (or the reverse).
     */
    public static synchronized LibraryStore shared(Context context) throws Exception {
        if (shared == null) shared = new LibraryStore(context.getApplicationContext());
        return shared;
    }

    public long version() { return version; }

    public LibraryStore(Context context) throws Exception {
        this.context = context.getApplicationContext();
        root = new File(context.getFilesDir(), "books");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Impossibile creare la libreria");
        catalog = new AtomicFile(new File(context.getFilesDir(), "library.json"));
        // AtomicFile restores a previous valid catalog after an interrupted write.
        if (catalog.getBaseFile().exists() || new File(catalog.getBaseFile()+".bak").exists()) {
            try (InputStream in = catalog.openRead()) {
                JSONArray array = new JSONArray(new String(EbookParser.read(in, DATA_LIMIT), "UTF-8"));
                for (int i = 0; i < array.length(); i++) books.add(Book.from(array.getJSONObject(i)));
            }
        }
    }

    public synchronized void save() throws Exception {
        JSONArray array = new JSONArray();
        for (Book b : books) array.put(b.json());
        FileOutputStream out = null;
        try {
            out = catalog.startWrite();
            out.write(array.toString().getBytes("UTF-8"));
            catalog.finishWrite(out);
        } catch (Exception e) {
            if (out != null) catalog.failWrite(out);
            throw e;
        }
        version++;
        app.atlante.library.widget.Widgets.libraryChanged(context);
        app.atlante.library.catalog.LibraryIndex.libraryChanged(context);   // the FTS4 index of the library follows
    }

    public File directory(Book b) { return new File(root, b.id); }
    public File original(Book b) { return new File(directory(b), "book." + b.format.toLowerCase(Locale.ROOT)); }
    public File cover(Book b) { return new File(directory(b), "cover"); }
    @Override public synchronized Book find(String id) {
        for (Book b : books) if (b.id.equals(id)) return b;
        return null;
    }
    @Override public synchronized ArrayList<Book> snapshot() { return new ArrayList<>(books); }

    public String name(Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { /* Some providers do not expose a display name. */ }
        String last = uri.getLastPathSegment();
        return last != null && last.contains(".") ? last.substring(last.lastIndexOf('/') + 1) : "libro";
    }

    public Book importBook(Uri uri) throws Exception {
        String name = name(uri);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
        if (!ext.matches("EPUB|TXT|CBZ|AZW3")) throw new IOException(name + ": scegli EPUB, TXT, CBZ o AZW3");
        File temporary = File.createTempFile("import-", ".tmp", context.getCacheDir());
        File dest = null;
        boolean committed = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(temporary)) {
                if (in == null) throw new IOException("File non disponibile");
                byte[] buffer = new byte[32768]; int count; long total = 0;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > 512L * 1024 * 1024) throw new IOException("File oltre 512 MB");
                    digest.update(buffer, 0, count); out.write(buffer, 0, count);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            String id = hex.toString();
            String fileHash=id;
            synchronized (this) {
                for(Book existing:books) if(existing.meta.optString("fileSha256",existing.id).equals(fileHash)) {
                    if(original(existing).isFile())return null;
                    // Stage under a separate ID; linking is confirmed in the preview.
                    id=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
                    break;
                }
            }
            Book book = new Book(); book.id = id; book.format = ext; book.meta.put("fileSha256",fileHash);
            book.title = name.substring(0, dot);
            dest = directory(book);
            // The original URI is read only; imported copies stay in the private app directory.
            if (dest.exists()) deleteTree(dest);
            if (!dest.mkdirs()) throw new IOException("Impossibile salvare il libro");
            if (!temporary.renameTo(original(book))) throw new IOException("Impossibile copiare il libro");
            EbookParser.metadata(original(book), cover(book), book);
            committed = true;
            return book;
        } finally {
            temporary.delete();
            if (!committed && dest != null) deleteTree(dest);
        }
    }

    @Override public synchronized Book addCatalog(Book candidate) throws Exception {
        Book existing = find(candidate.id);
        if (existing != null) return existing;
        if (candidate.onlineCover != null) {
            if (!directory(candidate).isDirectory() && !directory(candidate).mkdirs()) throw new IOException("Impossibile salvare la copertina");
            try (OutputStream out = new FileOutputStream(cover(candidate))) { out.write(candidate.onlineCover); }
        }
        books.add(candidate);
        try { save(); } catch (Exception e) { books.remove(candidate); throw e; }
        return candidate;
    }

    @Override public synchronized void replace(Book updated) throws Exception {
        Book previous = find(updated.id);
        if (previous == null) throw new IOException("Libro non disponibile");
        int index = books.indexOf(previous);
        books.set(index, updated);
        try { save(); } catch (Exception e) { books.set(index, previous); throw e; }
    }

    /** One write for many changed books; on failure every book goes back to how it was. */
    @Override public synchronized void replaceAll(List<Book> updated) throws Exception {
        if (updated.isEmpty()) return;
        ArrayList<Book> before = new ArrayList<>(books);
        for (Book u : updated) { Book previous = find(u.id); if (previous != null) books.set(books.indexOf(previous), u); }
        try { save(); } catch (Exception e) { books.clear(); books.addAll(before); throw e; }
    }

    public synchronized void saveDownloadedCover(Book book, byte[] bytes) throws Exception {
        if (find(book.id) == null) return;
        if (!directory(book).isDirectory() && !directory(book).mkdirs()) return;
        AtomicFile image = new AtomicFile(cover(book)); FileOutputStream out = null;
        try { out=image.startWrite(); out.write(bytes); image.finishWrite(out); }
        catch(Exception e) { if(out != null) image.failWrite(out); throw e; }
    }

    /** Private directory on the same volume as the library, so restored files can be moved in place. */
    public File staging() { return new File(context.getFilesDir(), "restore"); }

    public static void clear(File directory) { deleteTree(directory); }

    /** Adds restored books that are not in the catalog yet; existing books are never overwritten. */
    public synchronized int restore(List<Book> incoming, File staging) throws Exception {
        ArrayList<Book> added = new ArrayList<>();
        try {
            for (Book book : incoming) {
                // Never a path as id: "../" would point the restore at folders outside the book's own one.
                if (book.id == null || !book.id.matches("[0-9a-f]{64}")) continue;
                if (find(book.id) != null) continue;
                File source = new File(staging, book.id), target = directory(book);
                if (source.isDirectory()) {
                    if (target.exists()) deleteTree(target);
                    if (!source.renameTo(target)) throw new IOException("Impossibile ripristinare " + book.title);
                }
                books.add(book); added.add(book);
            }
            save();
        } catch (Exception e) {
            books.removeAll(added);
            for (Book book : added) deleteTree(directory(book));
            throw e;
        }
        return added.size();
    }

    /**
     * Gives an imported file to a book already in the library that has none (a record added by hand, from a
     * catalog or from a backup): the existing record keeps all its data; only empty fields, the chapters and a
     * missing cover come from the file.
     */
    public synchronized Book attachFile(Book candidate, Book existing) throws Exception {
        Book target = find(existing.id);
        if (target == null) throw new IOException("Libro non disponibile");
        if (!target.format.equals("CATALOGO") && original(target).exists()) throw new IOException("Questo libro ha già un file");
        File from = original(candidate);
        if (!from.isFile()) throw new IOException("File importato non disponibile");
        Book before = Book.from(target.json());
        String oldFormat = target.format;
        target.format = candidate.format;
        File dir = directory(target);
        if (!dir.isDirectory() && !dir.mkdirs()) { target.format = oldFormat; throw new IOException("Impossibile salvare il file"); }
        File to = original(target);
        if (!from.renameTo(to)) { target.format = oldFormat; throw new IOException("Impossibile collegare il file"); }
        boolean coverMoved = !cover(target).isFile() && cover(candidate).isFile() && cover(candidate).renameTo(cover(target));
        target.meta.put("fileSha256", candidate.meta.optString("fileSha256", candidate.id));
        if (target.isbn.isEmpty()) target.isbn = candidate.isbn;
        if (target.publisher.isEmpty()) target.publisher = candidate.publisher;
        if (target.language.isEmpty()) target.language = candidate.language;
        if (target.year.isEmpty()) target.year = candidate.year;
        if (target.description.isEmpty()) target.description = candidate.description;
        if (target.chapters.isEmpty()) target.chapters.addAll(candidate.chapters);
        try { save(); }
        catch (Exception e) {
            to.renameTo(from); if (coverMoved) cover(target).renameTo(cover(candidate));
            books.set(books.indexOf(target), before); throw e;
        }
        deleteTree(directory(candidate));
        return target;
    }

    /**
     * Two records of the same work become one, in one save: [merged] (the kept record, already holding the other's
     * data, see BibliographicEngine.merged) replaces the kept one and [removed] goes. Files: the other's eBook comes
     * here when this record has none (else it is kept as another edition's file), its cover becomes this record's
     * cover when this has none (else an alternative cover). The removed record's folder is moved, not deleted, to
     * files/merged/ and the merge is written in merge-history.json: "Annulla l'ultima unione" brings it back.
     * On any failure nothing changes.
     */
    public synchronized Book merge(Book merged, Book removed) throws Exception {
        Book keep = find(merged.id), other = find(removed.id);
        if (keep == null || other == null || keep == other) throw new IOException("Unione non più possibile: uno dei due libri è cambiato");
        File dir = directory(keep); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Impossibile preparare la cartella del libro");
        ArrayList<File[]> moves = new ArrayList<>();
        Book result = Book.from(merged.json());
        try {
            File otherFile = original(other);
            if (!other.format.equals("CATALOGO") && otherFile.isFile()) {
                if (keep.format.equals("CATALOGO") || !original(keep).exists()) {
                    result.format = other.format;
                    File to = original(result); move(otherFile, to, moves);
                    result.meta.put("fileSha256", other.meta.optString("fileSha256", other.id));
                } else {
                    File to = new File(new File(dir, "editions"), other.id + "." + other.format.toLowerCase(Locale.ROOT)); move(otherFile, to, moves);
                    tagEditionFile(result, other.id, "editions/" + to.getName());
                }
            }
            File otherCover = cover(other);
            if (otherCover.isFile()) {
                if (!cover(keep).isFile()) move(otherCover, cover(result), moves);
                else move(otherCover, new File(new File(dir, "covers"), "alt-" + other.id), moves);
            }
            ArrayList<Book> before = new ArrayList<>(books);
            books.set(books.indexOf(keep), result); books.remove(other);
            try { save(); } catch (Exception e) { books.clear(); books.addAll(before); throw e; }
        } catch (Exception e) {
            for (int i = moves.size() - 1; i >= 0; i--) moves.get(i)[1].renameTo(moves.get(i)[0]);
            throw e;
        }
        // Kept for "Annulla l'ultima unione": the other's folder and both records as they were.
        File trash = new File(new File(context.getFilesDir(), "merged"), other.id);
        if (directory(other).isDirectory()) { trash.getParentFile().mkdirs(); deleteTree(trash); directory(other).renameTo(trash); }
        try { MergeHistory.add(context, keep, other, moves); } catch (Exception ignored) { /* the merge itself is saved */ }
        return result;
    }

    private static void tagEditionFile(Book b, String otherId, String path) throws org.json.JSONException {
        org.json.JSONArray eds = b.meta.optJSONArray("editions");
        for (int i = 0; eds != null && i < eds.length(); i++) { org.json.JSONObject e = eds.optJSONObject(i); if (e != null && otherId.equals(e.optString("from"))) { e.put("file", path); return; } }
    }

    private static void move(File from, File to, ArrayList<File[]> moves) throws IOException {
        if (!to.getParentFile().isDirectory() && !to.getParentFile().mkdirs()) throw new IOException("Impossibile spostare " + from.getName());
        if (!from.renameTo(to)) throw new IOException("Impossibile spostare " + from.getName());
        moves.add(new File[]{from, to});
    }

    /** Undoes a merge written in merge-history.json: both records as they were, files back in their place. */
    public synchronized void undoMerge(org.json.JSONObject entry) throws Exception {
        Book keep = Book.from(entry.getJSONObject("keep")), other = Book.from(entry.getJSONObject("other"));
        if (find(keep.id) == null) throw new IOException("Il libro unito non c’è più");
        if (find(other.id) != null) throw new IOException("Unione già annullata");
        File trash = new File(new File(context.getFilesDir(), "merged"), other.id);
        if (trash.isDirectory()) { File d = directory(other); d.getParentFile().mkdirs(); trash.renameTo(d); }
        org.json.JSONArray moves = entry.optJSONArray("moves");
        for (int i = moves == null ? -1 : moves.length() - 1; i >= 0; i--) {
            org.json.JSONArray m = moves.getJSONArray(i); File from = new File(m.getString(0)), to = new File(m.getString(1));
            if (to.isFile()) { from.getParentFile().mkdirs(); to.renameTo(from); }
        }
        ArrayList<Book> before = new ArrayList<>(books);
        books.set(books.indexOf(find(keep.id)), keep); books.add(other);
        try { save(); } catch (Exception e) { books.clear(); books.addAll(before); throw e; }
    }

    /**
     * An edition of a work already here (an EPUB when the record already has a file, a sheet found online): it joins
     * the existing record as one of its editions (ISBN, publisher, file in editions/), never as a second record.
     */
    public synchronized Book addEdition(Book candidate, Book existing) throws Exception {
        Book target = find(existing.id);
        if (target == null) throw new IOException("Libro non disponibile");
        Book result = app.atlante.library.catalog.BibliographicEngine.INSTANCE.merged(target, candidate, new app.atlante.library.catalog.BibliographicEngine.Choices());
        File dir = directory(target); ArrayList<File[]> moves = new ArrayList<>();
        File from = original(candidate);
        try {
            if (!candidate.format.equals("CATALOGO") && from.isFile()) {
                File to = new File(new File(dir, "editions"), candidate.id + "." + candidate.format.toLowerCase(Locale.ROOT));
                move(from, to, moves); tagEditionFile(result, candidate.id, "editions/" + to.getName());
            }
            if (cover(candidate).isFile() && cover(target).isFile()) move(cover(candidate), new File(new File(dir, "covers"), "alt-" + candidate.id), moves);
            else if (cover(candidate).isFile()) move(cover(candidate), cover(result), moves);
            Book before = target; books.set(books.indexOf(target), result);
            try { save(); } catch (Exception e) { books.set(books.indexOf(result), before); throw e; }
        } catch (Exception e) {
            for (int i = moves.size() - 1; i >= 0; i--) moves.get(i)[1].renameTo(moves.get(i)[0]);
            throw e;
        }
        if (!candidate.id.equals(target.id)) deleteTree(directory(candidate));
        return result;
    }

    public synchronized void discardCandidate(Book candidate) {
        if (find(candidate.id) == null) deleteTree(directory(candidate));
    }

    @Override public synchronized void remove(Book b) throws Exception {
        int index = books.indexOf(b);
        books.remove(b);
        try { save(); }
        catch (Exception e) { books.add(Math.max(0, index), b); throw e; }
        deleteTree(directory(b));
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        f.delete();
    }
}
