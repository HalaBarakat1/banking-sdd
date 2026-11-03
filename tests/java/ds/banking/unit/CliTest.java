package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import ds.banking.Main;
import ds.banking.cli.UsageException;
import ds.banking.client.ClientMain;
import ds.banking.client.RetryPolicy;
import ds.banking.client.TransactionResult.Result;
import ds.banking.protocol.Json;
import ds.banking.server.ServerMain;
import ds.banking.support.TestServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The jar's command line, contracts/client-cli.md (T026; FR-022, FR-025, FR-031). */
@Tag("unit")
class CliTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int main(String... args) {
        out.reset();
        err.reset();
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private List<String> outLines() {
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private JsonNode lastJson() throws Exception {
        List<String> lines = outLines();
        return Json.mapper().readTree(lines.get(lines.size() - 1));
    }

    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    @DisplayName("FR-031: server defaults are port 8080, data/bank.db, 32 threads and no seeded accounts")
    void serverDefaults() throws Exception {
        ServerMain.Options o = ServerMain.parse(new String[0]);

        assertEquals(8080, o.port());
        assertEquals(Path.of("data", "bank.db"), o.database());
        assertEquals(32, o.threads());
        assertTrue(o.seeds().isEmpty());
    }

    @Test
    @DisplayName("FR-031: server options --port, --db, --threads and --seed A=1000,B=500 are parsed in order")
    void serverOptions() throws Exception {
        ServerMain.Options o = ServerMain.parse(
                new String[] {"--port", "9090", "--db", "x.db", "--threads", "4", "--seed", "A=1000,B=500"});

        Map<String, Long> seeds = new LinkedHashMap<>();
        seeds.put("A", 1000L);
        seeds.put("B", 500L);
        assertEquals(new ServerMain.Options(Path.of("x.db"), 9090, 4, seeds), o);
    }

    @ParameterizedTest(name = "[{index}] server {0}")
    @ValueSource(strings = {"--port abc", "--port 70000", "--threads 0", "--seed A", "--seed A=-1", "--seed a=b", "--bogus 1", "--port"})
    @DisplayName("contracts/client-cli.md: invalid server arguments exit with code 2")
    void invalidServerArguments(String args) {
        String[] parts = ("server " + args).split(" ");

        assertEquals(2, main(parts));
        assertTrue(err.toString(StandardCharsets.UTF_8).toLowerCase().contains("usage"), err.toString());
    }

    @Test
    @DisplayName("FR-022: client defaults are http://127.0.0.1:8080, data/client.db and the NFR-003 retry policy")
    void clientDefaults() throws Exception {
        ClientMain.Options o = ClientMain.parse(new String[] {"debit", "A", "10"});

        assertEquals(URI.create("http://127.0.0.1:8080"), o.url());
        assertEquals(Path.of("data", "client.db"), o.database());
        assertEquals(RetryPolicy.defaults(), o.policy());
        assertEquals("debit", o.command());
        assertEquals(List.of("A", "10"), o.arguments());
    }

    @Test
    @DisplayName("FR-022: every retry limit is configurable from the command line")
    void clientRetryFlags() throws Exception {
        ClientMain.Options o = ClientMain.parse(new String[] {"--url", "http://h:1", "--db", "c.db",
            "--connect-timeout-ms", "300", "--timeout-ms", "400", "--attempts", "3", "--max-total-ms", "5000",
            "--backoff-ms", "20", "--max-backoff-ms", "80", "journal"});

        assertEquals(new RetryPolicy(Duration.ofMillis(300), Duration.ofMillis(400), 3, Duration.ofMillis(5000),
                Duration.ofMillis(20), Duration.ofMillis(80)), o.policy());
        assertEquals(URI.create("http://h:1"), o.url());
        assertEquals("journal", o.command());
    }

    @ParameterizedTest(name = "[{index}] client {0}")
    @ValueSource(strings = {"", "fly", "debit", "debit A", "debit A x", "debit A 0", "debit a%b 10",
        "--attempts 0 debit A 10", "--timeout-ms -5 debit A 10", "--backoff-ms 50 --max-backoff-ms 10 debit A 10",
        "--url ::: debit A 10", "--bogus 1 debit A 10"})
    @DisplayName("contracts/client-cli.md: invalid client arguments exit with code 2")
    void invalidClientArguments(String args) {
        String[] parts = args.isEmpty() ? new String[] {"client"} : ("client " + args).split(" ");

        assertEquals(2, main(parts));
    }

    @Test
    @DisplayName("contracts/client-cli.md: exit code is 3 if any transaction is UNKNOWN, else 4 if REFUSED, else 0")
    void exitCodes() {
        assertEquals(0, ClientMain.exitCodeFor(List.of(Result.SUCCEEDED)));
        assertEquals(0, ClientMain.exitCodeFor(List.of(Result.REJECTED, Result.NOT_EXECUTED)));
        assertEquals(3, ClientMain.exitCodeFor(List.of(Result.UNKNOWN)));
        assertEquals(4, ClientMain.exitCodeFor(List.of(Result.REFUSED)));
        assertEquals(3, ClientMain.exitCodeFor(List.of(Result.SUCCEEDED, Result.UNKNOWN, Result.REFUSED)));
    }

    @Test
    @DisplayName("FR-024 / FR-025: no server -> UNKNOWN (exit 3); a second debit on that account is REFUSED (exit 4)")
    void unknownThenRefused() throws Exception {
        String url = "http://127.0.0.1:" + closedPort();
        String db = dir.resolve("client.db").toString();

        int first = main("client", "--url", url, "--db", db, "--attempts", "2", "--backoff-ms", "1",
                "--max-backoff-ms", "2", "debit", "A", "10");
        JsonNode firstResult = lastJson();
        int second = main("client", "--url", url, "--db", db, "debit", "A", "5");
        JsonNode secondResult = lastJson();

        assertEquals(3, first);
        assertEquals("UNKNOWN", firstResult.get("result").asText());
        assertEquals(1, firstResult.get("sequence_key").asLong());
        assertEquals(4, second);
        assertEquals("REFUSED", secondResult.get("result").asText());
    }

    @Test
    @DisplayName("FR-026: journal is read-only: it prints entries and sends nothing, even with no server")
    void journalSendsNothing() throws Exception {
        String url = "http://127.0.0.1:" + closedPort();
        String db = dir.resolve("client.db").toString();
        main("client", "--url", url, "--db", db, "--attempts", "1", "debit", "A", "10");

        int code = main("client", "--url", url, "--db", db, "journal");

        assertEquals(0, code);
        JsonNode entry = Json.mapper().readTree(outLines().get(0));
        assertEquals(1, entry.get("sequence_key").asLong());
        assertEquals("UNKNOWN", entry.get("state").asText());
    }

    @Test
    @DisplayName("US1-AS1: client debit against a running server prints a SUCCEEDED JSON line and exits 0")
    void debitAgainstServer() throws Exception {
        try (TestServer server = TestServer.start(dir)) {
            server.store().seedAccount("A", 1000);

            int code = main("client", "--url", "http://127.0.0.1:" + server.port(), "--db",
                    dir.resolve("client.db").toString(), "debit", "A", "100");

            assertEquals(0, code);
            JsonNode result = lastJson();
            assertEquals("SUCCEEDED", result.get("result").asText());
            assertEquals("EXECUTED", result.get("outcome").asText());
            assertEquals(900, result.get("balance_after").asLong());
            assertEquals(1, result.get("attempts").asInt());
        }
    }

    @Test
    @DisplayName("contracts/client-cli.md: a usage error is reported as UsageException by the parsers")
    void parsersThrowUsageException() {
        assertThrows(UsageException.class, () -> ClientMain.parse(new String[] {"debit", "A"}));
        assertThrows(UsageException.class, () -> ServerMain.parse(new String[] {"--port", "-1"}));
    }
}
