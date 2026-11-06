package app.atlante.library;

import org.json.JSONException;
import org.json.JSONObject;

final class Book {
    String id, title = "", author = "Autore sconosciuto", series = "", genre = "";
    String description = "", format = "", status = "Da leggere";
    String source = "", notes = "";
    String edition = "", isbn = "", publisher = "", language = "", year = "";
    String subtitle = "", tags = "", location = "", medium = "", started = "", finished = "";
    int rating;
    long addedAt;
    long coverId;
    transient byte[] onlineCover;
    /** Set by an import that restored the missing file of a book already in the catalog. */
    transient boolean reattached;
    int currentPage, totalPages;
    double number;
    int page;

    JSONObject json() throws JSONException {
        return new JSONObject().put("id", id).put("title", title).put("author", author)
            .put("subtitle", subtitle).put("tags", tags).put("location", location).put("medium", medium)
            .put("started", started).put("finished", finished).put("rating", rating).put("addedAt", addedAt)
            .put("series", series).put("genre", genre).put("description", description)
            .put("edition", edition).put("isbn", isbn).put("publisher", publisher).put("language", language).put("year", year).put("coverId", coverId).put("source", source).put("notes", notes).put("currentPage", currentPage).put("totalPages", totalPages).put("format", format).put("status", status).put("number", number).put("page", page);
    }

    static Book from(JSONObject j) throws JSONException {
        Book b = new Book();
        b.id = j.getString("id");
        if (!b.id.matches("[0-9a-f]{64}")) throw new JSONException("Identificatore non valido");
        b.subtitle = j.optString("subtitle"); b.tags = j.optString("tags"); b.location = j.optString("location");
        b.medium = j.optString("medium"); b.started = j.optString("started"); b.finished = j.optString("finished");
        b.rating = Math.max(0, Math.min(5, j.optInt("rating"))); b.addedAt = j.optLong("addedAt");
        b.title = j.optString("title"); b.author = j.optString("author", b.author);
        b.series = j.optString("series"); b.genre = j.optString("genre");
        b.description = j.optString("description"); b.format = j.getString("format");
        if (!b.format.matches("EPUB|TXT|CBZ|CATALOGO")) throw new JSONException("Formato non valido");
        b.status = j.optString("status", b.status); b.number = j.optDouble("number", 0);
        b.page = Math.max(0, j.optInt("page", 0));
        b.edition = j.optString("edition"); b.isbn = j.optString("isbn"); b.publisher = j.optString("publisher"); b.language = j.optString("language"); b.year = j.optString("year"); b.coverId = j.optLong("coverId", 0);
        b.source = j.optString("source"); b.notes = j.optString("notes");
        b.currentPage = Math.max(0, j.optInt("currentPage", 0));
        b.totalPages = Math.max(0, j.optInt("totalPages", 0));
        return b;
    }
}
