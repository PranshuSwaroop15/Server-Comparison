package com.example.loop;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class LoopServer {

    static class Conn {
        final SocketChannel ch;
        final ByteBuffer readBuf = ByteBuffer.allocateDirect(64 * 1024);
        ByteBuffer writeBuf; // response when ready
        final StringBuilder header = new StringBuilder();
        boolean headerDone = false;
        String path = "/";
        Map<String, String> query = Map.of();

        Conn(SocketChannel ch) { this.ch = ch; }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8083;
        ExecutorService cpuPool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        ScheduledExecutorService timer = Executors.newScheduledThreadPool(1);
        Selector selector = Selector.open();
        ServerSocketChannel server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.bind(new InetSocketAddress(port), 1024);
        server.register(selector, SelectionKey.OP_ACCEPT);
        System.out.println("[threadloop] Listening on port " + port);

        try {
            while (true) {
                selector.select(1000);
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next(); it.remove();
                    try {
                        if (key.isAcceptable()) {
                            SocketChannel ch = server.accept();
                            ch.configureBlocking(false);
                            ch.socket().setTcpNoDelay(true);
                            Conn conn = new Conn(ch);
                            ch.register(selector, SelectionKey.OP_READ, conn);
                        } else if (key.isReadable()) {
                            Conn conn = (Conn) key.attachment();
                            if (!readAndMaybeParse(conn)) {
                                closeQuiet(key); continue;
                            }
                            if (conn.headerDone && conn.writeBuf == null) {
                                // dispatch
                                dispatch(conn, key, cpuPool, timer);
                            }
                        } else if (key.isWritable()) {
                            Conn conn = (Conn) key.attachment();
                            if (conn.writeBuf != null) {
                                conn.ch.write(conn.writeBuf);
                                if (!conn.writeBuf.hasRemaining()) {
                                    conn.writeBuf = null;
                                    // keep-alive: switch back to read for another request
                                    key.interestOps(SelectionKey.OP_READ);
                                    resetConn(conn);
                                }
                            }
                        }
                    } catch (CancelledKeyException | IOException ex) {
                        closeQuiet(key);
                    }
                }
            }
        } finally {
            server.close();
            selector.close();
            cpuPool.shutdown();
            timer.shutdown();
        }
    }

    private static void resetConn(Conn c) {
        c.header.setLength(0);
        c.headerDone = false;
        c.path = "/";
        c.query = Map.of();
        c.readBuf.clear();
    }

    private static boolean readAndMaybeParse(Conn c) throws IOException {
        int r = c.ch.read(c.readBuf);
        if (r == -1) return false;
        c.readBuf.flip();
        while (c.readBuf.hasRemaining() && !c.headerDone) {
            char ch = (char) (c.readBuf.get() & 0xFF);
            c.header.append(ch);
            int len = c.header.length();
            if (len >= 4 && c.header.charAt(len - 4) == '\r' && c.header.charAt(len - 3) == '\n' &&
                    c.header.charAt(len - 2) == '\r' && c.header.charAt(len - 1) == '\n') {
                c.headerDone = true;
                parseRequestLine(c);
            }
            if (c.header.length() > 64 * 1024) throw new IOException("Header too large");
        }
        c.readBuf.compact();
        return true;
    }

    private static void parseRequestLine(Conn c) {
        String[] lines = c.header.toString().split("\r\n");
        if (lines.length == 0) return;
        String[] parts = lines[0].split(" ");
        if (parts.length < 2) return;
        String target = parts[1];
        int qi = target.indexOf('?');
        c.path = qi >= 0 ? target.substring(0, qi) : target;
        c.query = parseQuery(qi >= 0 ? target.substring(qi + 1) : "");
    }

    private static Map<String, String> parseQuery(String q) {
        if (q.isEmpty()) return Map.of();
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(dec(kv.substring(0, i)), dec(kv.substring(i + 1)));
            else if (!kv.isEmpty()) m.put(dec(kv), "");
        }
        return m;
    }

    private static String dec(String s) {
        try { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private static void dispatch(Conn conn, SelectionKey key, ExecutorService cpuPool, ScheduledExecutorService timer) {
        // Nonblocking: offload CPU to pool; use timer for "IO" delay
        String p = conn.path; Map<String,String> q = conn.query;
        if ("/echo".equals(p)) {
            int size = parseIntOr(q.get("size"), 1024);
            byte[] body = randomBody(size);
            queueResponse(conn, key, 200, "OK", "text/plain", body);
        } else if ("/cpu".equals(p)) {
            int ms = parseIntOr(q.get("ms"), 5);
            cpuPool.execute(() -> {
                busySpin(ms);
                queueResponse(conn, key, 200, "OK", "text/plain", msgBytes("cpu=" + ms + "ms"));
            });
        } else if ("/io-slow".equals(p)) {
            int ms = parseIntOr(q.get("ms"), 20);
            timer.schedule(() ->
                    queueResponse(conn, key, 200, "OK", "text/plain", msgBytes("io=" + ms + "ms")), ms, TimeUnit.MILLISECONDS);
        } else if ("/mixed".equals(p)) {
            int cpu = parseIntOr(q.get("cpuMs"), 5);
            int io = parseIntOr(q.get("ioMs"), 5);
            cpuPool.execute(() -> {
                busySpin(cpu);
                timer.schedule(() ->
                        queueResponse(conn, key, 200, "OK", "text/plain",
                                msgBytes("mixed cpu=" + cpu + "ms io=" + io + "ms")), io, TimeUnit.MILLISECONDS);
            });
        } else {
            queueResponse(conn, key, 200, "OK", "text/plain", msgBytes("ok"));
        }
    }

    private static void queueResponse(Conn conn, SelectionKey key, int code, String reason, String ctype, byte[] body) {
        String hdr = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                "Content-Type: " + ctype + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: keep-alive\r\n\r\n";
        byte[] h = hdr.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(h.length + body.length);
        buf.put(h).put(body).flip();
        conn.writeBuf = buf;
        key.interestOps(SelectionKey.OP_WRITE);
        key.selector().wakeup();
    }

    private static void busySpin(int ms) {
        long end = System.nanoTime() + ms * 1_000_000L; long x = 0;
        while (System.nanoTime() < end) x++;
    }

    private static byte[] randomBody(int size) {
        byte[] b = new byte[size]; Arrays.fill(b, (byte) 'A'); return b;
    }

    private static byte[] msgBytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static void closeQuiet(SelectionKey key) {
        try { key.channel().close(); } catch (IOException ignored) {}
        try { key.cancel(); } catch (Exception ignored) {}
    }

    private static int parseIntOr(String v, int def) { try { return v == null ? def : Integer.parseInt(v); } catch (Exception e) { return def; } }
}
