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
}
