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
                JSONArray array = new JSONArray(new String(EbookParser.read(in, 16 * 1024 * 1024), "UTF-8"));
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
