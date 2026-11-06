package app.atlante.library;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.AtomicFile;
import org.json.JSONArray;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

final class LibraryStore {
    final ArrayList<Book> books = new ArrayList<>();
    private final Context context;
    private final File root;
    private final AtomicFile catalog;

    LibraryStore(Context context) throws Exception {
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

    synchronized void save() throws Exception {
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
    }

    File directory(Book b) { return new File(root, b.id); }
    File original(Book b) { return new File(directory(b), "book." + b.format.toLowerCase(Locale.ROOT)); }
    File cover(Book b) { return new File(directory(b), "cover"); }
    synchronized Book find(String id) {
        for (Book b : books) if (b.id.equals(id)) return b;
        return null;
    }
    synchronized ArrayList<Book> snapshot() { return new ArrayList<>(books); }

    String name(Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { /* Some providers do not expose a display name. */ }
        return "libro";
    }

    Book importBook(Uri uri) throws Exception {
        String name = name(uri);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
        if (!ext.matches("EPUB|TXT|CBZ")) throw new IOException(name + ": scegli EPUB, TXT o CBZ");
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
            synchronized (this) {
                Book existing = find(id);
                if (existing != null) {
                    // A book restored from a backup without files gets its file back here.
                    if (existing.format.equals(ext) && !original(existing).exists()) {
                        if (!directory(existing).isDirectory() && !directory(existing).mkdirs()) throw new IOException("Impossibile salvare il libro");
                        if (!temporary.renameTo(original(existing))) throw new IOException("Impossibile copiare il libro");
                        existing.reattached = true;
                        return existing;
                    }
                    return null;
                }
            }
            Book book = new Book(); book.id = id; book.format = ext;
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

    synchronized Book addCatalog(Book candidate) throws Exception {
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

    synchronized void replace(Book updated) throws Exception {
        Book previous = find(updated.id);
        if (previous == null) throw new IOException("Libro non disponibile");
        int index = books.indexOf(previous);
        books.set(index, updated);
        try { save(); } catch (Exception e) { books.set(index, previous); throw e; }
    }

    synchronized void saveDownloadedCover(Book book, byte[] bytes) throws Exception {
        if (find(book.id) == null) return;
        if (!directory(book).isDirectory() && !directory(book).mkdirs()) return;
        AtomicFile image = new AtomicFile(cover(book)); FileOutputStream out = null;
        try { out=image.startWrite(); out.write(bytes); image.finishWrite(out); }
        catch(Exception e) { if(out != null) image.failWrite(out); throw e; }
    }

    /** Private directory on the same volume as the library, so restored files can be moved in place. */
    File staging() { return new File(context.getFilesDir(), "restore"); }

    static void clear(File directory) { deleteTree(directory); }

    /** Adds restored books that are not in the catalog yet; existing books are never overwritten. */
    synchronized int restore(List<Book> incoming, File staging) throws Exception {
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

    synchronized void discardCandidate(Book candidate) {
        if (find(candidate.id) == null) deleteTree(directory(candidate));
    }

    synchronized void remove(Book b) throws Exception {
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
