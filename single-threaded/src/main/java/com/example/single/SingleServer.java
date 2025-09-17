package com.example.single;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SingleServer {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            System.out.println("[single-threaded] Listening on port " + port);
            while (true) {
                try (Socket sock = server.accept()) {
                    sock.setSoTimeout(15000);
                    handleConnection(sock);
                } catch (SocketTimeoutException ignored) {
                } catch (IOException e) {
                    System.err.println("Connection error: " + e.getMessage());
                }
            }
        }
    }

    private static void handleConnection(Socket sock) throws IOException {
        InputStream in = sock.getInputStream();
        OutputStream out = sock.getOutputStream();
        // Process multiple requests per connection (simple keep-alive)
        while (true) {
            sock.setSoTimeout(15000);
            HttpRequest req = readRequest(in);
            if (req == null) break; // client closed or timeout
            byte[] body = route(req);
            writeResponse(out, 200, "OK", "text/plain", body, true);
            // loop to read next request on same connection
        }
    }

    private static HttpRequest readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int c, last4 = 0;
        while ((c = in.read()) != -1) {
            headerBuf.write(c);
            last4 = ((last4 << 8) | (c & 0xFF)) & 0xFFFFFFFF;
            if (last4 == 0x0D0A0D0A) break; // \r\n\r\n
            if (headerBuf.size() > 64 * 1024) throw new IOException("Header too large");
        }
        if (headerBuf.size() == 0 && c == -1) return null; // client closed
        String headers = headerBuf.toString(StandardCharsets.US_ASCII);
        String[] lines = headers.split("\r\n");
        if (lines.length == 0) return null;
        String[] parts = lines[0].split(" ");
        if (parts.length < 2) return null;
        String method = parts[0];
        String target = parts[1];
        Map<String, String> headerMap = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int idx = line.indexOf(':');
            if (idx > 0) headerMap.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
        }
        int contentLen = headerMap.containsKey("content-length") ? Integer.parseInt(headerMap.get("content-length")) : 0;
        byte[] body = new byte[contentLen];
        int read = 0;
        while (read < contentLen) {
            int r = in.read(body, read, contentLen - read);
            if (r == -1) throw new EOFException("Unexpected EOF in body");
            read += r;
        }
        return new HttpRequest(method, target, headerMap, body);
    }

    private static void writeResponse(OutputStream out, int code, String reason, String contentType, byte[] body, boolean keepAlive) throws IOException {
        String hdr =
                "HTTP/1.1 " + code + " " + reason + "\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n" +
                        "\r\n";
        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    // ---- Routing ----
    private static byte[] route(HttpRequest req) {
        String path = req.path;
        Map<String, String> q = req.query;

        try {
            if (path.equals("/echo")) {
                int size = parseIntOr(q.get("size"), 1024);
                return randomBody(size);
            } else if (path.equals("/cpu")) {
                int ms = parseIntOr(q.get("ms"), 5);
                busySpin(ms);
                return msg("cpu=" + ms + "ms");
            } else if (path.equals("/io-slow")) {
                int ms = parseIntOr(q.get("ms"), 20);
                Thread.sleep(ms);
                return msg("io=" + ms + "ms");
            } else if (path.equals("/mixed")) {
                int cpu = parseIntOr(q.get("cpuMs"), 5);
                int io = parseIntOr(q.get("ioMs"), 5);
                busySpin(cpu);
                Thread.sleep(io);
                return msg("mixed cpu=" + cpu + "ms io=" + io + "ms");
            } else {
                return msg("ok");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return msg("interrupted");
        }
    }

    private static void busySpin(int ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        long x = 0;
        while (System.nanoTime() < end) {
            x += 1; // prevent JIT optimizing away
        }
    }

    private static byte[] randomBody(int size) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) 'A');
        return b;
    }

    private static byte[] msg(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---- Request helper ----
    static class HttpRequest {
        final String method;
        final String path;
        final Map<String, String> headers;
        final byte[] body;
        final Map<String, String> query;

        HttpRequest(String method, String target, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.headers = headers;
            this.body = body;
            String p = target, qStr = "";
            int qi = target.indexOf('?');
            if (qi >= 0) { p = target.substring(0, qi); qStr = target.substring(qi + 1); }
            this.path = p;
            this.query = parseQuery(qStr);
        }

        private Map<String, String> parseQuery(String q) {
            Map<String, String> m = new HashMap<>();
            if (q == null || q.isEmpty()) return m;
            for (String kv : q.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) m.put(urlDecode(kv.substring(0, i)), urlDecode(kv.substring(i + 1)));
                else if (!kv.isEmpty()) m.put(urlDecode(kv), "");
            }
            return m;
        }

        private String urlDecode(String s) {
            try {
                return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
            } catch (Exception e) { return s; }
        }
    }

    private static int parseIntOr(String v, int def) {
        try { return v == null ? def : Integer.parseInt(v); } catch (Exception e) { return def; }
    }
}
