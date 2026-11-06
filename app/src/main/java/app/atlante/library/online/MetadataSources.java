package app.atlante.library.online;

import app.atlante.library.model.BookMatcher;
import app.atlante.library.storage.CatalogImport;
import app.atlante.library.sync.SyncCore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One function per catalog. Each returns Candidates built only from what the catalog actually says.
 * Public sources: Open Library, SBN (ICCU), Internet Archive. With the user’s own key:
 * Hardcover, ISBNdb. No scraping, no private endpoints.
 */
public final class MetadataSources {
    private MetadataSources() { }

    /** Keys entered by the user; empty means the source is skipped. */
    public static final class Keys { public String hardcover = "", isbndb = ""; }

    // ---- languages -----------------------------------------------------------------------------

    private static final String[][] LANGS = {{"ita", "it", "italiano", "italian"}, {"eng", "en", "inglese", "english"}, {"fre", "fr", "francese", "french", "fra"},
        {"ger", "de", "tedesco", "german", "deu"}, {"spa", "es", "spagnolo", "spanish"}, {"por", "pt", "portoghese", "portuguese"}};

    /** ISO 639-2 code ("ita") from "it", "ita", "ITALIANO", "Italian", "/languages/ita"; "" if unknown. */
    public static String language(String raw) {
        if (raw == null) return "";
        String v = raw.trim().toLowerCase(Locale.ROOT).replace("/languages/", "");
        for (String[] l : LANGS) for (String alias : l) if (v.equals(alias)) return l[0];
        return v.matches("[a-z]{3}") ? v : "";
    }

    private static final Set<String> IT_WORDS = new HashSet<>(Arrays.asList("il", "la", "di", "che", "e", "un", "una", "del", "della", "per", "non", "con", "nel", "sono", "è", "gli", "le", "dei", "alla", "suo", "sua"));
    private static final Set<String> EN_WORDS = new HashSet<>(Arrays.asList("the", "and", "of", "to", "a", "in", "is", "his", "her", "that", "with", "for", "was", "on", "as", "by", "this", "from", "their", "an"));

    /** Rough language of a text: "ita", "eng" or "" (only these two are told apart, by common words). */
    public static String textLanguage(String text) {
        int it = 0, en = 0;
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) { if (IT_WORDS.contains(w)) it++; if (EN_WORDS.contains(w)) en++; }
        if (it + en < 4) return "";
        return it > en * 1.5 ? "ita" : en > it * 1.5 ? "eng" : "";
    }

    static String year(String date) { Matcher m = Pattern.compile("(1[5-9]|20)[0-9]{2}").matcher(date == null ? "" : date); String y = ""; while (m.find()) { y = m.group(); break; } return y; }

    private static String str(JSONObject o, String k) { return o == null || o.isNull(k) ? "" : o.optString(k, "").trim(); }
    private static List<String> list(JSONArray a) { List<String> out = new ArrayList<>(); for (int i = 0; a != null && i < a.length(); i++) { String s = a.optString(i, "").trim(); if (!s.isEmpty() && !s.equals("null")) out.add(s); } return out; }

    // ---- Open Library ----------------------------------------------------------------------------

    /** Edition record (/isbn/…json) plus its work (/works/…json): edition fields and work fields. */
    public static Candidate openLibrary(JSONObject edition, JSONObject work) {
        Candidate c = new Candidate("Open Library");
        c.title = str(edition, "title"); c.subtitle = str(edition, "subtitle");
        c.publisher = String.join(", ", list(edition.optJSONArray("publishers")));
        c.editionDate = str(edition, "publish_date"); c.year = year(c.editionDate);
        c.pages = Math.max(0, edition.optInt("number_of_pages", 0)); c.format = str(edition, "physical_format");
        JSONArray langs = edition.optJSONArray("languages");
        if (langs != null && langs.optJSONObject(0) != null) c.language = language(langs.optJSONObject(0).optString("key"));
        for (String i : list(edition.optJSONArray("isbn_13"))) c.isbns.addAll(BookMatcher.isbns(i));
        for (String i : list(edition.optJSONArray("isbn_10"))) c.isbns.addAll(BookMatcher.isbns(i));
        JSONArray covers = edition.optJSONArray("covers");
        for (int i = 0; covers != null && i < covers.length() && i < 3; i++) if (covers.optLong(i) > 0)
            c.covers.add(new Candidate.Cover("https://covers.openlibrary.org/b/id/" + covers.optLong(i) + "-L.jpg?default=false", "Open Library", 0, 0));
        for (String by : list(edition.optJSONArray("contributions"))) contributor(c, by);
        String key = str(edition, "key"); if (key.matches("/books/OL[0-9]+M")) { c.ids.put("openlibrary_edition", key); c.url = "https://openlibrary.org" + key; }
        List<String> series = list(edition.optJSONArray("series"));
        if (!series.isEmpty()) c.series = series.get(0).replaceAll("\\s*[;,]?\\s*(n\\.|vol\\.|#)?\\s*\\d+\\s*$", "").trim();
        if (work != null) {
            String workTitle = str(work, "title");
            if (!workTitle.isEmpty() && !BookMatcher.key(workTitle).equals(BookMatcher.key(c.title))) c.originalTitle = workTitle;
            c.firstPublished = year(str(work, "first_publish_date"));
            for (String s : list(work.optJSONArray("subjects"))) if (c.subjects.size() < 12) c.subjects.add(s);
            Object d = work.opt("description");
            String text = d instanceof JSONObject ? ((JSONObject) d).optString("value") : d instanceof String ? (String) d : "";
            text = clean(text);
            if (!text.isEmpty()) c.descriptions.add(new Candidate.Description(text, textLanguage(text), "Open Library (opera)"));
            String wkey = str(work, "key"); if (wkey.matches("/works/OL[0-9]+W")) c.ids.put("openlibrary_work", wkey);
        }
        return c;
    }

    /** "Translated by X" style lines in Open Library contributions. */
    private static void contributor(Candidate c, String line) {
        String l = line.toLowerCase(Locale.ROOT);
        String name = line.replaceAll("(?i)^.*?(by|di|da)\\s+", "").replaceAll("\\(.*\\)", "").trim();
        if (l.contains("transl") || l.contains("tradu")) c.translators.add(name);
        else if (l.contains("illustr")) c.illustrators.add(name);
        else if (l.contains("edit") || l.contains("cura")) c.editors.add(name);
    }

    public static Candidate openLibraryByIsbn(String isbn) throws Exception {
        JSONObject edition = new JSONObject(new String(OnlineCatalog.request("https://openlibrary.org/isbn/" + isbn + ".json", 2 * 1024 * 1024), StandardCharsets.UTF_8));
        JSONArray works = edition.optJSONArray("works");
        String wk = works == null || works.optJSONObject(0) == null ? "" : works.optJSONObject(0).optString("key");
        JSONObject work = null;
        if (wk.matches("/works/OL[0-9]+W")) try { work = new JSONObject(new String(OnlineCatalog.request("https://openlibrary.org" + wk + ".json", 2 * 1024 * 1024), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        Candidate c = openLibrary(edition, work); c.byIsbn = true; return c;
    }

    // ---- SBN (ICCU) -----------------------------------------------------------------------------

    /** Full SBN record: Italian edition data with roles (author, translator, editor, illustrator), series, Dewey. */
    public static Candidate sbn(JSONObject full) {
        Candidate c = new Candidate("SBN");
        String[] parts = str(full, "titolo").split(" / ", 2);
        String[] titles = parts[0].split(" : ", 2);
        c.title = titles[0].trim(); if (titles.length > 1) c.subtitle = titles[1].trim();
        JSONArray names = full.optJSONArray("nomi");
        for (int i = 0; names != null && i < names.length(); i++) {
            String n = names.optString(i); Matcher m = Pattern.compile("^\\[(.+?)]\\s*(.+)$").matcher(n.trim());
            if (!m.find()) continue;
            String role = m.group(1).toLowerCase(Locale.ROOT), person = SbnCatalog.person(m.group(2));
            if (role.startsWith("autore")) c.authors.add(person);
            else if (role.startsWith("tradutt")) c.translators.add(person);
            else if (role.startsWith("curat") || role.startsWith("editor")) c.editors.add(person);
            else if (role.startsWith("illustr")) c.illustrators.add(person);
        }
        if (c.authors.isEmpty() && !str(full, "autorePrincipale").isEmpty()) c.authors.add(SbnCatalog.person(str(full, "autorePrincipale")));
        if (parts.length > 1 && c.translators.isEmpty()) {
            Matcher t = Pattern.compile("(?i)(?:traduzione|trad\\.) di ([^;]+)").matcher(parts[1]); if (t.find()) c.translators.add(t.group(1).trim());
        }
        String pub = str(full, "pubblicazione");
        String[] place = pub.split(" : ", 2);
        if (place.length > 1) c.publisher = place[1].replaceAll(",? *(stampa |c|©)?\\[?[0-9]{4}.*$", "").replace("[", "").replace("]", "").trim();
        c.year = year(pub); c.editionDate = c.year;
        Matcher p = Pattern.compile("([0-9]{2,5}) *p\\.").matcher(str(full, "descrizioneFisica")); if (p.find()) c.pages = Integer.parseInt(p.group(1));
        c.language = language(str(full, "linguaPubblicazione"));
        String coll = str(full, "collezione");
        if (!coll.isEmpty() && !coll.equalsIgnoreCase("Fuori collana")) c.collection = coll.replaceAll("\\s*;\\s*", " · ");
        c.dewey = str(full, "classificazioneDewey");
        for (String n : list(full.optJSONArray("numeri"))) if (n.startsWith("[ISBN]")) c.isbns.addAll(BookMatcher.isbns(n));
        String bid = str(full, "codiceIdentificativo"); if (!bid.isEmpty()) c.ids.put("sbn", bid);
        return c;
    }

    public static Candidate sbnByIsbn(String isbn) throws Exception {
        JSONArray records = new JSONObject(new String(OnlineCatalog.request(SbnCatalog.BASE + "search.json?isbn=" + isbn + "&start=0&rows=3", 1024 * 1024), StandardCharsets.UTF_8)).optJSONArray("briefRecords");
        JSONObject first = records == null ? null : records.optJSONObject(0);
        if (first == null) return null;
        JSONObject full = new JSONObject(new String(OnlineCatalog.request(SbnCatalog.BASE + "full.json?bid=" + URLEncoder.encode(first.optString("codiceIdentificativo"), "UTF-8"), 1024 * 1024), StandardCharsets.UTF_8));
        Candidate c = sbn(full); c.byIsbn = true; if (c.isbns.isEmpty()) c.isbns.add(isbn); return c;
    }

    // ---- Internet Archive ------------------------------------------------------------------------

    public static Candidate internetArchive(JSONObject doc) {
        Candidate c = new Candidate("Internet Archive");
        c.title = first(doc.opt("title")); c.authors.addAll(all(doc.opt("creator"))); c.publisher = first(doc.opt("publisher"));
        c.editionDate = first(doc.opt("date")); c.year = year(c.editionDate); c.language = language(first(doc.opt("language")));
        for (String i : all(doc.opt("isbn"))) c.isbns.addAll(BookMatcher.isbns(i));
        for (String s : all(doc.opt("subject"))) if (c.subjects.size() < 8) c.subjects.add(s);
        String id = first(doc.opt("identifier"));
        if (!id.isEmpty()) { c.ids.put("internet_archive", id); c.url = "https://archive.org/details/" + id; c.covers.add(new Candidate.Cover("https://archive.org/services/img/" + id, "Internet Archive", 0, 0)); }
        String text = clean(first(doc.opt("description")));
        if (text.length() > 80) c.descriptions.add(new Candidate.Description(text, c.language.isEmpty() ? textLanguage(text) : c.language, "Internet Archive"));
        return c;
    }

    public static Candidate internetArchiveByIsbn(String isbn) throws Exception {
        String url = "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode("isbn:" + isbn, "UTF-8")
            + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=publisher&fl%5B%5D=date&fl%5B%5D=language&fl%5B%5D=isbn&fl%5B%5D=subject&fl%5B%5D=description&rows=1&output=json";
        JSONArray docs = new JSONObject(new String(OnlineCatalog.request(url, 1024 * 1024), StandardCharsets.UTF_8)).optJSONObject("response").optJSONArray("docs");
        if (docs == null || docs.optJSONObject(0) == null) return null;
        Candidate c = internetArchive(docs.getJSONObject(0)); c.byIsbn = true; return c;
    }

    // ---- Hardcover (user token) ------------------------------------------------------------------

    public static Candidate hardcover(JSONObject edition) {
        Candidate c = new Candidate("Hardcover");
        c.title = str(edition, "title"); c.subtitle = str(edition, "subtitle"); c.pages = Math.max(0, edition.optInt("pages", 0));
        c.editionDate = str(edition, "release_date"); c.year = year(c.editionDate);
        JSONObject pub = edition.optJSONObject("publisher"); if (pub != null) c.publisher = str(pub, "name");
        JSONObject lang = edition.optJSONObject("language"); if (lang != null) c.language = language(lang.optString("code3", lang.optString("language")));
        c.isbns.addAll(BookMatcher.isbns(str(edition, "isbn_13") + " " + str(edition, "isbn_10")));
        JSONObject img = edition.optJSONObject("image");
        JSONObject book = edition.optJSONObject("book");
        if ((img == null || str(img, "url").isEmpty()) && book != null) img = book.optJSONObject("image");
        if (img != null && !str(img, "url").isEmpty()) c.covers.add(new Candidate.Cover(str(img, "url"), "Hardcover", img.optInt("width", 0), img.optInt("height", 0)));
        if (book != null) {
            if (!str(book, "title").isEmpty() && !BookMatcher.key(str(book, "title")).equals(BookMatcher.key(c.title))) c.originalTitle = str(book, "title");
            c.firstPublished = book.isNull("release_year") ? "" : str(book, "release_year");
            String text = clean(str(book, "description"));
            if (!text.isEmpty()) c.descriptions.add(new Candidate.Description(text, textLanguage(text), "Hardcover (opera)"));
            JSONArray contributions = book.optJSONArray("contributions");
            for (int i = 0; contributions != null && i < contributions.length(); i++) {
                JSONObject ct = contributions.optJSONObject(i); if (ct == null) continue;
                String name = ct.optJSONObject("author") == null ? "" : ct.optJSONObject("author").optString("name"), role = str(ct, "contribution").toLowerCase(Locale.ROOT);
                if (name.isEmpty()) continue;
                if (role.isEmpty() || role.contains("author")) c.authors.add(name); else if (role.contains("transl")) c.translators.add(name); else if (role.contains("illustr")) c.illustrators.add(name); else if (role.contains("edit")) c.editors.add(name);
            }
            JSONArray series = book.optJSONArray("book_series");
            JSONObject s = series == null ? null : series.optJSONObject(0);
            if (s != null && s.optJSONObject("series") != null) { c.series = s.optJSONObject("series").optString("name"); c.number = s.optDouble("position", 0); }
            if (!str(book, "slug").isEmpty()) c.url = "https://hardcover.app/books/" + str(book, "slug");
            if (book.optInt("id") > 0) c.ids.put("hardcover", Integer.toString(book.optInt("id")));
        }
        return c;
    }

    public static Candidate hardcoverByIsbn(String isbn, String token) throws Exception {
        String field = isbn.length() == 13 ? "isbn_13" : "isbn_10";
        String q = "query($i:String!){ editions(where:{" + field + ":{_eq:$i}}, limit:1) { title subtitle pages release_date isbn_13 isbn_10 image { url width height }"
            + " publisher { name } language { code3 language } book { id title slug description release_year image { url width height }"
            + " contributions { contribution author { name } } book_series { position series { name } } } } }";
        Map<String, String> h = new HashMap<>(); h.put("authorization", token.regionMatches(true, 0, "Bearer ", 0, 7) ? token : "Bearer " + token);
        SyncCore.Response r = SyncCore.http("POST", "https://api.hardcover.app/v1/graphql", new JSONObject().put("query", q).put("variables", new JSONObject().put("i", isbn)).toString(), h, null, null, 4 * 1024 * 1024);
        if (r.status != 200) return null;
        JSONObject data = new JSONObject(r.body).optJSONObject("data");
        JSONArray editions = data == null ? null : data.optJSONArray("editions");
        if (editions == null || editions.optJSONObject(0) == null) return null;
        Candidate c = hardcover(editions.getJSONObject(0)); c.byIsbn = true; return c;
    }

    // ---- ISBNdb (optional paid key) --------------------------------------------------------------

    public static Candidate isbndb(JSONObject book) {
        Candidate c = new Candidate("ISBNdb");
        c.title = str(book, "title"); c.authors.addAll(list(book.optJSONArray("authors"))); c.publisher = str(book, "publisher");
        c.editionDate = str(book, "date_published"); c.year = year(c.editionDate); c.pages = Math.max(0, book.optInt("pages", 0));
        c.language = language(str(book, "language")); c.format = str(book, "binding");
        c.isbns.addAll(BookMatcher.isbns(str(book, "isbn13") + " " + str(book, "isbn")));
        for (String s : list(book.optJSONArray("subjects"))) if (c.subjects.size() < 10) c.subjects.add(s);
        String text = clean(!str(book, "synopsis").isEmpty() ? str(book, "synopsis") : str(book, "overview"));
        if (!text.isEmpty()) c.descriptions.add(new Candidate.Description(text, c.language.isEmpty() ? textLanguage(text) : c.language, "ISBNdb"));
        if (!str(book, "image").isEmpty()) c.covers.add(new Candidate.Cover(str(book, "image"), "ISBNdb", 0, 0));
        return c;
    }

    public static Candidate isbndbByIsbn(String isbn, String key) throws Exception {
        Map<String, String> h = new HashMap<>(); h.put("Authorization", key);
        SyncCore.Response r = SyncCore.http("GET", "https://api2.isbndb.com/book/" + isbn, null, h, null, null, 1024 * 1024);
        if (r.status != 200) return null;
        JSONObject book = new JSONObject(r.body).optJSONObject("book");
        if (book == null) return null;
        Candidate c = isbndb(book); c.byIsbn = true; return c;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    static String iso2(String iso3) { for (String[] l : LANGS) if (l[0].equals(iso3)) return l[1]; return ""; }
    private static String first(Object v) { List<String> a = all(v); return a.isEmpty() ? "" : a.get(0); }
    private static List<String> all(Object v) {
        if (v instanceof JSONArray) return list((JSONArray) v);
        if (v == null || v == JSONObject.NULL) return new ArrayList<>();
        String s = String.valueOf(v).trim(); return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Collections.singletonList(s));
    }
    static String clean(String text) { return CatalogImport.plain(text == null ? "" : text).replaceAll("\\s*\\(\\[source\\]\\[\\d+\\]\\)", "").replaceAll("-{3,}[\\s\\S]*$", "").trim(); }
}
