package app.atlante.library;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Highlights and notes from a Kindle's "My Clippings.txt" (Italian or English device language).
 * Bookmarks are skipped. Amazon offers no way to read position or reading state, so only these come in.
 */
final class KindleClippings {
    static final class Clip {
        String title = "", author = "", type = "Citazione", text = "";
        int page, location;
    }

    private static final Pattern PAGE = Pattern.compile("(?i)(?:pagina|page|pag\\.)\\s*(\\d+)");
    private static final Pattern LOCATION = Pattern.compile("(?i)(?:posizione|location|loc\\.)\\s*(\\d+)");

    static List<Clip> parse(String text) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<Clip> clips = new ArrayList<>();
        for (String block : text.split("\r?\n==========\r?\n?")) {
            String[] lines = block.replace("\r", "").split("\n");
            int i = 0; while (i < lines.length && lines[i].trim().isEmpty()) i++;
            if (lines.length - i < 2) continue;
            String head = lines[i].replace("\uFEFF", "").trim(), meta = lines[i + 1].toLowerCase(Locale.ROOT);
            if (meta.contains("segnalibro") || meta.contains("bookmark")) continue;
            StringBuilder body = new StringBuilder();
            for (int j = i + 2; j < lines.length; j++) { if (body.length() > 0 || !lines[j].trim().isEmpty()) body.append(body.length() > 0 ? "\n" : "").append(lines[j]); }
            if (body.toString().trim().isEmpty()) continue;
            Clip c = new Clip();
            int open = head.lastIndexOf('('), close = head.lastIndexOf(')');
            if (open > 0 && close > open) { c.title = head.substring(0, open).trim(); c.author = person(head.substring(open + 1, close).trim()); }
            else c.title = head;
            c.type = meta.contains("nota") || meta.contains(" note") ? "Nota" : "Citazione";
            Matcher p = PAGE.matcher(meta); if (p.find()) c.page = Integer.parseInt(p.group(1));
            Matcher l = LOCATION.matcher(meta); if (l.find()) c.location = Integer.parseInt(l.group(1));
            c.text = body.toString().trim();
            clips.add(c);
        }
        return clips;
    }

    /** Kindle writes some authors as "Eco, Umberto". */
    private static String person(String value) {
        String[] parts = value.split(",\\s*", 2);
        return parts.length == 2 && !value.contains(";") && !parts[1].contains(" ") ? parts[1] + " " + parts[0] : value;
    }

    /** Adds the clips as notes of the book; returns how many were new (identical notes are not repeated). */
    static int addTo(Book b, List<Clip> clips, String today) {
        try {
            JSONArray notes = ReadingJournal.array(b, "notes"); Set<String> known = new HashSet<>();
            for (int i = 0; i < notes.length(); i++) { JSONObject n = notes.optJSONObject(i); if (n != null) known.add(n.optString("body").trim()); }
            int added = 0;
            for (Clip c : clips) {
                if (!known.add(c.text)) continue;
                notes.put(new JSONObject().put("type", c.type).put("body", c.text).put("page", c.page)
                    .put("tags", "Kindle" + (c.location > 0 ? ", posizione " + c.location : "")).put("date", today).put("at", System.currentTimeMillis()));
                added++;
            }
            ReadingJournal.put(b, "notes", notes);
            return added;
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
}
