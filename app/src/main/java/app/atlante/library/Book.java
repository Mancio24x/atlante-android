package app.atlante.library;

import org.json.JSONException;
import org.json.JSONObject;

final class Book {
    String id, title = "", author = "Autore sconosciuto", series = "", genre = "";
    String description = "", format = "", status = "Da leggere";
    double number;
    int page;

    JSONObject json() throws JSONException {
        return new JSONObject().put("id", id).put("title", title).put("author", author)
            .put("series", series).put("genre", genre).put("description", description)
            .put("format", format).put("status", status).put("number", number).put("page", page);
    }

    static Book from(JSONObject j) throws JSONException {
        Book b = new Book();
        b.id = j.getString("id");
        if (!b.id.matches("[0-9a-f]{64}")) throw new JSONException("Identificatore non valido");
        b.title = j.optString("title"); b.author = j.optString("author", b.author);
        b.series = j.optString("series"); b.genre = j.optString("genre");
        b.description = j.optString("description"); b.format = j.getString("format");
        if (!b.format.matches("EPUB|TXT|CBZ")) throw new JSONException("Formato non valido");
        b.status = j.optString("status", b.status); b.number = j.optDouble("number", 0);
        b.page = Math.max(0, j.optInt("page", 0));
        return b;
    }
}
