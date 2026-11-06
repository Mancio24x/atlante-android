package app.atlante.library.storage;

import app.atlante.library.model.Book;

import java.util.List;

/** The catalog operations Sync needs; LibraryStore on the phone, an in-memory list in tests. */
public interface SyncStore {
    public List<Book> snapshot();
    public Book find(String id);
    public void replace(Book updated) throws Exception;
    public Book addCatalog(Book candidate) throws Exception;
}
