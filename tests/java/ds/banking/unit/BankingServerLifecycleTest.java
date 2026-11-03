package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.server.BankingServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HTTP server start, stop and restart on the same port (T011; NFR-002, research R1, R11). */
@Tag("unit")
class BankingServerLifecycleTest {

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1))
            .build();

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private BankingServer.Config config() {
        return BankingServer.Config.defaults(dir.resolve("bank.db")).withPort(0);
    }

    @Test
    @DisplayName("NFR-002: the server starts on a free port with a pool of 32 threads and answers unknown paths with 404 JSON")
    void startsAndAnswers() throws Exception {
        try (BankingServer server = BankingServer.start(config())) {
            assertTrue(server.port() > 0);
            assertEquals(32, server.threads());

            HttpResponse<String> response = get(server.port(), "/nope");

            assertEquals(404, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
            assertTrue(response.body().contains("\"message\""), response.body());
        }
    }

    @Test
    @DisplayName("NFR-001: starting the server creates the database schema")
    void createsSchema() throws Exception {
        try (BankingServer server = BankingServer.start(config())) {
            assertTrue(server.store().readAccount("A").isEmpty());
        }
    }

    @Test
    @DisplayName("NFR-002: after stop the port no longer accepts requests")
    void stopReleasesPort() throws Exception {
        BankingServer server = BankingServer.start(config());
        int port = server.port();

        server.stop();

        assertThrows(IOException.class, () -> get(port, "/nope"));
    }

    @Test
    @DisplayName("NFR-001 / FS-2: a new server can be started on the same port and database right after stop")
    void restartOnSamePort() throws Exception {
        BankingServer first = BankingServer.start(config());
        int port = first.port();
        first.store().seedAccount("A", 1000);
        first.stop();

        try (BankingServer second = BankingServer.start(config().withPort(port))) {
            assertEquals(port, second.port());
            assertEquals(404, get(port, "/nope").statusCode());
            assertEquals(1000, second.store().readAccount("A").orElseThrow().balance());
        }
    }

    @Test
    @DisplayName("NFR-002: binding a port that stays busy gives up after the 2 s rebind window")
    void busyPortFailsAfterRetryWindow() throws Exception {
        try (BankingServer holder = BankingServer.start(config())) {
            long started = System.nanoTime();

            assertThrows(IOException.class, () -> BankingServer.start(
                    BankingServer.Config.defaults(dir.resolve("other.db")).withPort(holder.port())));

            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertTrue(elapsedMs >= 1900 && elapsedMs < 5000, "gave up after " + elapsedMs + " ms");
        }
    }
}
