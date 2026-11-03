package ds.banking.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.protocol.Outcome;
import ds.banking.server.log.RequestLog;
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

/** {@code POST /accounts/{id}/cancellations}, every cancel row of contracts/http-api.md §2 (T050). */
@Tag("contract")
class CancelContractTest {

    @TempDir
    Path dir;

    private TestServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private void start() {
        server = TestServer.start(dir);
        server.store().seedAccount("A", 1000);
    }

    private Response cancel(String account, long key, boolean isRetry) {
        return server.post("/accounts/" + account + "/cancellations",
                "{\"sequence_key\":" + key + ",\"is_retry\":" + isRetry + "}");
    }

    private Response debit(long key, long amount) {
        return server.post("/accounts/A/debits", "{\"sequence_key\":" + key + ",\"amount\":" + amount
                + ",\"is_retry\":false}");
    }

    private static void assertAnswer(Response r, int status, Outcome outcome, boolean duplicate) {
        assertEquals(status, r.status(), r.body());
        assertTrue(r.contentType().startsWith("application/json"), r.contentType());
        assertEquals(outcome.name(), r.json().get("outcome").asText(), r.body());
        assertEquals(duplicate, r.json().get("duplicate").asBoolean(), r.body());
    }

    @Test
    @DisplayName("FR-015 / FR-016: cancelling a new key is 200 CANCELLED; repeating it is 200 CANCELLED with duplicate=true")
    void cancelNewKey() {
        start();

        Response first = cancel("A", 8, false);
        Response again = cancel("A", 8, true);

        assertAnswer(first, 200, Outcome.CANCELLED, false);
        assertEquals("A", first.json().get("account_id").asText());
        assertEquals(8, first.json().get("sequence_key").asLong());
        assertFalse(first.json().has("amount"));
        assertAnswer(again, 200, Outcome.CANCELLED, true);
        assertEquals(8, server.store().readAccount("A").orElseThrow().lastSequenceKey());
    }

    @Test
    @DisplayName("FR-017: cancelling an executed key is 409 ALREADY_EXECUTED with the original amount and balance")
    void cancelExecuted() {
        start();
        debit(8, 100);

        Response late = cancel("A", 8, false);

        assertAnswer(late, 409, Outcome.ALREADY_EXECUTED, true);
        assertEquals(100, late.json().get("amount").asLong());
        assertEquals(900, late.json().get("balance_after").asLong());
    }

    @Test
    @DisplayName("FR-017 / EC-010: cancelling a key refused for insufficient funds is 200 ALREADY_REJECTED")
    void cancelRefused() {
        start();
        debit(8, 5000);

        assertAnswer(cancel("A", 8, false), 200, Outcome.ALREADY_REJECTED, true);
    }

    @Test
    @DisplayName("FR-018: cancelling an undecided key below the last key is 409 STALE_KEY")
    void cancelStale() {
        start();
        debit(8, 100);

        assertAnswer(cancel("A", 5, false), 409, Outcome.STALE_KEY, false);
    }

    @Test
    @DisplayName("FR-011 / US5-AS2: a debit on a key burnt by a cancel is 409 CANCELLED with duplicate=true")
    void debitAfterCancel() {
        start();
        cancel("A", 8, false);

        Response late = debit(8, 100);

        assertEquals(409, late.status());
        assertEquals("CANCELLED", late.json().get("outcome").asText());
        assertEquals(1000, server.store().readAccount("A").orElseThrow().balance());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"NOBODY", "a%20b"})
    @DisplayName("EC-005: cancelling on an unknown account is 404 UNKNOWN_ACCOUNT")
    void unknownAccount(String account) {
        start();

        assertAnswer(cancel(account, 1, false), 404, Outcome.UNKNOWN_ACCOUNT, false);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "{\"sequence_key\":8}",
        "{\"sequence_key\":8,\"is_retry\":false,\"amount\":5}",
        "{\"sequence_key\":0,\"is_retry\":false}",
        "{\"sequence_key\":8.5,\"is_retry\":false}",
        "{\"sequence_key\":\"8\",\"is_retry\":false}",
        ""
    })
    @DisplayName("FR-015 / FR-012: an invalid cancel body is 400 INVALID_REQUEST and burns no key")
    void invalidBody(String body) {
        start();

        Response r = server.post("/accounts/A/cancellations", body);

        assertEquals(400, r.status(), r.body());
        assertEquals("INVALID_REQUEST", r.json().get("outcome").asText());
        assertEquals(0, server.store().readAccount("A").orElseThrow().lastSequenceKey());
    }

    @Test
    @DisplayName("FR-004: a database lock held past busy_timeout makes a cancel 503 UNAVAILABLE")
    void busyDatabaseIs503() throws Exception {
        server = TestServer.start(dir, c -> c.withBusyTimeoutMs(100));
        server.store().seedAccount("A", 1000);

        Response busy;
        try (Connection lock = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("bank.db").toAbsolutePath());
                Statement s = lock.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            busy = cancel("A", 8, false);
            s.execute("ROLLBACK");
        }

        assertEquals(503, busy.status(), busy.body());
        assertEquals("UNAVAILABLE", busy.json().get("outcome").asText());
    }

    @Test
    @DisplayName("NFR-004: every cancel request writes one request-log line with op=CANCEL")
    void oneLogLinePerCancel() {
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
            cancel("A", 8, false);
            cancel("A", 8, true);
        } finally {
            logger.removeHandler(capture);
        }

        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("op=CANCEL account=A key=8 is_retry=false decision=EXECUTED outcome=CANCELLED"),
                lines.get(0));
        assertTrue(lines.get(1).contains("op=CANCEL account=A key=8 is_retry=true decision=DUPLICATE outcome=CANCELLED"),
                lines.get(1));
    }
}
