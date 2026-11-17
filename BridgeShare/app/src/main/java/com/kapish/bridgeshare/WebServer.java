package com.kapish.bridgeshare;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Tiny HTTP/1.1 server. The iPhone opens it in Safari; nothing is installed on the iPhone.
 * Every request must carry the random access key (?k=...) shown in the QR code.
 */
final class WebServer {

    interface Listener {
        void onTransfer(String message);
    }

    private static final int BUF = 64 * 1024;
    private static final String APP_FOLDER = "BridgeShare";

    private final Context ctx;
    private final String token;
    private final String deviceName;
    private final Listener listener;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;
    private int port;

    WebServer(Context ctx, String token, String deviceName, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.token = token;
        this.deviceName = deviceName;
        this.listener = listener;
    }

    int port() {
        return port;
    }

    void start() throws IOException {
        IOException last = null;
        for (int p : new int[]{8080, 8888, 8000, 0}) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(p));
                serverSocket = ss;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (serverSocket == null) throw last;
        port = serverSocket.getLocalPort();
        running = true;
        pool = Executors.newCachedThreadPool();
        Thread t = new Thread(this::acceptLoop, "bridgeshare-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (!running) return;
            } catch (Exception e) {
                // pool shut down
                return;
            }
        }
    }

    // ---------------------------------------------------------------- request handling

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(120_000);
            InputStream in = new BufferedInputStream(sock.getInputStream(), BUF);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), BUF);

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
                }
            }

            String path = target;
            Map<String, String> query = new HashMap<>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                parseQuery(target.substring(q + 1), query);
            }

            if (path.equals("/favicon.ico") || path.equals("/apple-touch-icon.png")
                    || path.equals("/apple-touch-icon-precomposed.png")) {
                sendText(out, 404, "text/plain", "not found");
                return;
            }
            if (!tokenOk(query.get("k"))) {
                sendText(out, 403, "text/html; charset=utf-8", FORBIDDEN_PAGE);
                return;
            }

            if (method.equals("GET") && path.equals("/")) {
                sendBytes(out, 200, "text/html; charset=utf-8", readAsset("index.html"));
            } else if (method.equals("GET") && path.equals("/api/info")) {
                sendText(out, 200, "application/json; charset=utf-8", infoJson());
            } else if (method.equals("GET") && path.startsWith("/d/")) {
                serveDownload(out, path, "1".equals(query.get("inline")));
            } else if (method.equals("GET") && path.startsWith("/zip")) {
                serveZip(out);
            } else if (method.equals("POST") && path.equals("/api/upload")) {
                receiveUpload(in, out, headers, query.get("name"));
            } else {
                sendText(out, 404, "text/plain", "not found");
            }
            out.flush();
        } catch (Exception ignored) {
            // client went away mid-transfer; nothing to do
        }
    }

    private boolean tokenOk(String k) {
        if (k == null) return false;
        return MessageDigest.isEqual(k.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private String infoJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("device", deviceName);
        JSONArray files = new JSONArray();
        for (FileStore.Item i : FileStore.get().outgoing()) {
            JSONObject f = new JSONObject();
            f.put("id", i.id);
            f.put("name", i.name);
            f.put("size", i.size);
            f.put("mime", i.mime);
            files.put(f);
        }
        o.put("files", files);
        return o.toString();
    }

    private void serveDownload(OutputStream out, String path, boolean inline) throws IOException {
        // /d/{id}/{name}
        String rest = path.substring(3);
        int slash = rest.indexOf('/');
        String id = slash >= 0 ? rest.substring(0, slash) : rest;
        FileStore.Item item = FileStore.get().findOutgoing(id);
        if (item == null) {
            sendText(out, 404, "text/plain", "This file is no longer shared.");
            return;
        }
        InputStream src;
        try {
            src = ctx.getContentResolver().openInputStream(item.uri);
        } catch (Exception e) {
            src = null;
        }
        if (src == null) {
            sendText(out, 410, "text/plain", "The Android phone can no longer read this file. Add it again.");
            return;
        }
        try (InputStream is = src) {
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: ").append(item.mime).append("\r\n");
            if (item.size >= 0) h.append("Content-Length: ").append(item.size).append("\r\n");
            h.append("Content-Disposition: ").append(inline ? "inline" : "attachment")
                    .append("; filename=\"").append(asciiName(item.name)).append("\"; filename*=UTF-8''")
                    .append(urlEncode(item.name)).append("\r\n");
            h.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.UTF_8));
            copy(is, out, -1);
            out.flush();
            if (!inline) listener.onTransfer("Sent to iPhone: " + item.name);
        }
    }

    private void serveZip(OutputStream out) throws IOException {
        String header = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n"
                + "Content-Disposition: attachment; filename=\"BridgeShare.zip\"\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(Deflater.BEST_SPEED);
        Set<String> used = new HashSet<>();
        int count = 0;
        for (FileStore.Item item : FileStore.get().outgoing()) {
            try (InputStream is = ctx.getContentResolver().openInputStream(item.uri)) {
                if (is == null) continue;
                String name = item.name;
                int n = 1;
                while (!used.add(name)) name = withSuffix(item.name, n++);
                zip.putNextEntry(new ZipEntry(name));
                copy(is, zip, -1);
                zip.closeEntry();
                count++;
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("closed")) throw e;
            }
        }
        zip.finish();
        out.flush();
        listener.onTransfer("Sent " + count + " file(s) to iPhone as a zip");
    }

    private void receiveUpload(InputStream in, OutputStream out, Map<String, String> headers, String rawName)
            throws IOException {
        long length;
        try {
            length = Long.parseLong(headers.get("content-length"));
        } catch (Exception e) {
            sendText(out, 411, "text/plain", "Content-Length required");
            return;
        }
        if ("100-continue".equalsIgnoreCase(headers.get("expect"))) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        String name = sanitize(rawName);
        String mime = headers.get("x-file-type");
        if (mime == null || mime.isEmpty() || mime.equals("application/octet-stream")) {
            mime = MimeUtil.fromName(name);
        }

        ContentResolver cr = ctx.getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri collection;
            String relPath;
            if (mime.startsWith("image/")) {
                collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                relPath = Environment.DIRECTORY_PICTURES + "/" + APP_FOLDER;
            } else if (mime.startsWith("video/")) {
                collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                relPath = Environment.DIRECTORY_MOVIES + "/" + APP_FOLDER;
            } else {
                collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                relPath = Environment.DIRECTORY_DOWNLOADS + "/" + APP_FOLDER;
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(collection, v);
            if (uri == null) {
                sendText(out, 500, "text/plain", "Android could not create the file");
                return;
            }
            boolean ok = false;
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new IOException("no output stream");
                copy(in, os, length);
                ok = true;
            } finally {
                if (!ok) {
                    try {
                        cr.delete(uri, null, null);
                    } catch (Exception ignored) {
                    }
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            String finalName = queryName(cr, uri, name);
            FileStore.get().addIncoming(uri, finalName, length, mime, relPath);
            listener.onTransfer("Received from iPhone: " + finalName);
        } else {
            File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = new File(ctx.getFilesDir(), "received");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, name);
            int n = 1;
            while (f.exists()) f = new File(dir, withSuffix(name, n++));
            boolean ok = false;
            try (OutputStream os = new FileOutputStream(f)) {
                copy(in, os, length);
                ok = true;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                if (!ok) f.delete();
            }
            FileStore.get().addIncoming(Uri.fromFile(f), f.getName(), length, mime, f.getParent());
            listener.onTransfer("Received from iPhone: " + f.getName());
        }
        sendText(out, 200, "application/json", "{\"ok\":true}");
    }

    // ---------------------------------------------------------------- helpers

    private static String queryName(ContentResolver cr, Uri uri, String fallback) {
        try (Cursor c = cr.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static void copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[BUF];
        long remaining = limit;
        while (limit < 0 || remaining > 0) {
            int want = limit < 0 ? buf.length : (int) Math.min(buf.length, remaining);
            int r = in.read(buf, 0, want);
            if (r < 0) {
                if (limit >= 0) throw new IOException("upload cut off");
                break;
            }
            out.write(buf, 0, r);
            remaining -= r;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16 * 1024) throw new IOException("header too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static void parseQuery(String qs, Map<String, String> out) {
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) {
            }
        }
    }

    static String sanitize(String raw) {
        String n = raw == null ? "" : raw;
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isEmpty()) n = "file_" + System.currentTimeMillis();
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
            n = n.substring(0, 120 - ext.length()) + ext;
        }
        return n;
    }

    private static String withSuffix(String name, int n) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) return name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
        return name + " (" + n + ")";
    }

    private static String asciiName(String name) {
        return name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "file";
        }
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream is = ctx.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            copy(is, b, -1);
            return b.toByteArray();
        }
    }

    private static void sendText(OutputStream out, int code, String type, String body) throws IOException {
        sendBytes(out, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status;
        switch (code) {
            case 200: status = "OK"; break;
            case 403: status = "Forbidden"; break;
            case 404: status = "Not Found"; break;
            case 410: status = "Gone"; break;
            case 411: status = "Length Required"; break;
            default: status = "Error";
        }
        String h = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static final String FORBIDDEN_PAGE = "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>BridgeShare</title></head>"
            + "<body style='font-family:-apple-system,sans-serif;padding:32px;text-align:center'>"
            + "<h2>Link expired</h2><p>Scan the QR code on the Android phone again.</p></body></html>";
}
