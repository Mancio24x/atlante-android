package app.atlante.library;

import java.util.List;

/** The catalog operations Sync needs; LibraryStore on the phone, an in-memory list in tests. */
interface SyncStore {
    List<Book> snapshot();
    Book find(String id);
    void replace(Book updated) throws Exception;
    Book addCatalog(Book candidate) throws Exception;
}
