package ftcsim.web;

import ftcsim.log.SimLog;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * A small dependency-free HTTP/1.1 + WebSocket (RFC 6455) server that serves
 * the simulator UI (static resources) and a JSON message channel.
 */
public final class WebServer {
    public interface MessageHandler { void onMessage(Client client, String text); }
    public interface HttpHandler { Response handle(String method, String path, Map<String, String> query, byte[] body); }

    public static final class Response {
        public final int status; public final String contentType; public final byte[] body;
        public Response(int status, String contentType, byte[] body) { this.status = status; this.contentType = contentType; this.body = body; }
        public static Response text(int status, String s) { return new Response(status, "text/plain; charset=utf-8", s.getBytes(StandardCharsets.UTF_8)); }
        public static Response json(String s) { return new Response(200, "application/json; charset=utf-8", s.getBytes(StandardCharsets.UTF_8)); }
    }

    /**
     * A connected WebSocket client.
     *
     * The writer thread owns the socket write path, and the queue between it and send() is
     * deliberately lock-free. Earlier versions wrote the socket inline while holding a
     * monitor; a peer that stopped reading (a page closed without a clean disconnect) then
     * filled the TCP send buffer and blocked flush() forever, parking the single broadcast
     * thread inside that monitor — silently, with no exception and nothing in the log.
     * send() therefore never blocks and never takes a lock a wedged writer could be holding.
     */
    public final class Client {
        /** Drop a client that has fallen this far behind; it is not draining its socket. */
        private static final int DEAD_AFTER_DROPS = 8;
        private final Socket socket;
        private final OutputStream out;
        private final AtomicBoolean open = new AtomicBoolean(true);
        /** Pre-encoded frames waiting for the writer thread. LinkedBlockingQueue for timed poll. */
        private final LinkedBlockingQueue<byte[]> pending = new LinkedBlockingQueue<>();
        private final AtomicInteger pendingCount = new AtomicInteger();
        private final int maxPending = 64;
        private final AtomicLong droppedFrames = new AtomicLong();
        public final Map<String, Object> attributes = new ConcurrentHashMap<>();

        Client(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
            Thread w = new Thread(this::drainLoop, "ftcsim-ws-writer");
            w.setDaemon(true);
            w.start();
        }

        public boolean isOpen() { return open.get(); }

        /** A client that has been dropping frames for a while is not coming back. */
        boolean isDead() { return droppedFrames.get() >= DEAD_AFTER_DROPS; }

        /** Never blocks and never takes a lock: encode and hand off to the writer thread. */
        public void send(String text) {
            if (!open.get()) return;
            byte[] frame = encodeFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
            if (pendingCount.get() >= maxPending) {
                if (droppedFrames.incrementAndGet() == DEAD_AFTER_DROPS) close();
                return;
            }
            pending.offer(frame);
            pendingCount.incrementAndGet();
        }

        /** Enqueue a control frame (ping/pong/close) with the same path as data frames. */
        void sendPong(byte[] payload) {
            if (!open.get()) return;
            pending.offer(encodeFrame(0xA, payload));
            pendingCount.incrementAndGet();
        }

        private void drainLoop() {
            while (open.get() || !pending.isEmpty()) {
                byte[] frame;
                try {
                    frame = pending.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) { return; }
                if (frame == null) continue;
                pendingCount.decrementAndGet();
                try {
                    out.write(frame);
                    out.flush();
                } catch (IOException e) { close(); return; }
            }
        }

        /**
         * Closes the client. Exactly one thread runs the body; concurrent callers return at
         * once. The socket is closed first because that is what unblocks a writer stuck in
         * flush() on a dead peer — the cleanup must never wait on the writer's lock.
         */
        public void close() {
            if (!open.compareAndSet(true, false)) return;
            clients.remove(this);
            try { socket.close(); } catch (IOException ignored) {}
        }

        /** Builds a complete WebSocket frame (header + payload) ready to write. */
        private byte[] encodeFrame(int opcode, byte[] payload) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(payload.length + 10);
            bos.write(0x80 | opcode);
            int len = payload.length;
            if (len < 126) bos.write(len);
            else if (len < 65536) { bos.write(126); bos.write(len >> 8); bos.write(len & 0xFF); }
            else { bos.write(127); for (int i = 7; i >= 0; i--) bos.write((int) (((long) len >> (8 * i)) & 0xFF)); }
            bos.write(payload, 0, payload.length);
            return bos.toByteArray();
        }
    }

    private final int port;
    private final String resourceRoot;
    private final MessageHandler messageHandler;
    private final Map<String, HttpHandler> apiHandlers = new LinkedHashMap<>();
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "ftcsim-http"); t.setDaemon(true); return t; });
    private ServerSocket serverSocket;
    private volatile boolean running;
    private final File overrideDir;

    public WebServer(int port, String resourceRoot, File overrideDir, MessageHandler messageHandler) {
        this.port = port; this.resourceRoot = resourceRoot; this.overrideDir = overrideDir; this.messageHandler = messageHandler;
    }

    public void addApi(String pathPrefix, HttpHandler h) { apiHandlers.put(pathPrefix, h); }
    public List<Client> clients() { return clients; }
    public int port() { return serverSocket == null ? port : serverSocket.getLocalPort(); }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    s.setTcpNoDelay(true);
                    pool.execute(() -> handle(s));
                } catch (IOException e) { if (running) SimLog.w("WebServer", "accept failed: " + e); }
            }
        }, "ftcsim-http-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        for (Client c : clients) c.close();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    public void broadcast(String text) { for (Client c : clients) c.send(text); }

    // ------------------------------------------------------------------ HTTP
    private void handle(Socket socket) {
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            socket.setSoTimeout(15000);
            while (true) {
                String requestLine = readLine(in);
                if (requestLine == null || requestLine.isEmpty()) break;
                Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int i = line.indexOf(':');
                    if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
                }
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) break;
                String method = parts[0];
                String fullPath = parts[1];
                String path = fullPath, queryString = "";
                int q = fullPath.indexOf('?');
                if (q >= 0) { path = fullPath.substring(0, q); queryString = fullPath.substring(q + 1); }
                int contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                byte[] body = new byte[contentLength];
                int read = 0;
                while (read < contentLength) { int n = in.read(body, read, contentLength - read); if (n < 0) break; read += n; }

                if ("websocket".equalsIgnoreCase(headers.getOrDefault("upgrade", "")) && headers.containsKey("sec-websocket-key")) {
                    socket.setSoTimeout(0);
                    handshake(out, headers.get("sec-websocket-key"));
                    Client client = new Client(socket, out);
                    clients.add(client);
                    readFrames(in, client);
                    return;
                }
                Response r;
                try { r = route(method, path, parseQuery(queryString), body); }
                catch (RuntimeException e) { SimLog.e("WebServer", "request failed: " + path, e); r = Response.text(500, "Internal error: " + e); }
                boolean keepAlive = !"close".equalsIgnoreCase(headers.getOrDefault("connection", "keep-alive"));
                writeResponse(out, r, keepAlive);
                if (!keepAlive) break;
            }
        } catch (IOException ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private Response route(String method, String path, Map<String, String> query, byte[] body) {
        for (Map.Entry<String, HttpHandler> e : apiHandlers.entrySet()) {
            if (path.startsWith(e.getKey())) {
                Response r = e.getValue().handle(method, path, query, body);
                if (r != null) return r;
            }
        }
        // The 3D view is the front door; the 2D view lives under /2d/. Both directories ship their
        // own index.html, so a bare directory request resolves the same way a file request does.
        if ("/".equals(path)) path = "/3d/index.html";
        else if ("/2d".equals(path) || "/2d/".equals(path)) path = "/2d/index.html";
        else if ("/3d".equals(path) || "/3d/".equals(path)) path = "/3d/index.html";
        if (path.contains("..")) return Response.text(403, "forbidden");
        byte[] data = null;
        if (overrideDir != null) {
            File f = new File(overrideDir, path.substring(1));
            if (f.isFile()) { try { data = java.nio.file.Files.readAllBytes(f.toPath()); } catch (IOException ignored) {} }
        }
        if (data == null) {
            try (InputStream is = WebServer.class.getResourceAsStream(resourceRoot + path)) {
                if (is != null) data = is.readAllBytes();
            } catch (IOException ignored) {}
        }
        if (data == null) return Response.text(404, "not found: " + path);
        return new Response(200, contentType(path), data);
    }

    private static String contentType(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private static Map<String, String> parseQuery(String qs) {
        Map<String, String> m = new HashMap<>();
        if (qs == null || qs.isEmpty()) return m;
        for (String kv : qs.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i), v = i < 0 ? "" : kv.substring(i + 1);
            m.put(java.net.URLDecoder.decode(k, StandardCharsets.UTF_8), java.net.URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void writeResponse(OutputStream out, Response r, boolean keepAlive) throws IOException {
        String reason = r.status == 200 ? "OK" : r.status == 404 ? "Not Found" : r.status == 400 ? "Bad Request" : r.status == 403 ? "Forbidden" : "Error";
        String head = "HTTP/1.1 " + r.status + " " + reason + "\r\n" +
            "Content-Type: " + r.contentType + "\r\n" +
            "Content-Length: " + r.body.length + "\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(r.body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 16384) throw new IOException("header line too long");
        }
        if (c < 0 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------ WebSocket
    private static void handshake(OutputStream out, String key) throws IOException {
        String accept;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            accept = Base64.getEncoder().encodeToString(sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
        String head = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void readFrames(InputStream in, Client client) {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageOpcode = 0;
        try {
            while (client.isOpen()) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.read();
                if (b1 < 0) break;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) len = ((long) in.read() << 8) | in.read();
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
                if (len > 16 * 1024 * 1024) break;
                byte[] mask = new byte[4];
                if (masked) { for (int i = 0; i < 4; i++) mask[i] = (byte) in.read(); }
                byte[] payload = new byte[(int) len];
                int read = 0;
                while (read < len) { int n = in.read(payload, read, (int) len - read); if (n < 0) throw new EOFException(); read += n; }
                if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                switch (opcode) {
                    case 0x8: client.close(); return;
                    // Pong replies go through the writer thread like any other frame; writing
                    // directly here would block the reader on a slow peer's socket.
                    case 0x9: client.sendPong(payload); break;
                    case 0xA: break;
                    case 0x0: case 0x1: case 0x2:
                        if (opcode != 0) messageOpcode = opcode;
                        message.write(payload);
                        if (fin) {
                            if (messageOpcode == 0x1) {
                                String text = message.toString(StandardCharsets.UTF_8);
                                try { messageHandler.onMessage(client, text); } catch (RuntimeException e) { SimLog.e("WebServer", "message handler failed", e); }
                            }
                            message.reset();
                        }
                        break;
                    default: break;
                }
            }
        } catch (IOException ignored) {
        } finally {
            client.close();
        }
    }

    private static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | opcode);
        int len = payload.length;
        if (len < 126) out.write(len);
        else if (len < 65536) { out.write(126); out.write(len >> 8); out.write(len & 0xFF); }
        else { out.write(127); for (int i = 7; i >= 0; i--) out.write((int) ((long) len >> (8 * i)) & 0xFF); }
        out.write(payload);
        out.flush();
    }
}
