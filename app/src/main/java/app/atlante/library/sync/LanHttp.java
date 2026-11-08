package app.atlante.library.sync;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plain HTTP to a server on the home network (Calibre's content server, a KOReader sync server on the LAN):
 * Android blocks clear-text HttpURLConnection for apps targeting API 28+, so these requests go over a socket.
 * Only private, link-local or loopback addresses: everything on the internet stays HTTPS.
 */
final class LanHttp {
    private LanHttp() { }

    static final class Raw { int status; Map<String, List<String>> headers; byte[] body; }

    /** An http:// address whose host is on this network. */
    static boolean applies(String address) {
        try {
            URL u = new URL(address);
            if (!u.getProtocol().equalsIgnoreCase("http")) return false;
            InetAddress a = InetAddress.getByName(u.getHost());
            return a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isLoopbackAddress();
        } catch (IOException | RuntimeException e) { return false; }
    }

    static Raw exchange(String method, String address, byte[] body, Map<String, String> headers, int limit) throws IOException {
        URL u = new URL(address);
        int port = u.getPort() > 0 ? u.getPort() : 80;
        String path = u.getFile().isEmpty() ? "/" : u.getFile();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(u.getHost(), port), 12000); socket.setSoTimeout(30000);
            StringBuilder head = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(u.getHost()).append(u.getPort() > 0 ? ":" + u.getPort() : "").append("\r\nConnection: close\r\n");
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            if (body != null) head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.UTF_8)); if (body != null) out.write(body); out.flush();
            InputStream in = new BufferedInputStream(socket.getInputStream());
            Raw r = new Raw();
            String status = line(in);
            String[] parts = status.split(" ");
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("Risposta HTTP non valida");
            r.status = Integer.parseInt(parts[1]);
            r.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String l = line(in); !l.isEmpty(); l = line(in)) {
                int c = l.indexOf(':'); if (c <= 0) continue;
                r.headers.computeIfAbsent(l.substring(0, c).trim(), k -> new ArrayList<>()).add(l.substring(c + 1).trim());
            }
            String te = first(r.headers, "Transfer-Encoding"), cl = first(r.headers, "Content-Length");
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
                while (true) {
                    String size = line(in); int semi = size.indexOf(';'); if (semi >= 0) size = size.substring(0, semi);
                    int n = Integer.parseInt(size.trim(), 16); if (n == 0) break;
                    copy(in, data, n, limit); line(in);
                }
            } else if (cl != null) copy(in, data, Long.parseLong(cl.trim()), limit);
            else copy(in, data, Long.MAX_VALUE, limit);
            r.body = data.toByteArray();
            return r;
        }
    }

    private static String first(Map<String, List<String>> h, String name) { List<String> v = h.get(name); return v == null || v.isEmpty() ? null : v.get(0); }

    private static void copy(InputStream in, ByteArrayOutputStream out, long n, int limit) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n)); if (r < 0) { if (n == Long.MAX_VALUE) return; throw new IOException("Risposta interrotta"); }
            out.write(buf, 0, r); if (n != Long.MAX_VALUE) n -= r;
            if (out.size() > limit) throw new IOException("Risposta troppo grande");
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c = in.read(); c >= 0 && c != '\n'; c = in.read()) if (c != '\r') b.write(c);
        return b.toString("UTF-8");
    }
}
