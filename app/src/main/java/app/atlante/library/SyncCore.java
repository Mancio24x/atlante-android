package app.atlante.library;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared pieces of the Sync section: reading snapshots, the merge rule and a small HTTP client. */
final class SyncCore {
    /** What a service can know about a reading: state, page, chapter and rating (fields it lacks stay empty). */
    static final class Snapshot {
        String status = Book.TO_READ, chapter = "";
        int page;
        double rating;
        Snapshot() { }
        Snapshot(String status, int page, String chapter, double rating) {
            this.status = Book.normalizeStatus(status); this.page = Math.max(0, page);
            this.chapter = chapter == null ? "" : chapter.trim(); this.rating = Math.round(Math.max(0, Math.min(5, rating)) * 2) / 2.0;
        }
        /** The page only matters while reading or paused; a finished or unstarted book has no "current page". */
        int relevantPage() { return status.equals(Book.READING) || status.equals(Book.PAUSED) ? page : 0; }
        boolean empty() { return status.equals(Book.TO_READ) && page == 0 && chapter.isEmpty() && rating == 0; }
        boolean same(Snapshot o, boolean chapters, boolean ratings) {
            return o != null && status.equals(o.status) && relevantPage() == o.relevantPage()
                && (!chapters || chapter.equals(o.chapter)) && (!ratings || rating == o.rating);
        }
        JSONObject json() throws JSONException { return new JSONObject().put("status", status).put("page", page).put("chapter", chapter).put("rating", rating); }
        static Snapshot from(JSONObject j) {
            return j == null ? null : new Snapshot(j.optString("status", Book.TO_READ), j.optInt("page"), j.optString("chapter"), j.optDouble("rating", 0));
        }
        static Snapshot of(Book b) {
            String chapter = b.journal.optString("chapter");
            return new Snapshot(b.status, b.currentPage, chapter, ReadingJournal.rating(b));
        }
    }

    enum Action { NONE, PUSH, PULL }

    /**
     * Book by book: whichever side changed since the last sync wins; if both changed the phone wins.
     * On the first link (no synced snapshot) the more advanced reading wins.
     */
    static Action decide(Snapshot local, Snapshot remote, Snapshot synced, boolean chapters, boolean ratings) {
        if (local.same(remote, chapters, ratings)) return Action.NONE;
        if (synced == null) {
            if (remote.empty()) return Action.PUSH;
            if (local.empty()) return Action.PULL;
            return rank(local) >= rank(remote) ? Action.PUSH : Action.PULL;
        }
        boolean localChanged = !local.same(synced, chapters, ratings), remoteChanged = !remote.same(synced, chapters, ratings);
        if (localChanged) return Action.PUSH;
        return remoteChanged ? Action.PULL : Action.NONE;
    }

    private static long rank(Snapshot s) {
        int state = s.status.equals(Book.READ) ? 3 : s.status.equals(Book.READING) || s.status.equals(Book.PAUSED) ? 2 : 1;
        return state * 1_000_000L + s.relevantPage();
    }

    /** Applies a remote snapshot to a book, with the usual side effects of a state change. */
    static void apply(Book b, Snapshot s, boolean chapters, boolean ratings, String today) {
        b.status = s.status;
        if (s.relevantPage() > 0) { b.currentPage = b.totalPages > 0 ? Math.min(s.page, b.totalPages) : s.page; if (b.totalPages > 0) b.percent = Math.round(100f * b.currentPage / b.totalPages); }
        if (chapters && !s.chapter.isEmpty()) ReadingJournal.put(b, "chapter", s.chapter);
        if (ratings && s.rating > 0) { ReadingJournal.put(b, "rating", s.rating); b.rating = (int) Math.round(s.rating); }
        Book.applyStatusEffects(b, today);
    }

    /** Remembers what both sides agreed on, under journal.sync.&lt;service&gt;. */
    static void remember(Book b, String service, JSONObject link, Snapshot agreed) {
        try {
            JSONObject sync = b.journal.optJSONObject("sync");
            if (sync == null) sync = new JSONObject();
            JSONObject entry = new JSONObject(link.toString()).put("synced", agreed.json()).put("at", System.currentTimeMillis());
            sync.put(service, entry); ReadingJournal.put(b, "sync", sync);
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
    static JSONObject link(Book b, String service) {
        JSONObject sync = b.journal.optJSONObject("sync");
        return sync == null ? null : sync.optJSONObject(service);
    }

    // ---- HTTP -------------------------------------------------------------------------------------

    static final class Response { int status; String body; Map<String, List<String>> headers; }

    /** GET/POST with optional Basic or Digest credentials (Calibre) or extra headers (Hardcover). */
    static Response http(String method, String address, String body, Map<String, String> headers, String user, String password, int limit) throws IOException {
        // Ask first without credentials: the server's challenge says whether it wants Basic or Digest
        // (Calibre in Digest mode rejects an unsolicited Basic header with 400).
        Response r = send(method, address, body, headers, null, limit);
        if (r.status == 401 && user != null && !user.isEmpty()) r = send(method, address, body, headers, answer(header(r, "WWW-Authenticate"), method, address, user, password), limit);
        return r;
    }

    private static Response send(String method, String address, String body, Map<String, String> headers, String authorization, int limit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(30000); c.setRequestMethod(method); c.setInstanceFollowRedirects(headers == null || !headers.containsKey("x-auth-key"));
        c.setRequestProperty("User-Agent", "AtlanteLibraryAndroid (https://github.com/Mancio24x/atlante-android)");
        c.setRequestProperty("Accept", "application/json");
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (authorization != null) c.setRequestProperty("Authorization", authorization);
        try {
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            Response r = new Response(); r.status = c.getResponseCode(); r.headers = c.getHeaderFields();
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? "" : new String(EbookParser.read(in, limit), StandardCharsets.UTF_8);
            return r;
        } finally { c.disconnect(); }
    }

    /** Authorization header answering a WWW-Authenticate challenge. */
    static String answer(String challenge, String method, String address, String user, String password) throws IOException {
        return challenge != null && challenge.regionMatches(true, 0, "Digest", 0, 6) ? digest(challenge, method, new URL(address).getFile(), user, password) : basic(user, password);
    }

    private static String header(Response r, String name) {
        if (r.headers == null) return null;
        for (Map.Entry<String, List<String>> e : r.headers.entrySet())
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
        return null;
    }

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** RFC 7616 Digest with MD5 and qop=auth, as used by Calibre's content server. */
    static String digest(String challenge, String method, String uri, String user, String password) {
        Map<String, String> p = new HashMap<>();
        Matcher m = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]*))").matcher(challenge);
        while (m.find()) p.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2) != null ? m.group(2) : m.group(3));
        String realm = p.getOrDefault("realm", ""), nonce = p.getOrDefault("nonce", ""), opaque = p.get("opaque");
        boolean qop = p.getOrDefault("qop", "").contains("auth");
        byte[] random = new byte[8]; new SecureRandom().nextBytes(random);
        String cnonce = hex(random), nc = "00000001";
        String ha1 = md5(user + ":" + realm + ":" + password), ha2 = md5(method + ":" + uri);
        String response = qop ? md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2) : md5(ha1 + ":" + nonce + ":" + ha2);
        return "Digest username=\"" + user + "\", realm=\"" + realm + "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", response=\"" + response + "\""
            + (qop ? ", qop=auth, nc=" + nc + ", cnonce=\"" + cnonce + "\"" : "") + (opaque != null ? ", opaque=\"" + opaque + "\"" : "") + ", algorithm=MD5";
    }

    static String md5(String value) {
        try { return hex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255)); return s.toString(); }
}
