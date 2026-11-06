package app.atlante.library.model;

import app.atlante.library.online.SbnCatalog;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether two records describe the same book, so that syncs and imports never create duplicates.
 * Signals, strongest first: a shared ISBN (ISBN-10 and ISBN-13 compared in the same form), then the same title
 * (ignoring subtitles, leading articles and "Author - Year - Title" file-name patterns) with a surname in common.
 * Same title by different authors ("La casa" by McDowell, "La casa" by someone else) is not the same book.
 */
public final class BookMatcher {
    public static final int SAME_ISBN = 100, SAME_TITLE_AND_AUTHOR = 80, SAME_TITLE_UNKNOWN_AUTHOR = 60, DIFFERENT = 0;
    private static final Pattern ISBN = Pattern.compile("(?<![0-9])(97[89][0-9]{10}|[0-9]{9}[0-9Xx])(?![0-9])");
    private static final Set<String> ARTICLES = new HashSet<>(Arrays.asList(
        "il", "lo", "la", "i", "gli", "le", "l", "un", "uno", "una", "un'", "the", "a", "an", "el", "los", "las", "les", "der", "die", "das"));
    private static final Set<String> NOT_SURNAMES = new HashSet<>(Arrays.asList("autore", "sconosciuto", "unknown", "author", "anonimo", "aa", "vv", "di", "de", "da", "del", "della", "van", "von"));

    public static boolean same(Book a, Book b) { return score(a, b) >= SAME_TITLE_UNKNOWN_AUTHOR; }

    public static int score(Book a, Book b) {
        String ah=a.meta.optString("fileSha256",a.id==null?"":a.id), bh=b.meta.optString("fileSha256",b.id==null?"":b.id);
        if(ah.matches("[0-9a-f]{64}")&&ah.equals(bh))return SAME_ISBN;
        org.json.JSONObject ai=a.meta.optJSONObject("ids"),bi=b.meta.optJSONObject("ids");
        if(ai!=null&&bi!=null)for(String key:new String[]{"asin","uuid","doi"}) {
            String value=ai.optString(key);if(!value.isEmpty()&&value.equalsIgnoreCase(bi.optString(key)))return SAME_ISBN;
        }
        Set<String> ia = isbns(a.isbn), ib = isbns(b.isbn);
        for (String i : ia) if (ib.contains(i)) return SAME_ISBN;
        if (a.number > 0 && b.number > 0 && a.number != b.number && !a.series.isEmpty() && SbnCatalog.normalize(a.series).equals(SbnCatalog.normalize(b.series))) return DIFFERENT;
        Set<String> sa = surnames(a.author), sb = surnames(b.author);
        Set<String> ta = titles(a.title, sa), tb = titles(b.title, sb);
        // One title must be exactly the other one, or one of its parts ("Il colibrì" vs "Il colibrì : romanzo").
        // Two different subtitles after the same prefix are different books ("Hunger Games - Ballata…" vs "Hunger Games - Il canto…").
        String shared = null;
        for (String t : whole(a.title)) if (tb.contains(t)) { shared = t; break; }
        if (shared == null) for (String t : whole(b.title)) if (ta.contains(t)) { shared = t; break; }
        if (shared == null) return DIFFERENT;
        // "… - Volume 1" and "… - Volume 2": different numbers in the titles mean different books (years aside).
        Set<String> na = numbers(a.title), nb = numbers(b.title);
        if (!na.isEmpty() && !nb.isEmpty() && !na.equals(nb)) return DIFFERENT;
        if (sa.isEmpty() || sb.isEmpty()) {
            // Without an author on one side only a distinctive title counts ("Gilgamesh" yes, "La casa" no).
            return shared.replace(" ", "").length() >= 8 ? SAME_TITLE_UNKNOWN_AUTHOR : DIFFERENT;
        }
        for (String s : sa) if (sb.contains(s)) return SAME_TITLE_AND_AUTHOR;
        return DIFFERENT;
    }

    /** The best match in a list (or null): highest score, ISBN before title. */
    public static <T> T best(Book wanted, Collection<T> candidates, java.util.function.Function<T, Book> asBook) {
        T found = null; int top = 0;
        for (T c : candidates) {
            int s = score(wanted, asBook.apply(c));
            if (s > top) { top = s; found = c; if (s == SAME_ISBN) break; }
        }
        return top >= SAME_TITLE_UNKNOWN_AUTHOR ? found : null;
    }

    /** Every ISBN in a field ("978…, 978…", "88-452-9261-X"), as ISBN-13. */
    public static Set<String> isbns(String raw) {
        Set<String> out = new HashSet<>();
        if (raw == null) return out;
        Matcher m = ISBN.matcher(raw.replaceAll("(?<=[0-9])[- ](?=[0-9Xx])", ""));
        while (m.find()) { String i = m.group(1).toUpperCase(Locale.ROOT); out.add(i.length() == 10 ? to13(i) : i); }
        return out;
    }

    public static String to13(String isbn10) {
        String core = "978" + isbn10.substring(0, 9); int sum = 0;
        for (int i = 0; i < 12; i++) sum += (core.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return core + ((10 - sum % 10) % 10);
    }

    /** Surnames (and given names) of every author, normalized: order and "Surname, Name" do not matter. */
    public static Set<String> surnames(String author) {
        Set<String> out = new HashSet<>();
        if (author == null) return out;
        for (String w : SbnCatalog.normalize(author).split(" ")) if (w.length() > 2 && !NOT_SURNAMES.contains(w)) out.add(w);
        return out;
    }

    /**
     * Comparable forms of a title: the whole title, the part before a subtitle, both without a leading article,
     * and for "A - B - C" names the pieces that are not the author or a year (e.g. "Camus Albert - 1987 - Lo straniero").
     */
    public static Set<String> titles(String title, Set<String> authorWords) {
        Set<String> out = new LinkedHashSet<>();
        if (title == null) return out;
        out.addAll(whole(title));   // the whole title always counts, even "1984"
        for (String t : new String[]{title, clean(title)}) {
            prefixes(out, t, authorWords);
            for (String piece : t.split("\\s+[-–—]\\s+")) prefixes(out, piece, authorWords);
        }
        return out;
    }

    /** Without notes in brackets ("(Italian Edition)", "(Z-Library)") and file extensions (".doc"). */
    public static String clean(String title) {
        return title.replaceAll("\\([^)]*\\)|\\[[^]]*\\]", " ").replaceAll("(?i)\\.(docx?|pdf|epub|txt|mobi|azw3?|rtf)\\s*$", " ").trim();
    }

    /** The title and every part of it that ends where a subtitle could start (":", ";", ",", " - ", ". ", "("). */
    private static void prefixes(Set<String> out, String title, Set<String> authorWords) {
        add(out, title, authorWords);
        Matcher m = Pattern.compile("\\s*[:;,]\\s*|\\s+[-–—]\\s+|\\.\\s|\\.$|\\s*\\(").matcher(title);
        while (m.find()) if (m.start() > 0) add(out, title.substring(0, m.start()), authorWords);
    }

    /** The whole title, with and without a leading article. */
    public static Set<String> whole(String title) {
        Set<String> out = new HashSet<>();
        for (String t : new String[]{title == null ? "" : title, title == null ? "" : clean(title)}) {
            String k = key(t); if (k.isEmpty()) continue;
            out.add(k); String[] first = k.split(" ", 2); if (first.length == 2 && ARTICLES.contains(first[0])) out.add(first[1]);
        }
        return out;
    }

    /** Numbers in a title other than years (volume numbers, "2", "II" is left to the series check). */
    public static Set<String> numbers(String title) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("(?<![0-9])([0-9]{1,4})(?![0-9])").matcher(title == null ? "" : title);
        while (m.find()) { String n = m.group(1); if (!n.matches("1[5-9][0-9]{2}|20[0-9]{2}")) out.add(String.valueOf(Integer.parseInt(n))); }
        return out;
    }

    /** Adds a comparable title, unless it is only digits or only the author's name (file-name pieces). */
    private static void add(Set<String> out, String title, Set<String> authorWords) {
        String k = key(title); if (k.isEmpty() || k.matches("[0-9 ]+")) return;
        Set<String> words = new HashSet<>(Arrays.asList(k.split(" ")));
        if (!authorWords.isEmpty() && authorWords.containsAll(words)) return;
        out.add(k);
        String[] first = k.split(" ", 2);
        if (first.length == 2 && ARTICLES.contains(first[0])) out.add(first[1]);
    }

    public static String key(String title) {
        return SbnCatalog.normalize(title == null ? "" : title.replace('’', '\'').replaceAll("(?i)\\b(l|un|dell|all|nell|sull|dall)'", "$1 "));
    }
}
