package app.atlante.library.online;

import java.util.*;

/**
 * A book record found by one catalog. Edition fields (title, publisher, ISBN, pages, cover, translator)
 * describe one specific edition; work fields (original title, first publication, subjects, series)
 * describe the work and can be shared by every edition. Unknown values stay empty.
 */
public final class Candidate {
    public final String source;
    /** True when the record was found through the exact ISBN of the edition being completed. */
    public boolean byIsbn;
    // Edition
    public String title = "", subtitle = "", publisher = "", collection = "", editionDate = "", year = "", language = "", format = "";
    public final List<String> authors = new ArrayList<>(), translators = new ArrayList<>(), editors = new ArrayList<>(), illustrators = new ArrayList<>();
    public final Set<String> isbns = new LinkedHashSet<>();
    public int pages;
    public final List<Cover> covers = new ArrayList<>();
    // Work
    public String originalTitle = "", firstPublished = "", series = "", dewey = "";
    public double number;
    public final List<String> subjects = new ArrayList<>();
    public final List<Description> descriptions = new ArrayList<>();
    // Identifiers and links
    public final Map<String, String> ids = new LinkedHashMap<>();
    public String url = "";

    public Candidate(String source) { this.source = source; }

    public static final class Cover {
        public final String url, source; public final int width, height;
        public Cover(String url, String source, int width, int height) { this.url = url; this.source = source; this.width = width; this.height = height; }
    }

    public static final class Description {
        public final String text, language, source;
        public Description(String text, String language, String source) { this.text = text; this.language = language; this.source = source; }
    }

    public String authorLine() { return String.join(" & ", authors); }
}
