package app.atlante.library;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Local catalog views; never alters the catalog or the reader's position. */
final class LibraryQuery {
    /** Tabs: "Tutti i libri", one per reading state ("Da leggere", "In lettura", "Letti"), or a view such as "Mappa". */
    static ArrayList<Book> select(List<Book> books, String query, String tab,
            String genre, String format, String order) {
        ArrayList<Book> result = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT).trim();
        for (Book b : books) {
            if (!(b.title + " " + b.author + " " + b.series + " " + b.genre + " " + b.isbn + " " + b.tags + " " + b.subtitle)
                    .toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (tab.equals("Da leggere") && !b.status.equals(Book.TO_READ)) continue;
            if (tab.equals("In lettura") && !b.status.equals(Book.READING)) continue;
            if (tab.equals("Letti") && !b.status.equals(Book.READ)) continue;
            if (!genre.isEmpty() && !genre.equalsIgnoreCase(b.genre)) continue;
            if (!format.isEmpty() && !format.equals(b.format)) continue;
            result.add(b);
        }
        Comparator<Book> title = (a, b) -> EbookParser.naturalCompare(a.title, b.title);
        Comparator<Book> comparator = title;
        if (order.equals("Autore")) comparator = ((Comparator<Book>) (a, b) -> EbookParser.naturalCompare(a.author, b.author)).thenComparing(title);
        if (order.equals("Saga")) comparator = ((Comparator<Book>) (a, b) -> {
            if (a.series.isEmpty() != b.series.isEmpty()) return a.series.isEmpty() ? 1 : -1;
            int series = EbookParser.naturalCompare(a.series, b.series);
            return series != 0 ? series : Double.compare(a.number, b.number);
        }).thenComparing(title);
        result.sort(comparator);
        return result;
    }
}
