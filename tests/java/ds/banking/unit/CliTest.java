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
        "--url ::: debit A 10", "--bogus 1 debit A 10", "recover A"})
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

    /** Client arguments with fast retries, so tests against a closed port finish quickly. */
    private static String[] fast(String url, String db, String... command) {
        List<String> args = new java.util.ArrayList<>(List.of("client", "--url", url, "--db", db, "--attempts", "2",
                "--backoff-ms", "1", "--max-backoff-ms", "2"));
        args.addAll(List.of(command));
        return args.toArray(String[]::new);
    }

    @Test
    @DisplayName("FR-024 / FR-025 / FR-026: no server -> UNKNOWN (exit 3); the next debit first resends it (still UNKNOWN), "
            + "then is REFUSED; exit 3 wins over 4")
    void unknownThenRefused() throws Exception {
        String url = "http://127.0.0.1:" + closedPort();
        String db = dir.resolve("client.db").toString();

        int first = main(fast(url, db, "debit", "A", "10"));
        JsonNode firstResult = lastJson();
        int second = main(fast(url, db, "debit", "A", "5"));
        List<String> secondLines = outLines();

        assertEquals(3, first);
        assertEquals("UNKNOWN", firstResult.get("result").asText());
        assertEquals(1, firstResult.get("sequence_key").asLong());
        assertEquals(3, second);
        assertEquals(2, secondLines.size(), secondLines.toString());
        JsonNode recovered = Json.mapper().readTree(secondLines.get(0));
        assertEquals("UNKNOWN", recovered.get("result").asText());
        assertEquals(1, recovered.get("sequence_key").asLong());
        assertEquals("REFUSED", lastJson().get("result").asText());
    }

    @Test
    @DisplayName("FR-026: recover with nothing unresolved prints nothing and exits 0")
    void recoverNothing() {
        assertEquals(0, main("client", "--url", "http://127.0.0.1:1", "--db", dir.resolve("client.db").toString(),
                "recover"));
        assertTrue(outLines().isEmpty(), outLines().toString());
    }

    @Test
    @DisplayName("FR-026 / FR-024: recover with the server still down keeps the entry UNKNOWN and exits 3")
    void recoverWhileServerDown() throws Exception {
        String url = "http://127.0.0.1:" + closedPort();
        String db = dir.resolve("client.db").toString();
        main(fast(url, db, "debit", "A", "10"));

        int code = main(fast(url, db, "recover"));

        assertEquals(3, code);
        assertEquals("UNKNOWN", lastJson().get("result").asText());
    }

    @Test
    @DisplayName("FR-026 / SC-005: recover against a running server resolves the UNKNOWN entry and exits 0")
    void recoverAgainstServer() throws Exception {
        String db = dir.resolve("client.db").toString();
        main(fast("http://127.0.0.1:" + closedPort(), db, "debit", "A", "10"));

        try (TestServer server = TestServer.start(dir)) {
            server.store().seedAccount("A", 1000);

            int code = main(fast("http://127.0.0.1:" + server.port(), db, "recover"));

            assertEquals(0, code);
            JsonNode result = lastJson();
            assertEquals("SUCCEEDED", result.get("result").asText());
            assertEquals(1, result.get("sequence_key").asLong());
            assertEquals(990, server.store().readAccount("A").orElseThrow().balance());
        }
    }

    @Test
    @DisplayName("FR-026 / FR-025: debit first resends the account's UNKNOWN entry; once resolved, the new debit goes ahead")
    void debitRecoversFirst() throws Exception {
        String db = dir.resolve("client.db").toString();
        main(fast("http://127.0.0.1:" + closedPort(), db, "debit", "A", "10"));

        try (TestServer server = TestServer.start(dir)) {
            server.store().seedAccount("A", 1000);

            int code = main(fast("http://127.0.0.1:" + server.port(), db, "debit", "A", "5"));

            assertEquals(0, code);
            List<String> lines = outLines();
            assertEquals(2, lines.size(), lines.toString());
            assertEquals(1, Json.mapper().readTree(lines.get(0)).get("sequence_key").asLong());
            assertEquals("SUCCEEDED", Json.mapper().readTree(lines.get(0)).get("result").asText());
            JsonNode debit = lastJson();
            assertEquals("SUCCEEDED", debit.get("result").asText());
            assertEquals(2, debit.get("sequence_key").asLong());
            assertEquals(985, server.store().readAccount("A").orElseThrow().balance());
        }
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
