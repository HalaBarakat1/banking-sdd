package ds.banking.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import ds.banking.server.log.RequestLog;
import ds.banking.server.store.RecordRow;
import ds.banking.support.TestServer;
import ds.banking.support.TestServer.Response;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code POST /accounts/{id}/debits}, every debit row of contracts/http-api.md §1 (T021). */
@Tag("contract")
class DebitContractTest {

    @TempDir
    Path dir;

    private TestServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private TestServer start() {
        server = TestServer.start(dir);
        server.store().seedAccount("A", 1000);
        return server;
    }

    private Response debit(String account, long key, long amount, boolean isRetry) {
        return server.post("/accounts/" + account + "/debits",
                "{\"sequence_key\":" + key + ",\"amount\":" + amount + ",\"is_retry\":" + isRetry + "}");
    }

    private static void assertAnswer(Response r, int status, Outcome outcome, boolean duplicate) {
        assertEquals(status, r.status(), r.body());
        assertTrue(r.contentType().startsWith("application/json"), r.contentType());
        assertEquals(outcome.name(), r.json().get("outcome").asText(), r.body());
        assertEquals(duplicate, r.json().get("duplicate").asBoolean(), r.body());
    }

    @Test
    @DisplayName("FR-001 / FR-002 / FR-003: a new debit is 200 EXECUTED with account, key, amount and balance after")
    void executed() {
        start();

        Response r = debit("A", 1, 250, false);

        assertAnswer(r, 200, Outcome.EXECUTED, false);
        JsonNode body = r.json();
        assertEquals("A", body.get("account_id").asText());
        assertEquals(1, body.get("sequence_key").asLong());
        assertEquals(250, body.get("amount").asLong());
        assertEquals(750, body.get("balance_after").asLong());
        assertEquals(750, server.store().readAccount("A").orElseThrow().balance());
    }

    @Test
    @DisplayName("FR-008: a copy of an executed key is 200 EXECUTED with duplicate=true and the same amount and balance")
    void duplicate() {
        start();
        Response first = debit("A", 1, 250, false);

        Response copy = debit("A", 1, 250, true);

        assertAnswer(copy, 200, Outcome.EXECUTED, true);
        assertEquals(first.json().get("balance_after"), copy.json().get("balance_after"));
        assertEquals(750, server.store().readAccount("A").orElseThrow().balance());
    }

    @Test
    @DisplayName("FR-006 / FR-003: insufficient funds is 422 with the amount and the balance at decision time, replayed as a duplicate")
    void insufficientFunds() {
        start();
        server.store().seedAccount("B", 50);

        Response refused = debit("B", 1, 100, false);
        Response copy = debit("B", 1, 100, true);

        assertAnswer(refused, 422, Outcome.INSUFFICIENT_FUNDS, false);
        assertEquals(100, refused.json().get("amount").asLong());
        assertEquals(50, refused.json().get("balance_after").asLong());
        assertAnswer(copy, 422, Outcome.INSUFFICIENT_FUNDS, true);
    }

    @Test
    @DisplayName("FR-009 / FR-003: an undecided key below the last key is 409 STALE_KEY")
    void staleKey() {
        start();
        debit("A", 5, 10, false);

        assertAnswer(debit("A", 3, 10, false), 409, Outcome.STALE_KEY, false);
    }

    @Test
    @DisplayName("FR-011 / EC-003: the same key with a different amount is 409 KEY_CONFLICT")
    void keyConflict() {
        start();
        debit("A", 1, 100, false);

        assertAnswer(debit("A", 1, 999, true), 409, Outcome.KEY_CONFLICT, false);
        assertEquals(900, server.store().readAccount("A").orElseThrow().balance());
    }

    @Test
    @DisplayName("FR-008 / FR-011: a debit on a cancelled key is 409 CANCELLED with duplicate=true")
    void cancelledKey() {
        start();
        server.store().inImmediateTransaction(tx -> {
            tx.insertRecord(new RecordRow("A", 4, Operation.CANCEL, null, Outcome.CANCELLED, 1000, "t"));
            tx.updateAccount("A", 1000, 4);
            return null;
        });

        assertAnswer(debit("A", 4, 100, false), 409, Outcome.CANCELLED, true);
        assertEquals(1000, server.store().readAccount("A").orElseThrow().balance());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"NOBODY", "a%20b"})
    @DisplayName("EC-005 / FR-012: an unknown account is 404 UNKNOWN_ACCOUNT and consumes no key")
    void unknownAccount(String account) {
        start();

        assertAnswer(debit(account, 1, 10, false), 404, Outcome.UNKNOWN_ACCOUNT, false);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "{\"sequence_key\":1,\"amount\":2.5,\"is_retry\":false}",
        "{\"sequence_key\":1,\"amount\":\"250\",\"is_retry\":false}",
        "{\"sequence_key\":1,\"amount\":250}",
        "{\"sequence_key\":1,\"amount\":250,\"is_retry\":false,\"note\":\"x\"}",
        "{\"sequence_key\":0,\"amount\":250,\"is_retry\":false}",
        ""
    })
    @DisplayName("FR-012 / EC-004: an invalid body is 400 INVALID_REQUEST, moves no money and consumes no key")
    void invalidBody(String body) {
        start();

        Response r = server.post("/accounts/A/debits", body);

        assertEquals(400, r.status(), r.body());
        assertEquals("INVALID_REQUEST", r.json().get("outcome").asText());
        assertEquals(0, server.store().readAccount("A").orElseThrow().lastSequenceKey());
        assertTrue(server.store().readRecords("A").isEmpty());
    }

    @Test
    @DisplayName("FR-004: a database lock held past busy_timeout is 503 UNAVAILABLE; the same request succeeds later")
    void busyDatabaseIs503() throws Exception {
        server = TestServer.start(dir, c -> c.withBusyTimeoutMs(100));
        server.store().seedAccount("A", 1000);

        Response busy;
        try (Connection lock = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("bank.db").toAbsolutePath());
                Statement s = lock.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            busy = debit("A", 1, 100, false);
            s.execute("ROLLBACK");
        }
        Response later = debit("A", 1, 100, true);

        assertEquals(503, busy.status(), busy.body());
        assertEquals("UNAVAILABLE", busy.json().get("outcome").asText());
        assertAnswer(later, 200, Outcome.EXECUTED, false);
    }

    @Test
    @DisplayName("FR-004: an unexpected server error is 500 with outcome UNAVAILABLE")
    void unexpectedErrorIs500() {
        server = TestServer.start(dir, c -> c.withDecider(store -> (account, request) -> {
            throw new IllegalStateException("simulated bug");
        }));
        server.store().seedAccount("A", 1000);

        Response r = debit("A", 1, 100, false);

        assertEquals(500, r.status(), r.body());
        assertEquals("UNAVAILABLE", r.json().get("outcome").asText());
        assertEquals(1000, server.store().readAccount("A").orElseThrow().balance());
    }

    @Test
    @DisplayName("NFR-004: every debit request writes exactly one request-log line with its decision")
    void oneLogLinePerRequest() {
        start();
        List<String> lines = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(RequestLog.LOGGER_NAME);
        logger.addHandler(capture);
        try {
            debit("A", 1, 100, false);
            debit("A", 1, 100, true);
            server.post("/accounts/A/debits", "{}");
        } finally {
            logger.removeHandler(capture);
        }

        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("op=DEBIT account=A key=1 is_retry=false decision=EXECUTED outcome=EXECUTED"),
                lines.get(0));
        assertTrue(lines.get(1).contains("is_retry=true decision=DUPLICATE outcome=EXECUTED"), lines.get(1));
        assertTrue(lines.get(2).contains("key=- is_retry=- decision=REJECTED outcome=INVALID_REQUEST"), lines.get(2));
        assertFalse(lines.get(0).contains("latency_ms=-"));
    }
}
