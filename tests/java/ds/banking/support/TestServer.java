package ds.banking.support;

import com.fasterxml.jackson.databind.JsonNode;
import ds.banking.protocol.Json;
import ds.banking.server.BankingServer;
import ds.banking.server.store.LedgerStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.UnaryOperator;

/**
 * A Banking Service on a random free port and a temporary database, plus a raw HTTP helper, for
 * contract and integration tests (research R9).
 */
public final class TestServer implements AutoCloseable {

    private final BankingServer server;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private TestServer(BankingServer server) {
        this.server = server;
    }

    /** Starts a server with default settings on {@code dir/bank.db} and a free port. */
    public static TestServer start(Path dir) {
        return start(dir, c -> c);
    }

    /** Starts a server with customized settings on {@code dir/bank.db} and a free port. */
    public static TestServer start(Path dir, UnaryOperator<BankingServer.Config> customize) {
        BankingServer.Config config = customize.apply(
                BankingServer.Config.defaults(dir.resolve("bank.db")).withPort(0));
        try {
            return new TestServer(BankingServer.start(config));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public BankingServer server() {
        return server;
    }

    public LedgerStore store() {
        return server.store();
    }

    public int port() {
        return server.port();
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    public Response post(String path, String json) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    public Response send(String method, String path, String json) {
        HttpRequest.BodyPublisher body = json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json);
        return send(HttpRequest.newBuilder(uri(path)).method(method, body));
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> r = http.send(builder.timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body(), r.headers().firstValue("Content-Type").orElse(""));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop();
    }

    /** A raw HTTP response. */
    public record Response(int status, String body, String contentType) {

        public JsonNode json() {
            try {
                return Json.mapper().readTree(body);
            } catch (IOException e) {
                throw new AssertionError("response body is not JSON: " + body, e);
            }
        }
    }
}
