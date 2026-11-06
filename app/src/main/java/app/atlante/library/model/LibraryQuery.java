package app.atlante.library.model;

import app.atlante.library.ebook.EbookParser;
import app.atlante.library.online.SeriesCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Local catalog views; never alters the catalog or the reader's position. */
public final class LibraryQuery {
    /** Tabs: "Tutti i libri", one per reading state ("Da leggere", "In lettura", "Letti"), or a view such as "Mappa". */
    public static ArrayList<Book> select(List<Book> books, String query, String tab,
            String genre, String format, String order) { return select(books, query, tab, genre, format, order, ""); }

    /** Saga filter: "" all, {@link #IN_SAGA} books of a saga, {@link #SINGLE} books without saga, or a saga name. */
    public static final String IN_SAGA = "Solo libri di una saga", SINGLE = "Libri singoli";

    /** Genre filter on the normalized genre ("Epic Fantasy" counts as Fantasy); a raw label still matches itself. */
    public static boolean genreMatches(Book b, String genre) {
        if (genre.isEmpty()) return true;
        if (genre.equalsIgnoreCase(b.genre)) return true;
        String main = app.atlante.library.saga.Genres.normalize(b.genre);
        if (genre.equals(OTHER_GENRE)) return main == null;
        return genre.equals(main);
    }
    public static final String OTHER_GENRE = "Altri generi";

    public static ArrayList<Book> select(List<Book> books, String query, String tab,
            String genre, String format, String order, String saga) {
        ArrayList<Book> result = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT).trim();
        for (Book b : books) {
            if (!(b.title + " " + b.author + " " + b.series + " " + b.genre + " " + b.isbn + " " + b.tags + " " + b.subtitle + " " + b.notes + " " + b.journal.toString())
                    .toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (tab.equals("Da leggere") && !b.status.equals(Book.TO_READ)) continue;
            if (tab.equals("In lettura") && !b.status.equals(Book.READING)) continue;
            if (tab.equals("Preferiti") && !b.journal.optBoolean("favorite")) continue;
            if (tab.equals(Book.PAUSED) && !b.status.equals(Book.PAUSED)) continue;
            if (tab.startsWith("Scaffale: ") && !ReadingJournal.inCollection(b, tab.substring(10))) continue;
            if (tab.equals("Letti") && !b.status.equals(Book.READ)) continue;
            if (!genreMatches(b, genre)) continue;
            if (saga.equals(IN_SAGA) && b.series.isEmpty()) continue;
            if (saga.equals(SINGLE) && !b.series.isEmpty()) continue;
            if (!saga.isEmpty() && !saga.equals(IN_SAGA) && !saga.equals(SINGLE) && !saga.equalsIgnoreCase(b.series)) continue;
            if (!format.isEmpty() && !format.equals(b.format)) continue;
            result.add(b);
        }
        Comparator<Book> title = (a, b) -> EbookParser.naturalCompare(a.title, b.title);
        Comparator<Book> comparator = title;
        if (order.equals("Autore")) comparator = ((Comparator<Book>) (a, b) -> EbookParser.naturalCompare(a.author, b.author)).thenComparing(title);
        if (order.equals("Saga")) comparator = ((Comparator<Book>) (a, b) -> {
            if (a.series.isEmpty() != b.series.isEmpty()) return a.series.isEmpty() ? 1 : -1;
            int series = EbookParser.naturalCompare(a.series, b.series);
            return series != 0 ? series : SeriesCatalog.compareNumber(a.number, b.number);
        }).thenComparing(title);
        result.sort(comparator);
        return result;
    }
}
