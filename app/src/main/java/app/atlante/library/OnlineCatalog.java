package app.atlante.library;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** On-demand HTTPS lookups only; never uploads the local catalog, notes or book files. */
final class OnlineCatalog {
    private static long lastRequest;
    private static final LinkedHashMap<String, String> cache = new LinkedHashMap<>();

    private static byte[] request(String address, int limit) throws Exception {
        long delay = 1100 - (System.currentTimeMillis() - lastRequest);
        if (delay > 0) Thread.sleep(delay);
        lastRequest = System.currentTimeMillis();
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

    static ArrayList<Book> search(String query) throws Exception {
        String response = cache.get(query);
        if (response == null) {
            response = new String(request("https://openlibrary.org/search.json?q=" + URLEncoder.encode(query, "UTF-8")
                + "&lang=it&limit=20&fields=key,title,author_name,first_publish_year,cover_i", 2 * 1024 * 1024), StandardCharsets.UTF_8);
            if (cache.size() >= 8) cache.remove(cache.keySet().iterator().next());
            cache.put(query, response);
        }
        return parseSearch(response);
    }

    static ArrayList<Book> parseSearch(String response) throws Exception {
        JSONArray docs = new JSONObject(response).getJSONArray("docs");
        ArrayList<Book> books = new ArrayList<>();
        for (int i = 0; i < docs.length() && books.size() < 20; i++) {
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
            books.add(b);
        }
        return books;
    }

    static String join(JSONArray values, int maximum) {
        ArrayList<String> strings = new ArrayList<>();
        for (int i = 0; i < Math.min(values.length(), maximum); i++) strings.add(values.optString(i));
        return String.join(", ", strings);
    }

    static void enrich(Book book) throws Exception {
        if (!book.source.matches("/works/OL[0-9]+W")) throw new IOException("Scheda non valida");
        String response = new String(request("https://openlibrary.org" + book.source + ".json", 2 * 1024 * 1024), StandardCharsets.UTF_8);
        enrichFrom(book, response);
        JSONObject editions = new JSONObject(new String(request("https://openlibrary.org" + book.source + "/editions.json?limit=20", 2 * 1024 * 1024), StandardCharsets.UTF_8));
        enrichEdition(book, editions);
        if (book.coverId > 0) {
            try { book.onlineCover = request("https://covers.openlibrary.org/b/id/" + book.coverId + "-M.jpg?default=false", 2 * 1024 * 1024); }
            catch (IOException ignored) { /* A missing cover never prevents consulting the record. */ }
        }
    }

    static void enrichEdition(Book book, JSONObject response) throws Exception {
        JSONArray entries = response.optJSONArray("entries");
        if (entries == null || entries.length() == 0) return;
        JSONObject chosen = entries.getJSONObject(0);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject edition = entries.getJSONObject(i); JSONArray languages = edition.optJSONArray("languages");
            if (languages != null && languages.toString().contains("/languages/ita")) { chosen = edition; break; }
        }
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

    static void enrichFrom(Book book, String response) throws Exception {
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
