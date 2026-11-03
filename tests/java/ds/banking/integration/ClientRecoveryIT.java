package ds.banking.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.client.Backoff;
import ds.banking.client.BankingHttpClient;
import ds.banking.client.ClientHooks;
import ds.banking.client.ClientJournal;
import ds.banking.client.Clock;
import ds.banking.client.JournalEntry;
import ds.banking.client.JournalState;
import ds.banking.client.PaymentClient;
import ds.banking.client.RetryPolicy;
import ds.banking.client.Sleeper;
import ds.banking.client.TransactionResult;
import ds.banking.client.TransactionResult.Result;
import ds.banking.protocol.Outcome;
import ds.banking.server.log.RequestLog;
import ds.banking.server.store.RecordRow;
import ds.banking.support.ClientViews;
import ds.banking.support.Invariants;
import ds.banking.support.TestServer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * User Story 4: the client crashes at chosen points and a new client on the same {@code client.db}
 * finishes the work (T046; FR-021, FR-022, FR-026, SC-005, EC-008).
 */
@Tag("integration")
class ClientRecoveryIT {

    private static final RetryPolicy POLICY = new RetryPolicy(Duration.ofSeconds(1), Duration.ofMillis(300), 6,
            Duration.ofSeconds(10), Duration.ofMillis(10), Duration.ofMillis(50));

    /** Thrown by a hook to stop the client at that point, as a process crash would. */
    private static final class SimulatedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SimulatedCrash(ClientHooks.Point point) {
            super("simulated client crash at " + point);
        }
    }

    @TempDir
    Path dir;

    private TestServer server;
    private Path journalFile;
    private final List<String> serverLog = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            serverLog.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void start() {
        RequestLog.logger().addHandler(capture);
        server = TestServer.start(dir);
        server.store().seedAccount("A", 1000);
        server.store().seedAccount("B", 1000);
        journalFile = dir.resolve("client.db");
    }

    @AfterEach
    void stop() {
        try {
            for (String account : List.of("A", "B")) {
                Invariants.check(server.store(), account);
            }
            List<RecordRow> records = new ArrayList<>(server.store().readRecords("A"));
            records.addAll(server.store().readRecords("B"));
            Invariants.crossCheck(ClientViews.from(new ClientJournal(journalFile)), records);
        } finally {
            server.close();
            RequestLog.logger().removeHandler(capture);
        }
    }

    /** A client on the shared journal file; {@code crashAt} null means it never crashes. */
    private PaymentClient client(ClientHooks.Point crashAt) {
        ClientHooks hooks = (point, entry) -> {
            if (point == crashAt) {
                throw new SimulatedCrash(point);
            }
        };
        return new PaymentClient(new ClientJournal(journalFile), new BankingHttpClient(server.uri("/"), POLICY),
                POLICY, Backoff.withRandomJitter(POLICY), Sleeper.system(), Clock.system(), hooks);
    }

    private List<String> serverLinesFor(long key) {
        return serverLog.stream().filter(l -> l.contains("key=" + key + " ")).toList();
    }

    private List<RecordRow> executed(String account, long key) {
        return server.store().readRecords(account).stream()
                .filter(r -> r.sequenceKey() == key && r.outcome() == Outcome.EXECUTED).toList();
    }

    @Test
    @DisplayName("US4-AS1 / FR-026 / FR-021: crash after reserving, before any attempt: the restarted client sends it once with is_retry=false")
    void crashBeforeSending() {
        assertThrows(SimulatedCrash.class, () -> client(ClientHooks.Point.AFTER_RESERVE).debit("A", 100));
        JournalEntry pending = new ClientJournal(journalFile).unresolved().get(0);
        assertEquals(JournalState.PENDING, pending.state());
        assertEquals(0, pending.attempts());
        assertTrue(server.store().readRecords("A").isEmpty());

        List<TransactionResult> recovered = client(null).recoverPending();

        assertEquals(1, recovered.size());
        assertEquals(Result.SUCCEEDED, recovered.get(0).result());
        assertEquals(pending.sequenceKey(), recovered.get(0).sequenceKey());
        assertEquals(1, executed("A", pending.sequenceKey()).size());
        List<String> lines = serverLinesFor(pending.sequenceKey());
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("is_retry=false"), lines.get(0));
        assertEquals(900, server.store().readAccount("A").orElseThrow().balance());
    }

    @Test
    @DisplayName("US4-AS2 / FR-021: crash after the server executed, before recording: the resend is a duplicate, no second debit")
    void crashAfterServerExecuted() {
        assertThrows(SimulatedCrash.class,
                () -> client(ClientHooks.Point.AFTER_RESPONSE_BEFORE_RECORD).debit("A", 100));
        JournalEntry pending = new ClientJournal(journalFile).unresolved().get(0);
        assertEquals(JournalState.PENDING, pending.state());
        assertEquals(1, pending.attempts());
        assertEquals(1, executed("A", pending.sequenceKey()).size());

        TransactionResult recovered = client(null).recoverPending().get(0);

        assertEquals(Result.SUCCEEDED, recovered.result());
        assertTrue(recovered.duplicate());
        assertEquals(900L, recovered.balanceAfter());
        assertEquals(1, executed("A", pending.sequenceKey()).size());
        assertTrue(serverLinesFor(pending.sequenceKey()).stream()
                .anyMatch(l -> l.contains("is_retry=true") && l.contains("decision=DUPLICATE")), serverLog.toString());
        assertEquals(900, server.store().readAccount("A").orElseThrow().balance());
    }

    @Test
    @DisplayName("US4-AS3 / FR-019: after a crash the next new transaction gets a key greater than the reserved one")
    void keysNeverReused() {
        assertThrows(SimulatedCrash.class, () -> client(ClientHooks.Point.AFTER_RESERVE).debit("A", 100));
        long reserved = new ClientJournal(journalFile).unresolved().get(0).sequenceKey();

        TransactionResult next = client(null).debit("B", 50);

        assertTrue(next.sequenceKey() > reserved, reserved + " then " + next.sequenceKey());
        assertEquals(Result.SUCCEEDED, next.result());
        assertEquals(Result.SUCCEEDED, client(null).recoverPending().get(0).result());
    }

    @Test
    @DisplayName("FR-022 / EC-008 / FR-026: an UNKNOWN entry that used all 6 attempts is resent in a fresh run and resolves")
    void unknownWithAllAttemptsIsResent() {
        TestServer.Response faults = put("{\"rules\":[{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"NEXT_N\",\"count\":6}]}");
        assertEquals(200, faults.status());
        TransactionResult lost = client(null).debit("A", 100);
        assertEquals(Result.UNKNOWN, lost.result());
        JournalEntry unknown = new ClientJournal(journalFile).find(lost.sequenceKey()).orElseThrow();
        assertEquals(JournalState.UNKNOWN, unknown.state());
        assertEquals(6, unknown.attempts());
        assertEquals(Result.REFUSED, client(null).debit("A", 5).result());

        TransactionResult recovered = client(null).recoverPending().get(0);

        assertEquals(Result.SUCCEEDED, recovered.result());
        assertEquals(1, recovered.attempts());
        assertTrue(serverLinesFor(lost.sequenceKey()).stream()
                .anyMatch(l -> l.contains("is_retry=true") && l.contains("decision=EXECUTED")), serverLog.toString());
        assertEquals(Result.SUCCEEDED, client(null).debit("A", 5).result());
        assertEquals(895, server.store().readAccount("A").orElseThrow().balance());
    }

    @Test
    @DisplayName("FR-025 / FR-026 / SC-001: a recovery in another thread that picks up a fresh debit's key does not make "
            + "that debit report REFUSED; it waits and reports the key's real outcome, so a caller never pays twice")
    void recoveryRacingAFreshDebit() throws Exception {
        put("{\"rules\":[{\"scenario\":\"DELAY_BEFORE_EXECUTE\",\"trigger\":\"NEXT_N\",\"count\":1,\"delay_ms\":300}]}");
        ClientJournal journal = new ClientJournal(journalFile);
        PaymentClient[] holder = new PaymentClient[1];
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<List<TransactionResult>>> racing =
                new java.util.concurrent.atomic.AtomicReference<>();
        ClientHooks startRecoveryAfterReserve = (point, entry) -> {
            if (point != ClientHooks.Point.AFTER_RESERVE) {
                return;
            }
            racing.set(java.util.concurrent.CompletableFuture.supplyAsync(() -> holder[0].recoverPending()));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (journal.find(entry.sequenceKey()).orElseThrow().attempts() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        };
        holder[0] = new PaymentClient(journal, new BankingHttpClient(server.uri("/"), POLICY), POLICY,
                Backoff.withRandomJitter(POLICY), Sleeper.system(), Clock.system(), startRecoveryAfterReserve);

        TransactionResult debit = holder[0].debit("A", 100);

        racing.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(Result.SUCCEEDED, debit.result(), debit.toString());
        assertEquals(1, journal.entries().size());
        assertEquals(900, server.store().readAccount("A").orElseThrow().balance());
    }

    @Test
    @DisplayName("FR-026: with nothing unresolved, recovery sends nothing")
    void nothingToRecover() {
        client(null).debit("A", 100);
        int linesBefore = serverLog.size();

        assertTrue(client(null).recoverPending().isEmpty());
        assertEquals(linesBefore, serverLog.size());
    }

    private TestServer.Response put(String json) {
        return server.send("PUT", "/admin/faults", json);
    }
}
