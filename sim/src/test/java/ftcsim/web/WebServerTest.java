package ftcsim.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The server can drive the robot, so the request gate is part of the security boundary:
 * a loopback bind must still refuse foreign Host headers (DNS rebinding) and cross-origin
 * WebSocket upgrades (which CORS does not cover).
 */
public class WebServerTest {
    private WebServer server;

    @BeforeEach
    public void startServer() throws IOException {
        server = new WebServer(WebServer.DEFAULT_HOST, 0, "/web", null, (client, text) -> {});
        server.start();
    }

    @AfterEach
    public void stopServer() {
        if (server != null) server.stop();
    }

    private String request(String... lines) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write((String.join("\r\n", lines) + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            return in.readLine();
        }
    }

    private String[] websocket(String host, String origin) {
        return new String[] {
            "GET /ws HTTP/1.1",
            "Host: " + host,
            "Upgrade: websocket",
            "Connection: Upgrade",
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
            "Sec-WebSocket-Version: 13",
            "Origin: " + origin
        };
    }

    @Test
    public void servesTheUiThroughTheLoopbackAddress() throws IOException {
        String status = request("GET / HTTP/1.1", "Host: 127.0.0.1:" + server.port(), "Connection: close");
        assertEquals("HTTP/1.1 303 See Other", status);
    }

    @Test
    public void acceptsLocalhostInTheHostHeader() throws IOException {
        String status = request("GET / HTTP/1.1", "Host: localhost:" + server.port(), "Connection: close");
        assertEquals("HTTP/1.1 303 See Other", status);
    }

    @Test
    public void rejectsAForeignHostHeader() throws IOException {
        String status = request("GET / HTTP/1.1", "Host: evil.example:" + server.port(), "Connection: close");
        assertEquals("HTTP/1.1 403 Forbidden", status);
    }

    @Test
    public void rejectsAReboundWebSocketUpgradeBeforeTheHandshake() throws IOException {
        String status = request(websocket("evil.example:" + server.port(), "http://evil.example"));
        assertEquals("HTTP/1.1 403 Forbidden", status);
    }

    @Test
    public void rejectsAWebSocketUpgradeFromAnotherOrigin() throws IOException {
        String status = request(websocket("127.0.0.1:" + server.port(), "http://evil.example"));
        assertEquals("HTTP/1.1 403 Forbidden", status);
    }

    @Test
    public void acceptsASameOriginWebSocketUpgrade() throws IOException {
        String status = request(websocket("127.0.0.1:" + server.port(), "http://127.0.0.1:" + server.port()));
        assertEquals("HTTP/1.1 101 Switching Protocols", status);
    }

    @Test
    public void acceptsAWebSocketClientWithoutAnOriginHeader() throws IOException {
        String status = request(
            "GET /ws HTTP/1.1",
            "Host: localhost:" + server.port(),
            "Upgrade: websocket",
            "Connection: Upgrade",
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
            "Sec-WebSocket-Version: 13");
        assertEquals("HTTP/1.1 101 Switching Protocols", status);
    }

    @Test
    public void rejectsAnApiRequestFromAnotherOrigin() throws IOException {
        String status = request("GET /api/opmodes HTTP/1.1", "Host: 127.0.0.1:" + server.port(),
            "Origin: http://evil.example", "Connection: close");
        assertEquals("HTTP/1.1 403 Forbidden", status);
    }

    @Test
    public void rejectsARequestWithoutAHostHeader() throws IOException {
        String status = request("GET / HTTP/1.1", "Connection: close");
        assertEquals("HTTP/1.1 400 Bad Request", status);
    }
}
