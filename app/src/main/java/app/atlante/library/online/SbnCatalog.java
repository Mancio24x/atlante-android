package app.atlante.library.online;

import app.atlante.library.model.Book;
import app.atlante.library.model.BookMatcher;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Italian national library catalog (OPAC SBN, ICCU): the most complete index of books published in Italy.
 * Uses the public JSON service of the official OPAC SBN mobile app; only the search text is sent.
 */
public final class SbnCatalog {
    public static final String BASE = "https://opac.sbn.it/opacmobilegw/";
    private static final Pattern BID = Pattern.compile("IT\\\\ICCU\\\\[A-Z0-9]{3,4}\\\\[0-9]{5,8}");
    private static final Pattern YEAR = Pattern.compile("(1[5-9]|20)[0-9]{2}");
    private static final Pattern PAGES = Pattern.compile("([0-9]{2,5}) *p\\.");

    public static ArrayList<Book> search(String query) throws Exception {
        String clean = query.trim().replaceAll("\\s+", " ");
        String isbn = clean.replaceAll("[\\s-]", "");
        String parameter = isbn.matches("97[89][0-9]{10}|[0-9]{9}[0-9Xx]") ? "isbn=" + isbn : "any=" + URLEncoder.encode(clean, "UTF-8");
        String response = new String(OnlineCatalog.request(BASE + "search.json?" + parameter + "&type=0&start=0&rows=40", 2 * 1024 * 1024), StandardCharsets.UTF_8);
        return parseSearch(response);
    }

    /**
     * Printed monographs only (no games, videos or e-resources), one result per work:
     * among editions with the same title and author, the most recent one with an ISBN wins.
     */
    public static ArrayList<Book> parseSearch(String response) throws Exception {
        JSONArray records = new JSONObject(response).optJSONArray("briefRecords");
        LinkedHashMap<String, Book> works = new LinkedHashMap<>();
        if (records == null) return new ArrayList<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null || !"Testo a stampa".equals(r.optString("tipo")) || !"Monografia".equals(r.optString("livello"))) continue;
            String bid = r.optString("codiceIdentificativo");
            if (!BID.matcher(bid).matches()) continue;
            Book b = fromRecord(r);
            if (b.title.isEmpty()) continue;
            String key = normalize(b.title) + "|" + normalize(b.author);
            Book current = works.get(key);
            if (current == null || better(b, current)) works.put(key, b);
        }
        return new ArrayList<>(works.values());
    }

    public static Book fromRecord(JSONObject r) throws Exception {
        Book b = new Book(); b.format = "CATALOGO"; b.language = "ita";
        String bid = r.optString("codiceIdentificativo");
        b.source = "sbn:" + bid; b.id = sha("sbn:" + bid);
        String[] parts = r.optString("titolo").split(" / ", 2);
        String[] titles = parts[0].split(" : ", 2);
        b.title = tidy(titles[0]); if (titles.length > 1) b.subtitle = tidy(titles[1]);
        String main = r.optString("autorePrincipale", "");
        if (!main.isEmpty() && !main.equals("null")) b.author = person(main);
        else if (parts.length > 1) b.author = tidy(parts[1].split(" ; ")[0].replaceAll("^(di|a cura di) ", ""));
        String publication = r.optString("pubblicazione", "");
        if (!publication.equals("null")) {
            Matcher year = YEAR.matcher(publication); String found = "";
            while (year.find()) found = year.group();
            b.year = found;
            String[] place = publication.split(" : ", 2);
            if (place.length > 1) b.publisher = tidy(place[1].replaceAll(",? *(stampa |c|©)?\\[?[0-9]{4}.*$", "").replace("[", "").replace("]", ""));
        }
        b.isbn = r.optString("isbn", "").replace("-", "").replace("null", "");
        return b;
    }

    /** Pages, language and ISBN from the full record of the chosen edition. */
    public static void enrich(Book b) throws Exception {
        String bid = b.source.startsWith("sbn:") ? b.source.substring(4) : "";
        if (!BID.matcher(bid).matches()) throw new java.io.IOException("Scheda SBN non valida");
        String response = new String(OnlineCatalog.request(BASE + "full.json?bid=" + URLEncoder.encode(bid, "UTF-8"), 1024 * 1024), StandardCharsets.UTF_8);
        enrichFrom(b, new JSONObject(response));
        if (!b.isbn.isEmpty()) {
            try { OnlineCatalog.enrichByIsbn(b); } catch (Exception ignored) { /* Plot and cover are optional extras. */ }
        }
    }

    public static void enrichFrom(Book b, JSONObject full) {
        Matcher pages = PAGES.matcher(full.optString("descrizioneFisica"));
        if (pages.find()) b.totalPages = Integer.parseInt(pages.group(1));
        if ("ITALIANO".equalsIgnoreCase(full.optString("linguaPubblicazione"))) b.language = "ita";
        JSONArray numbers = full.optJSONArray("numeri");
        if (b.isbn.isEmpty() && numbers != null) for (int i = 0; i < numbers.length(); i++) {
            String n = numbers.optString(i);
            if (n.startsWith("[ISBN]")) { b.isbn = n.substring(6).replaceAll("[^0-9Xx]", ""); break; }
        }
        String collection = full.optString("collezione", "");
        if (!collection.isEmpty() && !collection.equals("null") && b.series.isEmpty() && !collection.equalsIgnoreCase("Fuori collana")) b.tags = tidy(collection);
    }

    /** Same work in two catalogs: same ISBN, or same title with a word of the author in common. */
    /** Same book in two records: see {@link BookMatcher} (ISBN, title without subtitles, surnames). */
    public static boolean sameWork(Book a, Book b) { return BookMatcher.same(a, b); }

    private static boolean better(Book candidate, Book current) {
        if (candidate.isbn.isEmpty() != current.isbn.isEmpty()) return !candidate.isbn.isEmpty();
        return candidate.year.compareTo(current.year) > 0;
    }

    /** "Giannone, Francesca <1982- >" → "Francesca Giannone". */
    public static String person(String value) {
        String name = value.replaceAll("<[^>]*>", "").trim();
        String[] parts = name.split(", ", 2);
        return tidy(parts.length == 2 ? parts[1] + " " + parts[0] : name);
    }

    private static String tidy(String value) {
        return value.replaceAll("\\s+", " ").replaceAll("^[\\s.:;,/]+|[\\s.:;,/]+$", "").trim();
    }

    public static String normalize(String value) {
        return java.text.Normalizer.normalize(value.toLowerCase(Locale.ITALIAN), java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String sha(String value) throws Exception {
        StringBuilder id = new StringBuilder();
        for (byte v : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) id.append(String.format(Locale.ROOT, "%02x", v & 255));
        return id.toString();
    }
}
