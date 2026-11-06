package app.atlante.library.model;

import app.atlante.library.ebook.EbookParser;

import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class Book {
    /** The four reading states, in the order shown by the app. */
    public static final String TO_READ = "Da leggere", READING = "In lettura", PAUSED = "In pausa", READ = "Letto";
    public static final String[] STATES = {TO_READ, READING, PAUSED, READ};

    /** Known states are kept; "Abbandonato" (older versions) goes back to the books to read. */
    public static String normalizeStatus(String status) {
        if (status == null) return TO_READ;
        switch (status) {
            case READING: case PAUSED: case READ: return status;
            default: return TO_READ;
        }
    }

    public JSONObject journal = new JSONObject();
    /**
     * Extended bibliographic data (original title, translators, publisher's series, identifiers,
     * subjects, first publication…), the source of every field and the fields edited by hand.
     * Kept apart from the reading journal so that metadata updates never touch personal data.
     */
    public JSONObject meta = new JSONObject();
    private JSONObject retained = new JSONObject();

    public String id, title = "", author = "Autore sconosciuto", series = "", genre = "";
    public String description = "", format = "", status = "Da leggere";
    public String source = "", notes = "";
    public String edition = "", isbn = "", publisher = "", language = "", year = "";
    public String subtitle = "", tags = "", location = "", medium = "", started = "", finished = "";
    public int rating;
    public long addedAt;
    public long coverId;
    public transient byte[] onlineCover;
    /** Set by an import that restored the missing file of a book already in the catalog. */
    public transient boolean reattached;
    public int currentPage, totalPages;
    /** Progress in percent, used only while the number of pages is unknown. */
    public int percent;
    /** Table of contents with the reader's own rating and comment for each chapter. */
    public final ArrayList<Chapter> chapters = new ArrayList<>();

    public static final class Chapter {
        public String title = "", comment = "";
        public int rating;
        public Chapter() { }
        public Chapter(String title) { this.title = title; }
        public JSONObject json() throws JSONException { return new JSONObject().put("title", title).put("rating", rating).put("comment", comment); }
        public static Chapter from(JSONObject j) {
            Chapter c = new Chapter(j.optString("title"));
            c.rating = Math.max(0, Math.min(5, j.optInt("rating"))); c.comment = j.optString("comment");
            return c;
        }
    }
    public double number;
    public int page;

    /** A book being read gets a start date, a finished one an end date and all its pages. */
    public static void applyStatusEffects(Book b, String today) {
        if (b.status.equals(READING) && b.started.isEmpty()) b.started = today;
        if (b.status.equals(READ)) {
            if (b.finished.isEmpty()) b.finished = today;
            if (b.totalPages > 0) b.currentPage = b.totalPages;
            else b.percent = 100;
        }
    }

    /** 2.0 → "2", 2.5 → "2.5". */
    public static String formatNumber(double n) { return n == (long) n ? Long.toString((long) n) : Double.toString(n); }

    public JSONObject json() throws JSONException {
        return new JSONObject(retained.toString()).put("journal", journal).put("meta", meta).put("id", id).put("title", title).put("author", author)
            .put("subtitle", subtitle).put("tags", tags).put("location", location).put("medium", medium)
            .put("started", started).put("finished", finished).put("rating", rating).put("addedAt", addedAt)
            .put("series", series).put("genre", genre).put("description", description)
            .put("edition", edition).put("isbn", isbn).put("publisher", publisher).put("language", language).put("year", year).put("coverId", coverId).put("source", source).put("notes", notes).put("currentPage", currentPage).put("totalPages", totalPages).put("format", format).put("status", status).put("number", number).put("page", page)
            .put("percent", percent).put("chapters", chaptersJson());
    }

    private JSONArray chaptersJson() throws JSONException {
        JSONArray array = new JSONArray();
        for (Chapter c : chapters) array.put(c.json());
        return array;
    }

    public static Book from(JSONObject j) throws JSONException {
        Book b = new Book();
        b.retained = new JSONObject(j.toString());
        JSONObject journal = j.optJSONObject("journal");
        if (journal != null) b.journal = new JSONObject(journal.toString());
        JSONObject meta = j.optJSONObject("meta");
        if (meta != null) b.meta = new JSONObject(meta.toString());
        b.id = j.getString("id");
        if (!b.id.matches("[0-9a-f]{64}")) throw new JSONException("Identificatore non valido");
        b.subtitle = j.optString("subtitle"); b.tags = j.optString("tags"); b.location = j.optString("location");
        b.medium = j.optString("medium"); b.started = j.optString("started"); b.finished = j.optString("finished");
        b.rating = Math.max(0, Math.min(5, j.optInt("rating"))); b.addedAt = j.optLong("addedAt");
        b.title = j.optString("title"); b.author = j.optString("author", b.author);
        b.series = j.optString("series"); b.genre = j.optString("genre");
        b.description = j.optString("description"); b.format = j.getString("format");
        if (!b.format.matches("EPUB|TXT|CBZ|CATALOGO")) throw new JSONException("Formato non valido");
        b.status = normalizeStatus(j.optString("status", b.status)); b.number = j.optDouble("number", 0);
        b.page = Math.max(0, j.optInt("page", 0));
        b.edition = j.optString("edition"); b.isbn = j.optString("isbn"); b.publisher = j.optString("publisher"); b.language = j.optString("language"); b.year = j.optString("year"); b.coverId = j.optLong("coverId", 0);
        b.source = j.optString("source"); b.notes = j.optString("notes");
        b.currentPage = Math.max(0, j.optInt("currentPage", 0));
        b.totalPages = Math.max(0, j.optInt("totalPages", 0));
        b.percent = Math.max(0, Math.min(100, j.optInt("percent", 0)));
        JSONArray chapters = j.optJSONArray("chapters");
        if (chapters != null) for (int i = 0; i < chapters.length() && i < EbookParser.MAX_CHAPTERS; i++) {
            JSONObject c = chapters.optJSONObject(i); if (c != null) b.chapters.add(Chapter.from(c));
        }
        return b;
    }
}
