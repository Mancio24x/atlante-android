package app.atlante.library.online;

import app.atlante.library.model.Book;
import app.atlante.library.model.BookMatcher;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Multi-source bibliographic engine. For a book it asks every enabled catalog (by ISBN first, then by
 * title and author), keeps only records of the same work, separates the specific edition from the work,
 * picks each field from the most reliable source for the user's language and remembers where it came from.
 * Nothing is written to the book here: the result is a list of proposals the user accepts or refuses.
 */
public final class MetadataEngine {
    private MetadataEngine() { }

    public static final class Settings { public MetadataSources.Keys keys = new MetadataSources.Keys(); public String language = "ita"; }
    public interface Progress { void update(String message); }

    /** Where the engine gets its candidates; the live implementation calls the catalogs, tests use fixtures. */
    public interface Catalogs {
        List<Candidate> byIsbn(String isbn, List<String> notes);
        List<Candidate> search(Book book, List<String> notes);
    }

    /** One proposed change. {@code accept} is the default shown to the user. */
    public static final class Field {
        public final String key, label, current, source; public final boolean manual; public boolean accept;
        public String proposed;
        /** The user picked another proposal for this field (e.g. an alternative plot). */
        public void choose(String value) { proposed = value; }
        Field(String key, String label, String current, String proposed, String source, boolean manual) {
            this.key = key; this.label = label; this.current = current; this.proposed = proposed; this.source = source; this.manual = manual;
            accept = current.trim().isEmpty() && !proposed.trim().isEmpty();
        }
    }

    public static final class Result {
        public final List<Candidate> candidates = new ArrayList<>();
        /** Different editions found without an ISBN: the user chooses instead of the engine guessing. */
        public final List<Candidate> ambiguous = new ArrayList<>();
        public final List<Field> fields = new ArrayList<>();
        public final List<Candidate.Cover> covers = new ArrayList<>();
        public final List<Candidate.Description> descriptions = new ArrayList<>();
        public final List<String> sources = new ArrayList<>(), notes = new ArrayList<>();
        public Candidate edition;
    }

    // ---- lookup ----------------------------------------------------------------------------------

    public static Result lookup(Book book, Settings s, Progress progress) { return lookup(book, s, live(s, progress), null); }

    /** {@code chosen}: the edition picked by the user after an ambiguous result (or null). */
    public static Result lookup(Book book, Settings s, Catalogs catalogs, Candidate chosen) {
        Result r = new Result();
        List<String> isbns = new ArrayList<>(BookMatcher.isbns(book.isbn));
        if (chosen != null) isbns.addAll(chosen.isbns);
        for (String isbn : new LinkedHashSet<>(isbns.subList(0, Math.min(isbns.size(), 2)))) r.candidates.addAll(catalogs.byIsbn(isbn, r.notes));
        boolean exact = false; for (Candidate c : r.candidates) if (c.byIsbn) exact = true;
        if (!exact) {
            List<Candidate> found = new ArrayList<>();
            for (Candidate c : catalogs.search(book, r.notes)) if (BookMatcher.same(book, asBook(c))) found.add(c);
            r.candidates.addAll(found);
            if (chosen == null) {
                List<Candidate> editions = editions(found, s.language);
                if (editions.size() > 1) { r.ambiguous.addAll(editions); }
            }
        }
        merge(book, r, s.language, chosen);
        return r;
    }

    /** Distinct editions among title results, best first (language of the user, then most complete). */
    static List<Candidate> editions(List<Candidate> found, String lang) {
        Map<String, Candidate> byEdition = new LinkedHashMap<>();
        for (Candidate c : found) {
            String key = !c.isbns.isEmpty() ? c.isbns.iterator().next() : BookMatcher.key(c.publisher) + "|" + c.year + "|" + c.language;
            if (key.equals("||")) continue;
            Candidate known = byEdition.get(key);
            if (known == null || completeness(c) > completeness(known)) byEdition.put(key, c);
        }
        List<Candidate> list = new ArrayList<>(byEdition.values());
        list.sort((a, b) -> Integer.compare(score(b, lang), score(a, lang)));
        return list;
    }

    static int score(Candidate c, String lang) { return (lang.equals(c.language) ? 1000 : 0) + (c.isbns.isEmpty() ? 0 : 200) + completeness(c); }
    static int completeness(Candidate c) {
        int n = 0;
        for (String v : new String[]{c.title, c.publisher, c.year, c.language, c.originalTitle, c.series}) if (!v.isEmpty()) n += 10;
        if (c.pages > 0) n += 10; if (!c.authors.isEmpty()) n += 10; if (!c.covers.isEmpty()) n += 10; if (!c.descriptions.isEmpty()) n += 20;
        return n;
    }

    public static Book asBook(Candidate c) {
        Book b = new Book(); b.title = c.title; b.author = c.authors.isEmpty() ? "Autore sconosciuto" : c.authorLine();
        b.isbn = String.join(" ", c.isbns); b.series = c.series; b.number = c.number; return b;
    }

    // ---- merge -----------------------------------------------------------------------------------

    /** Source order for edition data: Italian catalogs first for Italian books. */
    static List<String> order(String lang) {
        return lang.equals("ita") ? Arrays.asList("SBN", "Google Books", "ISBNdb", "Hardcover", "Open Library", "Internet Archive")
            : Arrays.asList("Google Books", "ISBNdb", "Hardcover", "Open Library", "SBN", "Internet Archive");
    }

    static void merge(Book book, Result r, String lang, Candidate chosen) {
        // The edition: records found by the book's ISBN, or the edition chosen by the user; never a mix of editions.
        List<Candidate> edition = new ArrayList<>();
        for (Candidate c : r.candidates) if (c.byIsbn) edition.add(c);
        if (edition.isEmpty() && chosen != null) for (Candidate c : r.candidates) if (sameEdition(c, chosen)) edition.add(c);
        if (edition.isEmpty() && r.ambiguous.isEmpty()) {
            List<Candidate> eds = editions(r.candidates, lang);
            if (eds.size() == 1) for (Candidate c : r.candidates) if (sameEdition(c, eds.get(0))) edition.add(c);
        }
        List<Candidate> work = new ArrayList<>(r.candidates);
        List<String> order = order(lang);
        edition.sort(Comparator.comparingInt(c -> order.indexOf(c.source)));
        work.sort(Comparator.comparingInt(c -> order.indexOf(c.source)));
        for (Candidate c : r.candidates) if (!r.sources.contains(c.source)) r.sources.add(c.source);
        r.edition = edition.isEmpty() ? null : edition.get(0);

        JSONObject meta = book.meta, manual = meta.optJSONObject("manual");
        // Edition fields, only from the edition.
        if (!edition.isEmpty()) {
            pick(r, book, manual, "title", "Titolo", book.title, edition, c -> c.title);
            pick(r, book, manual, "subtitle", "Sottotitolo", book.subtitle, edition, c -> c.subtitle);
            pick(r, book, manual, "author", "Autore", book.author.equals("Autore sconosciuto") ? "" : book.author, edition, Candidate::authorLine);
            pick(r, book, manual, "publisher", "Editore", book.publisher, edition, c -> c.publisher);
            pick(r, book, manual, "year", "Anno dell’edizione", book.year, edition, c -> c.year);
            pick(r, book, manual, "language", "Lingua dell’edizione", book.language, edition, c -> c.language);
            pick(r, book, manual, "format", "Formato", meta.optString("format"), edition, c -> c.format);
            pick(r, book, manual, "collection", "Collana", meta.optString("collection"), edition, c -> c.collection);
            pick(r, book, manual, "translators", "Traduzione", meta.optString("translators"), edition, c -> String.join(", ", c.translators));
            pick(r, book, manual, "editors", "Curatela", meta.optString("editors"), edition, c -> String.join(", ", c.editors));
            pick(r, book, manual, "illustrators", "Illustrazioni", meta.optString("illustrators"), edition, c -> String.join(", ", c.illustrators));
            // Pages of this edition: the Italian catalog first, else the most frequent value.
            Map<Integer, Integer> pages = new HashMap<>(); Candidate pageSource = null;
            for (Candidate c : edition) if (c.pages > 0) { pages.merge(c.pages, 1, Integer::sum); if (pageSource == null) pageSource = c; }
            if (pageSource != null) {
                int best = pageSource.pages; for (Map.Entry<Integer, Integer> e : pages.entrySet()) if (e.getValue() > pages.get(best)) best = e.getKey();
                final int chosenPages = best; Candidate src = pageSource; for (Candidate c : edition) if (c.pages == chosenPages) { src = c; break; }
                add(r, manual, "totalPages", "Pagine dell’edizione", book.totalPages > 0 ? Integer.toString(book.totalPages) : "", Integer.toString(chosenPages), src.source);
            }
            Set<String> isbns = new LinkedHashSet<>(BookMatcher.isbns(book.isbn)); String isbnSource = "";
            for (Candidate c : edition) for (String i : c.isbns) if (isbns.add(i) && isbnSource.isEmpty()) isbnSource = c.source;
            if (!isbnSource.isEmpty()) add(r, manual, "isbn", "ISBN", book.isbn, String.join(", ", isbns), isbnSource);
            for (Candidate c : edition) r.covers.addAll(c.covers);
            r.covers.sort((a, b) -> Integer.compare(b.width, a.width));
        }
        // Work fields, from any record of the same work.
        pick(r, book, manual, "originalTitle", "Titolo originale", meta.optString("originalTitle"), work, c -> c.originalTitle);
        String first = ""; String firstSource = "";
        for (Candidate c : work) if (!c.firstPublished.isEmpty() && (first.isEmpty() || c.firstPublished.compareTo(first) < 0)) { first = c.firstPublished; firstSource = c.source; }
        if (!first.isEmpty()) add(r, manual, "firstPublished", "Prima pubblicazione", meta.optString("firstPublished"), first, firstSource);
        Candidate withSeries = null; for (Candidate c : work) if (!c.series.isEmpty()) { withSeries = c; break; }
        if (withSeries != null) {
            add(r, manual, "series", "Saga", book.series, withSeries.series, withSeries.source);
            if (withSeries.number > 0) add(r, manual, "number", "Volume", book.number > 0 ? Book.formatNumber(book.number) : "", Book.formatNumber(withSeries.number), withSeries.source);
        }
        pick(r, book, manual, "dewey", "Classificazione Dewey", meta.optString("dewey"), work, c -> c.dewey);
        LinkedHashSet<String> subjects = new LinkedHashSet<>(); String subjectSource = "";
        for (Candidate c : work) for (String sub : c.subjects)
            if (sub.length() <= 40 && !sub.contains(". ") && subjects.size() < 10 && subjects.add(sub) && subjectSource.isEmpty()) subjectSource = c.source;
        if (!subjects.isEmpty()) add(r, manual, "subjects", "Argomenti", meta.optString("subjects"), String.join(", ", subjects), subjectSource);
        // Genre only from classifications (Dewey of SBN) or publisher categories, never from free-form subjects.
        if (book.genre.isEmpty()) {
            String genre = "", genreSource = "";
            for (Candidate c : work) { String g = deweyGenre(c.dewey); if (!g.isEmpty()) { genre = g; genreSource = c.source + " (Dewey)"; break; } }
            if (genre.isEmpty()) for (Candidate c : work) if ((c.source.equals("Google Books") || c.source.equals("ISBNdb")) && !c.subjects.isEmpty()) { genre = c.subjects.get(0); genreSource = c.source; break; }
            if (!genre.isEmpty()) add(r, manual, "genre", "Genere", "", genre, genreSource);
        }
        // Plots: in the user's language first, edition before work, then the most complete one.
        for (Candidate c : edition) r.descriptions.addAll(c.descriptions);
        for (Candidate c : work) if (!edition.contains(c)) r.descriptions.addAll(c.descriptions);
        r.descriptions.removeIf(d -> d.text.length() < 120 || d.text.equals(d.text.toUpperCase(Locale.ROOT)));
        r.descriptions.sort((a, b) -> Integer.compare(plotScore(b, lang), plotScore(a, lang)));
        if (!r.descriptions.isEmpty()) {
            Candidate.Description best = r.descriptions.get(0);
            if (!best.text.equals(book.description)) add(r, manual, "description", "Trama" + (best.language.equals(lang) ? "" : " (" + best.language + ")"), book.description, best.text, best.source);
        }
        // Identifiers from every source of this work.
        JSONObject known = meta.optJSONObject("ids"); Map<String, String> ids = new LinkedHashMap<>();
        for (Candidate c : edition.isEmpty() ? work : edition) for (Map.Entry<String, String> e : c.ids.entrySet()) ids.putIfAbsent(e.getKey(), e.getValue());
        StringBuilder idLine = new StringBuilder();
        for (Map.Entry<String, String> e : ids.entrySet()) if (known == null || !e.getValue().equals(known.optString(e.getKey()))) idLine.append(idLine.length() > 0 ? "; " : "").append(e.getKey()).append("=").append(e.getValue());
        if (idLine.length() > 0) { Field f = new Field("ids", "Identificativi esterni", "", idLine.toString(), String.join(", ", r.sources), false); f.accept = true; r.fields.add(f); }
    }

    /** "853.92 (23.) NARRATIVA ITALIANA, 2000-" → "Narrativa italiana". */
    static String deweyGenre(String dewey) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\)\\s*([^,0-9]+)").matcher(dewey == null ? "" : dewey);
        if (!m.find()) return "";
        String g = m.group(1).trim().toLowerCase(Locale.ITALIAN);
        return g.isEmpty() ? "" : Character.toUpperCase(g.charAt(0)) + g.substring(1);
    }

    static int plotScore(Candidate.Description d, String lang) { return (d.language.equals(lang) ? 100000 : 0) + Math.min(d.text.length(), 3000); }

    static boolean sameEdition(Candidate a, Candidate b) {
        for (String i : a.isbns) if (b.isbns.contains(i)) return true;
        return a.isbns.isEmpty() && b.isbns.isEmpty() && BookMatcher.key(a.publisher).equals(BookMatcher.key(b.publisher)) && a.year.equals(b.year) && a.language.equals(b.language);
    }

    private interface Getter { String get(Candidate c); }
    private static void pick(Result r, Book book, JSONObject manual, String key, String label, String current, List<Candidate> from, Getter g) {
        for (Candidate c : from) { String v = g.get(c); if (v != null && !v.trim().isEmpty()) { add(r, manual, key, label, current, v.trim(), c.source); return; } }
    }
    private static void add(Result r, JSONObject manual, String key, String label, String current, String proposed, String source) {
        if (current == null) current = "";
        if (BookMatcher.key(current).equals(BookMatcher.key(proposed))) return;   // nothing new
        r.fields.add(new Field(key, label, current, proposed, source, manual != null && manual.optBoolean(key)));
    }

    // ---- applying accepted fields ----------------------------------------------------------------

    /** Writes the accepted fields to the book and records their source in meta.sources. */
    public static void apply(Book b, List<Field> fields) {
        try {
            JSONObject sources = b.meta.optJSONObject("sources"); if (sources == null) sources = new JSONObject();
            for (Field f : fields) {
                if (!f.accept) continue;
                String v = f.proposed;
                switch (f.key) {
                    case "title": b.title = v; break;
                    case "subtitle": b.subtitle = v; break;
                    case "author": b.author = v; break;
                    case "publisher": b.publisher = v; break;
                    case "year": b.year = v; break;
                    case "language": b.language = v; break;
                    case "isbn": b.isbn = v; break;
                    case "series": b.series = v; break;
                    case "number": try { b.number = Double.parseDouble(v); } catch (NumberFormatException ignored) { } break;
                    case "genre": b.genre = v; break;
                    case "description": b.description = v; break;
                    case "totalPages": try { b.totalPages = Integer.parseInt(v); if (b.currentPage > b.totalPages) b.currentPage = b.totalPages; } catch (NumberFormatException ignored) { } break;
                    case "ids": {
                        JSONObject ids = b.meta.optJSONObject("ids"); if (ids == null) ids = new JSONObject();
                        for (String pair : v.split(";\\s*")) { String[] kv = pair.split("=", 2); if (kv.length == 2) ids.put(kv[0].trim(), kv[1].trim()); }
                        b.meta.put("ids", ids); break;
                    }
                    default: b.meta.put(f.key, v);
                }
                sources.put(f.key, f.source);
            }
            b.meta.put("sources", sources).put("checkedAt", System.currentTimeMillis());
        } catch (org.json.JSONException e) { throw new IllegalArgumentException(e); }
    }

    /** Remembers fields edited by hand, so later updates propose them unchecked. */
    public static void markManual(Book before, Book after) {
        try {
            JSONObject manual = after.meta.optJSONObject("manual"); if (manual == null) manual = new JSONObject();
            String[][] pairs = {{"title", before.title, after.title}, {"subtitle", before.subtitle, after.subtitle}, {"author", before.author, after.author},
                {"publisher", before.publisher, after.publisher}, {"year", before.year, after.year}, {"language", before.language, after.language},
                {"isbn", before.isbn, after.isbn}, {"series", before.series, after.series}, {"genre", before.genre, after.genre},
                {"description", before.description, after.description}, {"totalPages", Integer.toString(before.totalPages), Integer.toString(after.totalPages)},
                {"number", Double.toString(before.number), Double.toString(after.number)}};
            for (String[] p : pairs) if (!p[1].equals(p[2])) manual.put(p[0], true);
            if (manual.length() > 0) after.meta.put("manual", manual);
        } catch (org.json.JSONException e) { throw new IllegalArgumentException(e); }
    }

    // ---- live catalogs -----------------------------------------------------------------------------

    public static Catalogs live(Settings s, Progress progress) {
        return new Catalogs() {
            public List<Candidate> byIsbn(String isbn, List<String> notes) {
                List<Candidate> out = new ArrayList<>();
                attempt(out, notes, "SBN", progress, () -> MetadataSources.sbnByIsbn(isbn));
                attempt(out, notes, "Open Library", progress, () -> MetadataSources.openLibraryByIsbn(isbn));
                attempt(out, notes, "Internet Archive", progress, () -> MetadataSources.internetArchiveByIsbn(isbn));
                if (!s.keys.google.isEmpty()) attempt(out, notes, "Google Books", progress, () -> {
                    List<Candidate> g = MetadataSources.googleBooksQuery("isbn:" + isbn, s.keys.google, "");
                    if (g.isEmpty()) return null; g.get(0).byIsbn = g.get(0).isbns.contains(isbn) || g.get(0).isbns.isEmpty(); return g.get(0);
                });
                if (!s.keys.hardcover.isEmpty()) attempt(out, notes, "Hardcover", progress, () -> MetadataSources.hardcoverByIsbn(isbn, s.keys.hardcover));
                if (!s.keys.isbndb.isEmpty()) attempt(out, notes, "ISBNdb", progress, () -> MetadataSources.isbndbByIsbn(isbn, s.keys.isbndb));
                return out;
            }
            public List<Candidate> search(Book book, List<String> notes) {
                List<Candidate> out = new ArrayList<>();
                String author = book.author.equals("Autore sconosciuto") ? "" : book.author;
                progress.update("Ricerca per titolo e autore…");
                try {
                    int n = 0;
                    for (Book b : SbnCatalog.search(book.title + " " + author)) if (BookMatcher.same(book, b) && n++ < 3) {
                        List<Candidate> c = byIsbnOnly(b.isbn, notes); if (c.isEmpty()) continue; for (Candidate x : c) x.byIsbn = false; out.addAll(c);
                    }
                } catch (Exception e) { notes.add("SBN: " + message(e)); }
                try {
                    int n = 0;
                    for (Book b : OnlineCatalog.search(book.title + " " + author, s.language, false)) if (BookMatcher.same(book, b) && !b.edition.isEmpty() && n++ < 3) {
                        try {
                            JSONObject ed = new JSONObject(new String(OnlineCatalog.request("https://openlibrary.org" + b.edition + ".json", 2 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8));
                            JSONArray works = ed.optJSONArray("works"); String wk = works == null || works.optJSONObject(0) == null ? "" : works.optJSONObject(0).optString("key");
                            JSONObject w = wk.matches("/works/OL[0-9]+W") ? new JSONObject(new String(OnlineCatalog.request("https://openlibrary.org" + wk + ".json", 2 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8)) : null;
                            out.add(MetadataSources.openLibrary(ed, w));
                        } catch (Exception ignored) { }
                    }
                } catch (Exception e) { notes.add("Open Library: " + message(e)); }
                if (!s.keys.google.isEmpty()) try {
                    out.addAll(MetadataSources.googleBooksQuery("intitle:" + book.title + (author.isEmpty() ? "" : " inauthor:" + author.split(" & ")[0]), s.keys.google, s.language));
                } catch (Exception e) { notes.add("Google Books: " + message(e)); }
                return out;
            }
            private List<Candidate> byIsbnOnly(String raw, List<String> notes) {
                List<Candidate> out = new ArrayList<>();
                for (String isbn : BookMatcher.isbns(raw)) { attempt(out, notes, "SBN", progress, () -> MetadataSources.sbnByIsbn(isbn)); break; }
                return out;
            }
        };
    }

    private interface Fetch { Candidate get() throws Exception; }
    private static void attempt(List<Candidate> out, List<String> notes, String name, Progress progress, Fetch f) {
        progress.update("Consulto " + name + "…");
        try { Candidate c = f.get(); if (c != null) out.add(c); }
        catch (java.io.FileNotFoundException e) { /* not in this catalog */ }
        catch (Exception e) { String m = message(e); if (!m.contains("404")) notes.add(name + ": " + m); }
    }
    static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
