package app.atlante.library.online;

import app.atlante.library.model.Book;
import app.atlante.library.ui.Covers;
import app.atlante.library.ebook.EbookParser;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** On-demand HTTPS lookups only; never uploads the local catalog, notes or book files. */
public final class OnlineCatalog {
    private static final Map<String, Long> nextRequest = new HashMap<>();
    private static File cacheDirectory;
    private static final LinkedHashMap<String, String> cache = new LinkedHashMap<>();
    private static final Map<String,Long> cacheTimes = new HashMap<>();
    public static synchronized void configure(File directory) { cacheDirectory = directory; }
    private static String hash(String value) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) hex.append(String.format(Locale.ROOT,"%02x",b & 255));
        return hex.toString();
    }
    public static byte[] request(String address, int limit) throws Exception {
        File file = cacheDirectory == null ? null : new File(cacheDirectory, hash(address));
        long ttl = address.contains("search.json") ? 15 * 60 * 1000L : 24 * 60 * 60 * 1000L;
        if (file != null && file.isFile() && file.length() <= limit && System.currentTimeMillis()-file.lastModified() < ttl) {
            try (InputStream in = new FileInputStream(file)) { return EbookParser.read(in,limit); }
            catch (IOException ignored) { /* A broken cache falls back to the network. */ }
        }
        byte[] bytes = download(address, limit);
        if (file != null) {
            try {
                if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) throw new IOException("Cache non disponibile");
                File temporary = File.createTempFile("cache-", ".tmp",cacheDirectory);
                try {
                    try (OutputStream out = new FileOutputStream(temporary)) { out.write(bytes); }
                    if (!temporary.renameTo(file)) temporary.delete();
                } finally { temporary.delete(); }
                File[] entries = cacheDirectory.listFiles();
                if (entries != null && entries.length > 64) {
                    Arrays.sort(entries,Comparator.comparingLong(File::lastModified));
                    for(int i=0;i<entries.length-64;i++) entries[i].delete();
                }
            } catch (IOException ignored) { /* Searches also work when the cache is full. */ }
        }
        return bytes;
    }

    public static synchronized File cacheDirectory() { return cacheDirectory; }

    /** Parallel downloads of covers by Open Library cover id (see {@link #interval}). */
    private static final java.util.concurrent.Semaphore coverSlots = new java.util.concurrent.Semaphore(6);

    /**
     * Minimum gap between two requests of the same kind. Open Library covers by cover id or OLID are not
     * rate limited by Open Library: up to 10 per second (6 at a time). Images on a static CDN (Hardcover's
     * assets on Cloudflare, Apple's mzstatic) have no limit: they share the same 6 parallel slots, without gap.
     * Everything else keeps about one per second, the limit Open Library sets for its API and for covers by
     * ISBN (over it the phone's IP is blocked).
     * Measured (DiscoverSpeedLiveTest, 12 Hardcover covers): with the one-per-second lane 6 covers took 5.3–6.3 s
     * and 12 took 11.6–12.6 s.
     */
    public static long interval(String address) {
        return staticImage(address) ? 0 : coverById(address) ? 100 : 1050;
    }
    public static boolean coverById(String address) {
        return address.startsWith("https://covers.openlibrary.org/b/id/") || address.startsWith("https://covers.openlibrary.org/b/olid/") || staticImage(address);
    }
    /** An image file on a CDN: no API behind it, no rate limit. */
    public static boolean staticImage(String address) {
        return address.startsWith("https://assets.hardcover.app/") || (address.startsWith("https://") && address.contains(".mzstatic.com/"));
    }

    /** Network only: shared rate limit per host (covers by id have their own, faster one), no cache. */
    public static byte[] download(String address, int limit) throws Exception {
        boolean cover = coverById(address);
        String lane = staticImage(address) ? "static-images" : cover ? "covers-by-id" : new URL(address).getHost(); long wait;
        synchronized (nextRequest) {
            long now = System.currentTimeMillis(); long slot = Math.max(now,nextRequest.containsKey(lane) ? nextRequest.get(lane) : now);
            nextRequest.put(lane,slot+interval(address)); wait=slot-now;
        }
        if (wait > 0) Thread.sleep(wait);
        if (!cover) return fetch(address, limit);
        coverSlots.acquire();
        try { return fetch(address, limit); } finally { coverSlots.release(); }
    }

    private static byte[] fetch(String address, int limit) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(address).openConnection();
        connection.setConnectTimeout(12000); connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "AtlanteLibraryAndroid (https://github.com/Mancio24x/atlante-android)");
        try {
            int status = connection.getResponseCode();
            if (status == 429) throw new IOException("Il catalogo è occupato: attendi e riprova.");
            if (status != 200) throw new IOException("Il catalogo ha risposto con codice " + status);
            try (InputStream in = connection.getInputStream()) { return EbookParser.read(in, limit); }
        } finally { connection.disconnect(); }
    }

    /** Book languages: Open Library (MARC) code, ISO code for the {@code lang} parameter, name. "" means any language. */
    public static final String[][] LANGUAGES = {
        {"ita", "it", "Italiano"}, {"eng", "en", "English"}, {"fre", "fr", "Français"}, {"ger", "de", "Deutsch"},
        {"spa", "es", "Español"}, {"por", "pt", "Português"}, {"", "", "Tutte le lingue"}
    };
    public static String languageName(String code) {
        for (String[] l : LANGUAGES) if (l[0].equals(code)) return l[2];
        return "Tutte le lingue";
    }

    public static ArrayList<Book> search(String query) throws Exception { return search(query, "ita", false); }

    /**
     * Searches works, preferring {@code language}: works with an edition in that language come first and
     * show that edition's title and cover; with {@code only} the others are left out.
     */
    public static ArrayList<Book> search(String query, String language, boolean only) throws Exception {
        query = query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String iso = "";
        for (String[] l : LANGUAGES) if (l[0].equals(language)) iso = l[1];
        String key = language + "|" + only + "|" + query;
        String response;
        synchronized (cache) {
            response = cache.get(key);
            Long time = cacheTimes.get(key);
            if (time == null || System.currentTimeMillis()-time > 15*60*1000L) response=null;
        }
        if (response == null) {
            response = new String(request("https://openlibrary.org/search.json?q=" + URLEncoder.encode(query, "UTF-8")
                + (iso.isEmpty() ? "" : "&lang=" + iso) + "&limit=" + (only ? 40 : 20)
                + "&fields=key,title,author_name,first_publish_year,cover_i,language,editions,editions.key,editions.title,editions.language,editions.cover_i",
                4 * 1024 * 1024), StandardCharsets.UTF_8);
            synchronized (cache) {
                if (cache.size() >= 8) { String oldest = cache.keySet().iterator().next(); cache.remove(oldest); cacheTimes.remove(oldest); }
                cache.put(key, response); cacheTimes.put(key,System.currentTimeMillis());
            }
        }
        return parseSearch(response, language, only);
    }

    public static ArrayList<Book> parseSearch(String response) throws Exception { return parseSearch(response, "", false); }

    public static ArrayList<Book> parseSearch(String response, String language, boolean only) throws Exception {
        JSONArray docs = new JSONObject(response).getJSONArray("docs");
        ArrayList<Book> preferred = new ArrayList<>(), others = new ArrayList<>();
        for (int i = 0; i < docs.length(); i++) {
            JSONObject doc = docs.getJSONObject(i); String key = doc.optString("key");
            if (key.matches("OL[0-9]+W")) key = "/works/" + key;
            if (!key.matches("/works/OL[0-9]+W") || doc.optString("title").trim().isEmpty()) continue;
            Book b = new Book(); b.format = "CATALOGO"; b.source = key; b.title = doc.getString("title");
            StringBuilder id = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(("openlibrary:" + key).getBytes(StandardCharsets.UTF_8)))
                id.append(String.format(Locale.ROOT, "%02x", value & 255));
            b.id = id.toString();
            JSONArray authors = doc.optJSONArray("author_name");
            if (authors != null && authors.length() > 0) b.author = join(authors, 10);
            b.year = doc.optString("first_publish_year", ""); b.coverId = Math.max(0, doc.optLong("cover_i", 0));
            boolean inLanguage = language.isEmpty() || contains(doc.optJSONArray("language"), language);
            // Open Library returns its best edition for the requested language; use it only if it really is in that language.
            JSONObject editions = doc.optJSONObject("editions");
            JSONArray editionDocs = editions == null ? null : editions.optJSONArray("docs");
            JSONObject edition = editionDocs == null ? null : editionDocs.optJSONObject(0);
            if (!language.isEmpty() && edition != null && contains(edition.optJSONArray("language"), language)) {
                inLanguage = true; b.language = language;
                if (!edition.optString("title").trim().isEmpty()) b.title = edition.optString("title").trim();
                if (edition.optString("key").matches("/books/OL[0-9]+M")) b.edition = edition.optString("key");
                if (edition.optLong("cover_i", 0) > 0) b.coverId = edition.optLong("cover_i");
            }
            if (inLanguage) preferred.add(b); else if (!only) others.add(b);
        }
        ArrayList<Book> books = new ArrayList<>(preferred); books.addAll(others);
        while (books.size() > 20) books.remove(books.size() - 1);
        return books;
    }

    private static boolean contains(JSONArray values, String value) {
        if (values == null) return false;
        for (int i = 0; i < values.length(); i++) if (value.equals(values.optString(i))) return true;
        return false;
    }

    public static String join(JSONArray values, int maximum) {
        ArrayList<String> strings = new ArrayList<>();
        for (int i = 0; i < Math.min(values.length(), maximum); i++) strings.add(values.optString(i));
        return String.join(", ", strings);
    }

    public static void enrich(Book book) throws Exception { enrich(book, book.language.isEmpty() ? "ita" : book.language); }

    public static void enrich(Book book, String language) throws Exception {
        if (!book.source.matches("/works/OL[0-9]+W")) throw new IOException("Scheda non valida");
        String response = new String(request("https://openlibrary.org" + book.source + ".json", 2 * 1024 * 1024), StandardCharsets.UTF_8);
        enrichFrom(book, response);
        JSONObject editions = new JSONObject(new String(request("https://openlibrary.org" + book.source + "/editions.json?limit=50", 4 * 1024 * 1024), StandardCharsets.UTF_8));
        enrichEdition(book, editions, language);
    }

    public static byte[] loadCover(Book book) {
        if (book.onlineCover != null) return book.onlineCover;
        try {
            if (book.coverId > 0) return request("https://covers.openlibrary.org/b/id/"+book.coverId+"-M.jpg?default=false",2*1024*1024);
            if (book.isbn.matches("[0-9]{9}[0-9Xx]|97[89][0-9]{10}")) return request("https://covers.openlibrary.org/b/isbn/"+book.isbn+"-M.jpg?default=false",2*1024*1024);
        } catch (Exception ignored) { /* Covers are optional. */ }
        return null;
    }

    /** Finds the best cover for a book (see {@link CoverSearch}): image bytes (JPEG/PNG) or null. */
    public static byte[] findCover(Book book) {
        try { CoverSearch.Cover c = CoverSearch.INSTANCE.best(book, (url, limit) -> { try { return request(url, limit); } catch (Exception e) { throw new RuntimeException(e); } }, 8); return c == null ? null : c.getBytes(); }
        catch (RuntimeException e) { return null; }
    }


    /** Same title after normalizing, or the same main title when one adds a subtitle after ":" or " - ". */
    public static boolean sameTitle(String a, String b) {
        String x = SbnCatalog.normalize(a), y = SbnCatalog.normalize(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        return x.equals(y) || SbnCatalog.normalize(mainTitle(a)).equals(SbnCatalog.normalize(mainTitle(b)));
    }
    /** The two forms {@link #sameTitle} compares (whole and main title), or null for an empty title: for indexes. */
    public static String[] titleKeys(String title) {
        String whole = SbnCatalog.normalize(title);
        return whole.isEmpty() ? null : new String[]{whole, SbnCatalog.normalize(mainTitle(title))};
    }
    private static String mainTitle(String title) { return title.split("\\s*[:;]\\s*|\\s+[-–—]\\s+|\\.\\s", 2)[0]; }

    private static byte[] image(byte[] bytes) {
        boolean jpeg = bytes.length > 3 && (bytes[0] & 255) == 0xFF && (bytes[1] & 255) == 0xD8;
        boolean png = bytes.length > 8 && (bytes[0] & 255) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        return (jpeg || png) && bytes.length > 1000 ? bytes : null;
    }

    /** Fills what a library record lacks (cover, plot, subjects, pages) from Open Library's edition with this ISBN. */
    public static void enrichByIsbn(Book book) throws Exception {
        if (!book.isbn.matches("[0-9]{9}[0-9Xx]|97[89][0-9]{10}")) return;
        JSONObject edition = new JSONObject(new String(request("https://openlibrary.org/isbn/" + book.isbn + ".json", 2 * 1024 * 1024), StandardCharsets.UTF_8));
        JSONArray covers = edition.optJSONArray("covers");
        if (book.coverId == 0 && covers != null && covers.optLong(0) > 0) book.coverId = covers.optLong(0);
        if (book.totalPages == 0) book.totalPages = Math.max(0, edition.optInt("number_of_pages", 0));
        JSONArray works = edition.optJSONArray("works");
        String work = works == null || works.optJSONObject(0) == null ? "" : works.optJSONObject(0).optString("key");
        if (work.matches("/works/OL[0-9]+W") && book.description.isEmpty())
            enrichFrom(book, new String(request("https://openlibrary.org" + work + ".json", 2 * 1024 * 1024), StandardCharsets.UTF_8));
    }

    public static void enrichEdition(Book book, JSONObject response) throws Exception { enrichEdition(book, response, "ita"); }

    /** Picks the edition found by the search, else the first one in the preferred language, else the first one. */
    public static void enrichEdition(Book book, JSONObject response, String language) throws Exception {
        JSONArray entries = response.optJSONArray("entries");
        if (entries == null || entries.length() == 0) return;
        JSONObject chosen = entries.getJSONObject(0), inLanguage = null, found = null;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject edition = entries.getJSONObject(i); JSONArray languages = edition.optJSONArray("languages");
            if (!book.edition.isEmpty() && book.edition.equals(edition.optString("key"))) found = edition;
            if (inLanguage == null && !language.isEmpty() && languages != null && languages.toString().contains("/languages/" + language + "\"")) inLanguage = edition;
        }
        if (found != null) chosen = found; else if (inLanguage != null) chosen = inLanguage;
        String editionKey = chosen.optString("key");
        if (editionKey.matches("/books/OL[0-9]+M")) book.edition = editionKey;
        if (!chosen.optString("title").isEmpty()) book.title = chosen.optString("title");
        book.year = chosen.optString("publish_date", book.year);
        JSONArray publishers = chosen.optJSONArray("publishers");
        if (publishers != null) book.publisher = join(publishers, 4);
        JSONArray isbn = chosen.optJSONArray("isbn_13");
        if (isbn == null) isbn = chosen.optJSONArray("isbn_10");
        if (isbn != null) book.isbn = join(isbn, 3);
        JSONArray languages = chosen.optJSONArray("languages");
        if (languages != null && languages.length() > 0) book.language = languages.getJSONObject(0).optString("key").replace("/languages/", "");
        book.totalPages = Math.max(0, chosen.optInt("number_of_pages", 0));
        JSONArray covers = chosen.optJSONArray("covers");
        if (covers != null && covers.optLong(0) > 0) book.coverId = covers.optLong(0);
    }

    public static void enrichFrom(Book book, String response) throws Exception {
        JSONObject work = new JSONObject(response);
        Object description = work.opt("description");
        if (description instanceof JSONObject) book.description = ((JSONObject)description).optString("value");
        else if (description instanceof String) book.description = (String)description;
        JSONArray subjects = work.optJSONArray("subjects");
        if (subjects != null) book.genre = join(subjects, 12);
        JSONArray covers = work.optJSONArray("covers");
        if (book.coverId == 0 && covers != null) book.coverId = Math.max(0, covers.optLong(0));

    }
}
