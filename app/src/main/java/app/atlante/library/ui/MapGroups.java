package app.atlante.library.ui;

import app.atlante.library.model.Book;
import app.atlante.library.online.SeriesCatalog;

import java.util.*;

/**
 * How the reading map groups books. Only the display changes: the books' own data is never rewritten.
 *
 * Authors: "Asimov, Isaac", "Isaac Asimov" and "ISAAC ASIMOV" are one author; a book by "A & B" is shown under
 * both. Sagas: names are compared without articles, "ciclo/saga/serie/trilogia di…", numbers and a few known
 * translations, so "Il ciclo della Fondazione", "Fondazione 3" and "Foundation" are one group. A book's own saga
 * and the saga recognized by the saga engine (Wikidata / Open Library) join their groups. Books without a saga
 * use the recognized one, otherwise the built-in catalog (for example the Dune titles).
 */
public final class MapGroups {
    /** Saga recognized by the saga engine for a book (name and position), when there is one. */
    public static final class Known {
        public final String series; public final String ordinal;
        public Known(String series, String ordinal) { this.series = series; this.ordinal = ordinal; }
    }

    public static final String SINGLE = "Libri singoli";

    private MapGroups() { }

    // ---- authors -------------------------------------------------------------------------------

    /** The authors of a book as written ("A & B", "A; B", "A and B"). */
    public static List<String> authors(String author) {
        ArrayList<String> out = new ArrayList<>();
        if (author == null) return out;
        for (String a : author.split("\\s*(?:&|;|\\band\\b)\\s*")) {
            String t = a.trim();
            if (!t.isEmpty() && !t.equalsIgnoreCase("Autore sconosciuto")) out.add(t);
        }
        return out;
    }

    /** Same key for the same person: "Asimov, Isaac" = "Isaac Asimov" = "isaac  asimov". */
    public static String authorKey(String name) {
        String n = name.trim();
        int comma = n.indexOf(',');
        if (comma > 0 && n.indexOf(',', comma + 1) < 0) n = n.substring(comma + 1) + " " + n.substring(0, comma);   // "Cognome, Nome"
        String[] words = SeriesCatalog.normalize(n).split(" ");
        Arrays.sort(words);
        return String.join(" ", words).trim();
    }

    /** One entry per person, with the spelling used most often in the library. */
    public static List<String> authorNames(Collection<Book> books) {
        HashMap<String, HashMap<String, Integer>> spellings = new HashMap<>();
        for (Book b : books) for (String a : authors(b.author)) {
            String key = authorKey(a); if (key.isEmpty()) continue;
            spellings.computeIfAbsent(key, k -> new HashMap<>()).merge(a, 1, Integer::sum);
        }
        ArrayList<String> names = new ArrayList<>();
        for (HashMap<String, Integer> s : spellings.values()) {
            String best = null; int count = -1;
            for (Map.Entry<String, Integer> e : s.entrySet())
                if (e.getValue() > count || (e.getValue() == count && e.getKey().compareTo(best) < 0)) { best = e.getKey(); count = e.getValue(); }
            names.add(best);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Books of this author (also when written differently, or as one of several authors). */
    public static ArrayList<Book> byAuthor(Collection<Book> books, String author, int max) {
        String key = authorKey(author);
        ArrayList<Book> out = new ArrayList<>();
        for (Book b : books) {
            if (out.size() >= max) break;
            for (String a : authors(b.author)) if (authorKey(a).equals(key)) { out.add(b); break; }
        }
        return out;
    }

    // ---- sagas ---------------------------------------------------------------------------------

    /** Well-known sagas whose Italian and original names differ. Both sides are already normalized keys. */
    private static final Map<String, String> ALIASES = new HashMap<>();
    static {
        String[][] pairs = {
            {"foundation", "fondazione"}, {"fondazioni", "fondazione"}, {"robots", "robot"}, {"galactic empire", "impero"}, {"impero galattico", "impero"},
            {"empire", "impero"}, {"song of ice and fire", "ghiaccio e fuoco"}, {"cronache del ghiaccio e del fuoco", "ghiaccio e fuoco"},
            {"stormlight archive", "folgoluce"}, {"cronache della folgoluce", "folgoluce"}, {"lord of the rings", "signore degli anelli"},
            {"wheel of time", "ruota del tempo"}, {"discworld", "mondo disco"}, {"dark tower", "torre nera"},
            {"hitchhiker s guide to the galaxy", "guida galattica per gli autostoppisti"}, {"hunger games trilogy", "hunger games"},
            {"chronicles of narnia", "narnia"}, {"cronache di narnia", "narnia"}, {"dune chronicles", "dune"}, {"cronache di dune", "dune"},
            {"hyperion cantos", "hyperion"}, {"i canti di hyperion", "hyperion"}, {"earthsea", "terramare"}, {"his dark materials", "queste oscure materie"},
            {"millennium trilogy", "millennium"}, {"neapolitan novels", "amica geniale"}, {"l amica geniale", "amica geniale"},
        };
        for (String[] p : pairs) ALIASES.put(p[0], p[1]);
    }

    private static final String GENERIC = "(?:il |lo |la |i |gli |le |l |the |un |una |a )?"
        + "(?:(?:ciclo|saga|serie|series|trilogia|trilogy|tetralogia|pentalogia|cycle|cronache|chronicles|romanzi|libri|books|universo|universe)"
        + "(?: (?:di|del|dello|della|dei|degli|delle|dell|d|of|the|de))*(?: |$))?";

    /** Comparable form of a saga name: no articles, generic words, numbers or "#3"; known translations folded. */
    public static String seriesKey(String series) {
        if (series == null) return "";
        String k = SeriesCatalog.normalize(series);
        k = k.replaceAll("\\b(?:vol|volume|libro|book|n|nr|parte|part)?\\s*\\d+(?:\\s\\d+)?$", "").trim();   // "Fondazione 3", "Dune vol 2"
        for (int i = 0; i < 3; i++) {                                                               // "Il ciclo della …", "La saga di …"
            String next = k.replaceFirst("^" + GENERIC, "").trim();
            if (next.isEmpty() || next.equals(k)) break; k = next;
        }
        k = k.replaceAll("\\s(?:series|saga|cycle|trilogy|trilogia|ciclo|serie|universe|novels|sequence)$", "").trim();
        k = k.replaceFirst("^(?:il|lo|la|i|gli|le|l|the) ", "");
        return ALIASES.getOrDefault(k, k);
    }

    /** A group of the map: display name and books in reading order (unnumbered at the end). */
    public static final class Group {
        public final String name; public final ArrayList<Book> books = new ArrayList<>();
        Group(String name) { this.name = name; }
    }

    /** Position used for the order: the book's own number, else the recognized one, else the catalog's. */
    public static double number(Book b, Known known) {
        if (b.number > 0) return b.number;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:#|vol\\.?|volume|libro|book)?\\s*(\\d+(?:[.,]\\d+)?)$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(b.series.trim());
        if (m.find() && m.start() > 0) return Double.parseDouble(m.group(1).replace(',', '.'));      // "Robot 3"
        if (known != null && known.ordinal != null) {
            try { return Double.parseDouble(known.ordinal.trim().replace(',', '.')); } catch (NumberFormatException ignored) { }
        }
        // The built-in catalog knows the order of some sagas (Dune…): used when it is the same saga.
        SeriesCatalog.Proposal p = SeriesCatalog.suggest(b);
        String own = known != null ? known.series : b.series;
        return p != null && (own.trim().isEmpty() || seriesKey(own).equals(seriesKey(p.series))) ? p.number : 0;
    }

    /** Groups for the map. {@code known} maps a book id to its recognized saga (may be empty). */
    public static List<Group> group(List<Book> books, Map<String, Known> known) {
        // 1. Every name a book carries for its saga, and union of the names found on the same book.
        HashMap<String, String> parent = new HashMap<>();
        HashMap<String, HashMap<String, Integer>> labels = new HashMap<>();
        HashMap<Book, String> keyOf = new HashMap<>();
        for (Book b : books) {
            ArrayList<String> names = new ArrayList<>();
            if (!b.series.trim().isEmpty()) names.add(b.series.trim());
            Known k = known.get(b.id);
            if (k != null && k.series != null && !k.series.trim().isEmpty()) names.add(k.series.trim());
            if (names.isEmpty()) { SeriesCatalog.Proposal p = SeriesCatalog.suggest(b); if (p != null) names.add(p.series); }
            String first = null;
            for (String n : names) {
                String key = seriesKey(n); if (key.isEmpty()) continue;
                parent.putIfAbsent(key, key);
                labels.computeIfAbsent(key, x -> new HashMap<>()).merge(n, 1, Integer::sum);
                if (first == null) first = key; else union(parent, first, key);
            }
            if (first != null) keyOf.put(b, first);
        }
        // 2. Groups by root; the name is the label used most often (the book's own spelling wins ties).
        LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
        HashMap<String, HashMap<String, Integer>> rootLabels = new HashMap<>();
        for (Map.Entry<String, HashMap<String, Integer>> e : labels.entrySet())
            for (Map.Entry<String, Integer> l : e.getValue().entrySet())
                rootLabels.computeIfAbsent(find(parent, e.getKey()), x -> new HashMap<>()).merge(l.getKey(), l.getValue(), Integer::sum);
        ArrayList<Book> singles = new ArrayList<>();
        for (Book b : books) {
            String key = keyOf.get(b);
            if (key == null) { singles.add(b); continue; }
            String root = find(parent, key);
            groups.computeIfAbsent(root, r -> new Group(bestLabel(rootLabels.get(r)))).books.add(b);
        }
        ArrayList<Group> out = new ArrayList<>(groups.values());
        // A "saga" of one book whose own data has no saga is not a group: it stays with the single books.
        for (Iterator<Group> it = out.iterator(); it.hasNext(); ) {
            Group g = it.next();
            if (g.books.size() == 1 && g.books.get(0).series.trim().isEmpty() && known.get(g.books.get(0).id) == null) { singles.add(g.books.get(0)); it.remove(); }
        }
        for (Group g : out) g.books.sort((a, b) -> {
            int c = SeriesCatalog.compareNumber(number(a, known.get(a.id)), number(b, known.get(b.id)));
            if (c == 0) c = a.year.compareTo(b.year);
            return c != 0 ? c : a.title.compareToIgnoreCase(b.title);
        });
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        if (!singles.isEmpty()) {
            Group s = new Group(SINGLE); s.books.addAll(singles);
            s.books.sort((a, b) -> { int c = a.year.compareTo(b.year); return c != 0 ? c : a.title.compareToIgnoreCase(b.title); });
            out.add(s);
        }
        return out;
    }

    private static String bestLabel(Map<String, Integer> counts) {
        String best = null; int n = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet())
            if (e.getValue() > n || (e.getValue() == n && e.getKey().length() < best.length())) { best = e.getKey(); n = e.getValue(); }
        // "Fondazione 3" as the only label: show the saga, not the volume.
        return best.replaceAll("\\s*(?:#|vol\\.?|volume|libro|book)?\\s*\\d+(?:[.,]\\d+)?$", "").trim();
    }

    private static String find(Map<String, String> parent, String k) {
        String r = k; while (!parent.get(r).equals(r)) r = parent.get(r);
        while (!parent.get(k).equals(r)) { String next = parent.get(k); parent.put(k, r); k = next; }
        return r;
    }
    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a), rb = find(parent, b); if (!ra.equals(rb)) parent.put(rb, ra);
    }
}
