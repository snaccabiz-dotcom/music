package com.lazith.lovedsongs;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Tiny HTTP server that lives inside the app.
 *  - the app's own screen talks to it on 127.0.0.1 (needs the per-launch app key to change anything)
 *  - your PC talks to it over Wi-Fi (needs the 6-digit sync code)
 * Pure Java on purpose, so it can be tested on a normal computer.
 */
public class SongServer {
    public interface Assets { byte[] read(String name) throws IOException; }

    private final File metaDir, audioDir, tombFile;
    private final Assets assets;
    private final String code, key;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "song-http"); t.setDaemon(true); return t; });
    private ServerSocket server;
    private final Object lock = new Object();
    private final Map<String, Long> tombs = new LinkedHashMap<>();
    private int fails = 0;
    private long lockedUntil = 0;
    /** Goes up every time the library changes (a sync from the PC, an edit...), so the screen knows to reload. */
    public volatile long version = 0;

    public SongServer(File dir, Assets assets, String code, String key) {
        this.metaDir = new File(dir, "meta");
        this.audioDir = new File(dir, "audio");
        this.tombFile = new File(dir, "deleted.txt");
        this.assets = assets; this.code = code; this.key = key;
        metaDir.mkdirs(); audioDir.mkdirs();
        loadTombs();
    }

    /** Tries ports first..last, returns the one it got. */
    public int start(int first, int last) throws IOException {
        IOException err = null;
        for (int p = first; p <= last; p++) {
            try { server = new ServerSocket(p); break; } catch (IOException e) { err = e; }
        }
        if (server == null) throw err != null ? err : new IOException("no port");
        pool.execute(() -> {
            while (!server.isClosed()) {
                try { final Socket s = server.accept(); pool.execute(() -> handle(s)); }
                catch (IOException e) { if (server.isClosed()) return; }
            }
        });
        return server.getLocalPort();
    }

    public void stop() { try { if (server != null) server.close(); } catch (IOException ignored) {} pool.shutdownNow(); }

    // ---------------------------------------------------------------- request handling
    private static class Req {
        String method, path, query = ""; Map<String, String> h = new HashMap<>(); long len = 0; InputStream in; boolean local;
        String q(String k) {
            for (String kv : query.split("&")) {
                int i = kv.indexOf('='); if (i < 0) continue;
                if (kv.substring(0, i).equals(k)) { try { return URLDecoder.decode(kv.substring(i + 1), "UTF-8"); } catch (Exception e) { return ""; } }
            }
            return null;
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            Req r = parse(in);
            if (r == null) { s.close(); return; }
            r.in = in; r.local = s.getInetAddress().isLoopbackAddress();
            try { route(r, out); } catch (SocketException | SocketTimeoutException e) { /* client went away */ }
            out.flush();
        } catch (Exception e) {
            // ignore broken connections
        } finally { try { s.close(); } catch (IOException ignored) {} }
    }

    private Req parse(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) return null;
        String[] p = line.split(" ");
        if (p.length < 2) return null;
        Req r = new Req(); r.method = p[0];
        String target = p[1]; int qi = target.indexOf('?');
        if (qi >= 0) { r.query = target.substring(qi + 1); target = target.substring(0, qi); }
        r.path = target;
        int n = 0;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            if (++n > 100) return null;
            int c = line.indexOf(':');
            if (c > 0) r.h.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
        }
        String cl = r.h.get("content-length");
        if (cl != null) { try { r.len = Long.parseLong(cl); } catch (NumberFormatException e) { return null; } }
        return r;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(); int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 16384) throw new IOException("line too long");
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private void route(Req r, OutputStream out) throws IOException {
        Map<String, String> cors = new LinkedHashMap<>();
        if (!r.local) {
            cors.put("Access-Control-Allow-Origin", "*");
            cors.put("Access-Control-Allow-Headers", "X-Sync-Code, Content-Type");
            cors.put("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, OPTIONS");
            cors.put("Access-Control-Allow-Private-Network", "true");
            cors.put("Access-Control-Max-Age", "600");
        }
        if (r.method.equals("OPTIONS")) { send(out, r.local ? 403 : 204, "text/plain", new byte[0], cors); return; }

        // the sign-post: anyone on the Wi-Fi may ask "are you Loved Songs?"
        if (r.path.equals("/api/hello")) {
            send(out, 200, "application/json", "{\"app\":\"loved-songs\"}".getBytes(StandardCharsets.UTF_8), cors); return;
        }

        if (r.local) {
            String host = r.h.getOrDefault("host", "");
            if (!(host.startsWith("127.0.0.1") || host.startsWith("localhost"))) { send(out, 403, "text/plain", b("no"), cors); return; }
            boolean writes = !r.method.equals("GET") && !r.method.equals("HEAD");
            if (writes && !safeEq(key, r.h.getOrDefault("x-loved-key", ""))) { send(out, 403, "text/plain", b("no"), cors); return; }
            if (r.method.equals("GET") && (r.path.equals("/") || r.path.equals("/index.html"))) {
                byte[] page;
                try { page = assets.read("index.html"); } catch (IOException e) { send(out, 500, "text/plain", b("missing page"), cors); return; }
                Map<String, String> hd = new LinkedHashMap<>(); hd.put("Cache-Control", "no-store");
                send(out, 200, "text/html; charset=utf-8", page, hd); return;
            }
        } else {
            long now = System.currentTimeMillis();
            synchronized (lock) { if (now < lockedUntil) { send(out, 429, "text/plain", b("wait"), cors); return; } }
            String given = r.h.get("x-sync-code"); if (given == null) given = r.q("code");
            if (given == null || !safeEq(code, given)) {
                synchronized (lock) { if (++fails >= 8) { lockedUntil = now + 60000; fails = 0; } }
                send(out, 403, "text/plain", b("code"), cors); return;
            }
            synchronized (lock) { fails = 0; }
        }
        api(r, out, cors);
    }

    private void api(Req r, OutputStream out, Map<String, String> cors) throws IOException {
        String p = r.path;
        if (p.equals("/api/songs") && r.method.equals("GET")) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (String id : ids()) {
                File a = new File(audioDir, id);
                if (!a.isFile()) continue;
                String m = readText(new File(metaDir, id + ".json"));
                if (m == null) continue;
                if (!first) sb.append(','); first = false; sb.append(m);
            }
            sb.append(']');
            send(out, 200, "application/json", sb.toString().getBytes(StandardCharsets.UTF_8), cors); return;
        }
        if (p.equals("/api/sync/list") && r.method.equals("GET")) {
            StringBuilder sb = new StringBuilder("{\"songs\":[");
            boolean first = true;
            for (String id : ids()) {
                File a = new File(audioDir, id); if (!a.isFile()) continue;
                long[] ix = index(id);
                if (!first) sb.append(','); first = false;
                sb.append("{\"id\":\"").append(id).append("\",\"addedAt\":").append(ix[1]).append(",\"updatedAt\":").append(ix[0]).append(",\"size\":").append(a.length()).append('}');
            }
            sb.append("],\"deleted\":[");
            synchronized (lock) {
                first = true;
                for (Map.Entry<String, Long> e : tombs.entrySet()) {
                    if (!first) sb.append(','); first = false;
                    sb.append("{\"id\":\"").append(e.getKey()).append("\",\"at\":").append(e.getValue()).append('}');
                }
            }
            sb.append("]}");
            send(out, 200, "application/json", sb.toString().getBytes(StandardCharsets.UTF_8), cors); return;
        }
        if (p.startsWith("/api/song/") && p.endsWith("/meta") && r.method.equals("GET")) {
            String id = p.substring(10, p.length() - 5);
            String m = okId(id) ? readText(new File(metaDir, id + ".json")) : null;
            if (m == null) { send(out, 404, "text/plain", b("none"), cors); return; }
            send(out, 200, "application/json", m.getBytes(StandardCharsets.UTF_8), cors); return;
        }
        if (p.startsWith("/api/song/") && r.method.equals("DELETE")) {
            String id = p.substring(10);
            if (!okId(id)) { send(out, 400, "text/plain", b("id"), cors); return; }
            removeSong(id);
            send(out, 200, "text/plain", b("ok"), cors); return;
        }
        if (p.startsWith("/api/meta/") && r.method.equals("POST")) {
            String id = p.substring(10);
            if (!okId(id) || r.len > 8 * 1024 * 1024) { send(out, 400, "text/plain", b("bad"), cors); return; }
            byte[] body = readBody(r.in, r.len);
            long u = num(r.q("updatedAt")), a = num(r.q("addedAt"));
            String type = r.q("type"); if (type == null || !type.matches("[\\w./+-]{3,40}")) type = "audio/mpeg";
            saveMeta(id, body, u, a, type);
            send(out, 200, "text/plain", b("ok"), cors); return;
        }
        if (p.startsWith("/api/audio/")) {
            String id = p.substring(11);
            if (!okId(id)) { send(out, 400, "text/plain", b("id"), cors); return; }
            File f = new File(audioDir, id);
            if (r.method.equals("PUT")) {
                File tmp = new File(audioDir, id + ".part");
                long got = 0;
                try (OutputStream fo = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    while (got < r.len) {
                        int n = r.in.read(buf, 0, (int) Math.min(buf.length, r.len - got));
                        if (n < 0) break; fo.write(buf, 0, n); got += n;
                    }
                }
                if (got != r.len) { tmp.delete(); send(out, 400, "text/plain", b("short"), cors); return; }
                f.delete();
                if (!tmp.renameTo(f)) { tmp.delete(); send(out, 500, "text/plain", b("save"), cors); return; }
                send(out, 200, "text/plain", b("ok"), cors); return;
            }
            if (r.method.equals("GET") || r.method.equals("HEAD")) {
                if (!f.isFile()) { send(out, 404, "text/plain", b("none"), cors); return; }
                sendFile(r, out, f, index(id) , cors); return;
            }
        }
        send(out, 404, "text/plain", b("not found"), cors);
    }

    // ---------------------------------------------------------------- library (used by the app screen too)
    public File audioFile(String id) { return new File(audioDir, id); }

    /** Ids of every song that has both its info and its audio. */
    public List<String> songIds() {
        List<String> l = new ArrayList<>();
        for (String id : ids()) if (new File(audioDir, id).isFile()) l.add(id);
        return l;
    }

    public String metaText(String id) { return okId(id) ? readText(new File(metaDir, id + ".json")) : null; }

    public void saveMeta(String id, byte[] json, long updatedAt, long addedAt, String type) throws IOException {
        if (!okId(id)) throw new IOException("id");
        writeAtomic(new File(metaDir, id + ".json"), json);
        writeAtomic(new File(metaDir, id + ".idx"), (updatedAt + " " + addedAt + " " + type).getBytes(StandardCharsets.UTF_8));
        synchronized (lock) { if (tombs.remove(id) != null) saveTombs(); }
        version++;
    }

    public void removeSong(String id) {
        if (!okId(id)) return;
        new File(audioDir, id).delete(); new File(metaDir, id + ".json").delete(); new File(metaDir, id + ".idx").delete();
        synchronized (lock) { tombs.put(id, System.currentTimeMillis()); saveTombs(); }
        version++;
    }

    // ---------------------------------------------------------------- helpers
    private void sendFile(Req r, OutputStream out, File f, long[] ix, Map<String, String> cors) throws IOException {
        long size = f.length(), start = 0, end = size - 1; int status = 200;
        String range = r.h.get("range");
        if (range != null && range.startsWith("bytes=")) {
            String[] pr = range.substring(6).split(",")[0].trim().split("-", -1);
            try {
                if (pr[0].isEmpty()) { long n = Long.parseLong(pr[1]); start = Math.max(0, size - n); }
                else { start = Long.parseLong(pr[0]); if (pr.length > 1 && !pr[1].isEmpty()) end = Math.min(end, Long.parseLong(pr[1])); }
                if (start > end || start >= size) { Map<String, String> h = new LinkedHashMap<>(cors); h.put("Content-Range", "bytes */" + size); send(out, 416, "text/plain", new byte[0], h); return; }
                status = 206;
            } catch (NumberFormatException e) { start = 0; end = size - 1; }
        }
        long len = end - start + 1;
        Map<String, String> h = new LinkedHashMap<>(cors);
        h.put("Accept-Ranges", "bytes");
        if (status == 206) h.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
        header(out, status, typeOf(f.getName()), len, h);
        if (!r.method.equals("HEAD")) {
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(start); byte[] buf = new byte[65536]; long left = len;
                while (left > 0) { int n = raf.read(buf, 0, (int) Math.min(buf.length, left)); if (n < 0) break; out.write(buf, 0, n); left -= n; }
            }
        }
        out.flush();
    }

    private String typeOf(String id) {
        File ix = new File(metaDir, id + ".idx");
        String t = readText(ix);
        if (t != null) { String[] p = t.trim().split(" "); if (p.length >= 3) return p[2]; }
        return "audio/mpeg";
    }

    private long[] index(String id) {
        String t = readText(new File(metaDir, id + ".idx"));
        long u = 0, a = 0;
        if (t != null) { String[] p = t.trim().split(" "); try { u = Long.parseLong(p[0]); a = Long.parseLong(p[1]); } catch (Exception ignored) {} }
        return new long[]{u, a};
    }

    private List<String> ids() {
        List<String> l = new ArrayList<>();
        File[] fs = metaDir.listFiles();
        if (fs != null) for (File f : fs) { String n = f.getName(); if (n.endsWith(".json")) l.add(n.substring(0, n.length() - 5)); }
        return l;
    }

    private static boolean okId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{6,64}"); }
    private static long num(String s) { try { return s == null ? 0 : Long.parseLong(s); } catch (NumberFormatException e) { return 0; } }
    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static boolean safeEq(String a, String c) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), c.getBytes(StandardCharsets.UTF_8)); }

    private static byte[] readBody(InputStream in, long len) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(16, len));
        byte[] buf = new byte[16384]; long got = 0;
        while (got < len) { int n = in.read(buf, 0, (int) Math.min(buf.length, len - got)); if (n < 0) break; bo.write(buf, 0, n); got += n; }
        return bo.toByteArray();
    }

    private static String readText(File f) {
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) { return null; }
    }

    private static void writeAtomic(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(data); }
        f.delete();
        if (!tmp.renameTo(f)) throw new IOException("rename");
    }

    private void loadTombs() {
        String t = readText(tombFile); if (t == null) return;
        for (String line : t.split("\n")) {
            String[] p = line.split("\t");
            if (p.length == 2 && okId(p[0])) { try { tombs.put(p[0], Long.parseLong(p[1].trim())); } catch (NumberFormatException ignored) {} }
        }
    }
    private void saveTombs() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : tombs.entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        try { writeAtomic(tombFile, sb.toString().getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) {}
    }

    private static String reason(int s) {
        switch (s) { case 200: return "OK"; case 204: return "No Content"; case 206: return "Partial Content"; case 400: return "Bad Request";
            case 403: return "Forbidden"; case 404: return "Not Found"; case 416: return "Range Not Satisfiable"; case 429: return "Too Many Requests"; default: return "Error"; }
    }
    private void header(OutputStream out, int status, String type, long len, Map<String, String> extra) throws IOException {
        StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\nContent-Length: ").append(len).append("\r\nConnection: close\r\n");
        for (Map.Entry<String, String> e : extra.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }
    private void send(OutputStream out, int status, String type, byte[] body, Map<String, String> extra) throws IOException {
        header(out, status, type, body.length, extra); out.write(body); out.flush();
    }
}
