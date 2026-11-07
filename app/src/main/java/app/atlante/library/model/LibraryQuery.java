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
    /** Format filter: books with more than one edition in their record (merged or imported editions). */
    public static final String MANY_EDITIONS = "Con più edizioni";

    /** From the saga graph (SagaFilters): book id → its sagas, their parents and sub-series; book id → universe. */
    public static volatile java.util.Map<String, java.util.Set<String>> graphSagas = new java.util.HashMap<>();
    public static volatile java.util.Map<String, String> graphUniverses = new java.util.HashMap<>();

    /** The book's own saga, or one of the sagas / sub-series the saga engine knows for it. */
    public static boolean sagaMatches(Book b, String saga) {
        if (saga.equalsIgnoreCase(b.series)) return true;
        java.util.Set<String> known = graphSagas.get(b.id);
        if (known != null) for (String s : known) if (s.equalsIgnoreCase(saga)) return true;
        return false;
    }

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
            String genre, String format, String order, String saga) { return select(books, query, tab, genre, format, order, saga, "", "", ""); }

    /** Language filter on the sheet's language, normalized ("ita", "it", "Italiano" are the same). */
    public static String languageOf(Book b) {
        String l = b.language.trim().toLowerCase(Locale.ROOT);
        if (l.isEmpty()) return "";
        for (String[] known : app.atlante.library.online.OnlineCatalog.LANGUAGES)
            if (!known[0].isEmpty() && (l.equals(known[0]) || l.equals(known[1]) || l.equalsIgnoreCase(known[2]) || l.startsWith(known[1] + "-"))) return known[2];
        if (l.equals("italian")) return "Italiano";
        return b.language.trim();
    }

    /** "1990–1999" for a year in the sheet (or its first publication), "" when unknown. */
    public static String decadeOf(Book b) {
        String y = b.year.isEmpty() ? b.meta.optString("firstPublished", "") : b.year;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(1[5-9]|20)\\d\\d").matcher(y);
        if (!m.find()) return "";
        int d = Integer.parseInt(m.group()) / 10 * 10;
        return d + "–" + (d + 9);
    }

    /**
     * With a query: only the books where every word is found (see {@link SearchIndex}), best match first, then
     * the chosen order. [author], [language], [decade] "" mean any.
     */
    public static ArrayList<Book> select(List<Book> books, String query, String tab,
            String genre, String format, String order, String saga, String author, String language, String decade) {
        return select(books, query, tab, genre, format, order, saga, author, language, decade, "");
    }

    /** [universe]: books whose saga belongs to this narrative universe ("" any). */
    public static ArrayList<Book> select(List<Book> books, String query, String tab,
            String genre, String format, String order, String saga, String author, String language, String decade, String universe) {
        ArrayList<Book> result = new ArrayList<>();
        for (Book b : books) {
            if (!author.isEmpty() && !SearchIndex.sameAuthor(b, author)) continue;
            if (!language.isEmpty() && !language.equals(languageOf(b))) continue;
            if (!decade.isEmpty() && !decade.equals(decadeOf(b))) continue;
            if (!universe.isEmpty() && !universe.equalsIgnoreCase(graphUniverses.get(b.id))) continue;
            if (tab.equals("Da leggere") && !b.status.equals(Book.TO_READ)) continue;
            if (tab.equals("In lettura") && !b.status.equals(Book.READING)) continue;
            if (tab.equals("Preferiti") && !b.journal.optBoolean("favorite")) continue;
            if (tab.equals(Book.PAUSED) && !b.status.equals(Book.PAUSED)) continue;
            if (tab.startsWith("Scaffale: ") && !ReadingJournal.inCollection(b, tab.substring(10))) continue;
            if (tab.equals("Letti") && !b.status.equals(Book.READ)) continue;
            if (!genreMatches(b, genre)) continue;
            boolean inSaga = !b.series.isEmpty() || graphSagas.containsKey(b.id);
            if (saga.equals(IN_SAGA) && !inSaga) continue;
            if (saga.equals(SINGLE) && inSaga) continue;
            if (!saga.isEmpty() && !saga.equals(IN_SAGA) && !saga.equals(SINGLE) && !sagaMatches(b, saga)) continue;
            if (format.equals(MANY_EDITIONS)) { org.json.JSONArray e = b.meta.optJSONArray("editions"); if (e == null || e.length() == 0) continue; }
            else if (!format.isEmpty() && !format.equals(b.format)) continue;
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
        if (SearchIndex.queryWords(query).isEmpty()) return result;
        return new ArrayList<>(SearchIndex.rank(result, query));
    }
}
