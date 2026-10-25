package ftcsim;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import ftcsim.config.RobotConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real WebSocket protocol against a simulator running without a team repository:
 * hello describes the field and robot, sim.field switches a season option, and sim.clearBalls
 * puts the field back. A child JVM keeps the simulator's global state (stdout capture, hardware
 * bus, physics thread) out of the unit-test process.
 */
public class SimulatorProtocolTest {
    private static final int START_TIMEOUT_MS = 30_000;
    private static final int MESSAGE_TIMEOUT_MS = 10_000;
    // The SDK's RobotCore AAR bundles gson 2.8.0, which comes first on the classpath.
    private static final Gson GSON = new Gson();

    private Process simulator;
    private Path tempDir;

    @AfterEach
    public void stopSimulator() {
        if (simulator != null) {
            simulator.destroyForcibly();
            try {
                simulator.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (tempDir != null) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // best effort: the temp directory is disposable
            }
        }
    }

    @Test
    public void theWireProtocolDescribesAndResetsTheField() {
        try {
            runProtocolTest();
        } catch (Throwable t) {
            throw new AssertionError(t.getMessage() + "\n--- simulator log ---\n" + logTail(), t);
        }
    }

    private void runProtocolTest() throws Exception {
        Path home = Paths.get(System.getProperty("ftcsim.home", ".")).toAbsolutePath().normalize();
        tempDir = Files.createTempDirectory("ftcsim-protocol-");
        File config = new File(tempDir.toFile(), "Biobuzz.json");
        RobotConfig c = RobotConfig.defaults();
        c.name = "ProtocolTest";
        c.field = "BIOBUZZ";
        c.autoCreateDevices = false;
        c.save(config);

        int port = freePort();
        Path java = Paths.get(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        List<String> cmd = new ArrayList<>(List.of(
            java.toString(),
            "-Dftcsim.home=" + home,
            "-Djava.awt.headless=true",
            "-cp", System.getProperty("java.class.path"),
            "ftcsim.Main",
            "--host", "127.0.0.1",
            "--port", Integer.toString(port),
            "--no-browser",
            "--config", config.getAbsolutePath(),
            "--data", new File(tempDir.toFile(), "data").getAbsolutePath()));
        simulator = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(new File(tempDir.toFile(), "simulator.log"))
            .start();

        Ws ws = connectWhenReady(port);
        try (ws) {
            ws.send("{\"type\":\"hello\"}");
            JsonObject hello = ws.await(m -> "hello".equals(string(m, "type")), MESSAGE_TIMEOUT_MS);
            JsonObject field = hello.getAsJsonObject("field");
            assertEquals("BIOBUZZ", string(field, "name"));
            assertEquals(4, field.getAsJsonArray("clusters").size(), "four CELL clusters");
            assertEquals(16, field.getAsJsonArray("tags").size(), "four AprilTags per CELL");
            assertTrue(hello.getAsJsonObject("robot").get("widthIn").getAsDouble() > 0, "robot spec");
            JsonObject state = field.getAsJsonObject("state");
            assertEquals("audience", string(state, "redHive"), "red HIVE starts audience-up");
            assertEquals("scoring", string(state, "blueHive"));

            ws.send("{\"type\":\"sim.field\",\"key\":\"redHive\",\"value\":\"scoring\"}");
            JsonObject tipped = ws.await(m -> {
                if (!"field".equals(string(m, "type"))) return false;
                JsonObject f = m.getAsJsonObject("field");
                return "scoring".equals(string(f.getAsJsonObject("state"), "redHive"));
            }, MESSAGE_TIMEOUT_MS);
            assertNotNull(tipped.getAsJsonObject("field").get("cellGeometry"),
                "the tipped field still carries the CELL geometry");

            ws.send("{\"type\":\"sim.clearBalls\"}");
            ws.await(m -> {
                if (!"field".equals(string(m, "type"))) return false;
                JsonObject f = m.getAsJsonObject("field");
                return "audience".equals(string(f.getAsJsonObject("state"), "redHive"));
            }, MESSAGE_TIMEOUT_MS);
        }
    }

    private Ws connectWhenReady(int port) throws Exception {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (true) {
            if (!simulator.isAlive()) throw new AssertionError("simulator exited with " + simulator.exitValue());
            if (System.currentTimeMillis() > deadline) throw new AssertionError("simulator did not open its port");
            try {
                return Ws.connect(port, 500);
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    private String logTail() {
        if (tempDir == null) return "(simulator was never started)";
        try {
            String text = Files.readString(tempDir.resolve("simulator.log"), StandardCharsets.UTF_8);
            return text.length() > 4000 ? text.substring(text.length() - 4000) : text;
        } catch (IOException e) {
            return "(no simulator log: " + e + ")";
        }
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    /** Minimal RFC 6455 client: handshake, masked text frames out, JSON text frames in. */
    private static final class Ws implements Closeable {
        private final Socket socket;
        private final java.io.DataInputStream in;
        private final OutputStream out;

        private Ws(Socket socket, java.io.DataInputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        static Ws connect(int port, int timeoutMs) throws IOException {
            Socket socket = new Socket();
            try {
                return handshake(socket, port, timeoutMs);
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        }

        private static Ws handshake(Socket socket, int port, int timeoutMs) throws IOException {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(MESSAGE_TIMEOUT_MS);
            var in = new java.io.DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String key = Base64.getEncoder().encodeToString("ftcsim-protocol!!".getBytes(StandardCharsets.US_ASCII));
            String request = "GET /ws HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String status = readAsciiLine(in);
            if (!status.contains(" 101 ")) {
                socket.close();
                throw new IOException("websocket handshake failed: " + status);
            }
            String header;
            while (!(header = readAsciiLine(in)).isEmpty()) { /* skip response headers */ }
            return new Ws(socket, in, out);
        }

        private static String readAsciiLine(java.io.DataInputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            while (true) {
                int b = in.read();
                if (b < 0) throw new EOFException("connection closed during handshake");
                if (b == '\n') break;
                if (b != '\r') buf.write(b);
            }
            return buf.toString(StandardCharsets.ISO_8859_1);
        }

        void send(String json) throws IOException {
            sendFrame(0x1, json.getBytes(StandardCharsets.UTF_8));
        }

        private void sendFrame(int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x80 | opcode);
            int len = payload.length;
            if (len < 126) {
                frame.write(0x80 | len);
            } else if (len < 65536) {
                frame.write(0x80 | 126);
                frame.write(len >>> 8);
                frame.write(len);
            } else {
                frame.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) frame.write((int) (((long) len >>> (8 * i)) & 0xFF));
            }
            byte[] mask = { 0x37, 0x11, 0x5a, 0x02 };
            frame.write(mask, 0, 4);
            for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i % 4]);
            out.write(frame.toByteArray());
            out.flush();
        }

        JsonObject await(Predicate<JsonObject> predicate, long timeoutMs) throws IOException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new SocketTimeoutException("no matching message within " + timeoutMs + " ms");
                JsonObject message = readJson(left);
                if (predicate.test(message)) return message;
            }
        }

        private JsonObject readJson(long timeoutMs) throws IOException {
            socket.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, timeoutMs)));
            while (true) {
                int b0 = in.read();
                if (b0 < 0) throw new EOFException("server closed the websocket");
                int b1 = in.readUnsignedByte();
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = in.readUnsignedShort();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.readUnsignedByte();
                }
                byte[] mask = masked ? in.readNBytes(4) : null;
                byte[] payload = new byte[(int) len];
                in.readFully(payload);
                if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                if (opcode == 0x9) { sendFrame(0xA, payload); continue; }   // ping -> pong
                if (opcode == 0x8) throw new EOFException("server closed the websocket");
                if (opcode == 0x1) return GSON.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
                // this protocol only sends small text messages; ignore pong/continuation frames
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
