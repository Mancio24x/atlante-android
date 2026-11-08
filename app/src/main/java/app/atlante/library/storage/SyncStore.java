package app.atlante.library.storage;

import app.atlante.library.model.Book;

import java.util.List;

/** The catalog operations Sync needs; LibraryStore on the phone, an in-memory list in tests. */
public interface SyncStore {
    public List<Book> snapshot();
    public Book find(String id);
    public void replace(Book updated) throws Exception;
    public Book addCatalog(Book candidate) throws Exception;
    /** Many books changed by a sync: saved together (the phone writes the library once, not once per book). */
    public default void replaceAll(List<Book> updated) throws Exception { for (Book b : updated) replace(b); }
    /**
     * Background work (Sync, Completa metadati, Indicizza i generi) copies books from a snapshot and writes them
     * minutes later. Each copy is written only if its book is still as it was ([before]: its JSON when the copy
     * was made): a change you made meanwhile (pages, state, rating, notes) stays, the copy is dropped for this run.
     * Check and write hold the library's lock together. Returns how many were written.
     */
    public default int replaceUnchanged(List<Book> copies, java.util.Map<String, String> before) throws Exception {
        if (copies.isEmpty()) return 0;
        synchronized (this) {
            java.util.ArrayList<Book> write = new java.util.ArrayList<>();
            for (Book copy : copies) {
                Book now = find(copy.id);
                if (now == null) continue;                                    // removed or merged meanwhile
                String was = before.get(copy.id);
                if (was != null && !was.equals(now.json().toString())) continue;
                write.add(copy);
            }
            replaceAll(write);
            return write.size();
        }
    }
    /** Removes a book (used only when the user confirms merging a duplicate). */
    public default void remove(Book b) throws Exception { throw new UnsupportedOperationException("remove"); }
}
