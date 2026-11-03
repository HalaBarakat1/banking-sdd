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
import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import ds.banking.server.log.RequestLog;
import ds.banking.support.ClientViews;
import ds.banking.support.Invariants;
import ds.banking.support.TestServer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** User Story 5: cancel burns a key so a late debit copy can never execute (T051; SC-007, FR-021, FR-027). */
@Tag("integration")
class CancelIT {

    /** Gives up fast: 3 attempts of 200 ms within 1 s, so a delayed debit ends UNKNOWN quickly. */
    private static final RetryPolicy FAST = new RetryPolicy(Duration.ofSeconds(1), Duration.ofMillis(200), 3,
            Duration.ofMillis(1000), Duration.ofMillis(10), Duration.ofMillis(20));

    private static final class SimulatedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SimulatedCrash() {
            super("simulated client crash");
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
        Logger.getLogger(RequestLog.LOGGER_NAME).addHandler(capture);
        server = TestServer.start(dir);
        server.store().seedAccount("A", 1000);
        journalFile = dir.resolve("client.db");
    }

    @AfterEach
    void stop() {
        try {
            Invariants.check(server.store(), "A");
            Invariants.crossCheck(ClientViews.from(new ClientJournal(journalFile)), server.store().readRecords("A"));
        } finally {
            server.close();
            Logger.getLogger(RequestLog.LOGGER_NAME).removeHandler(capture);
        }
    }

    private PaymentClient client(RetryPolicy policy, ClientHooks hooks) {
        return new PaymentClient(new ClientJournal(journalFile), new BankingHttpClient(server.uri("/"), policy),
                policy, Backoff.withRandomJitter(policy), Sleeper.system(), Clock.system(), hooks);
    }

    private void faults(String rules) {
        assertEquals(200, server.send("PUT", "/admin/faults", "{\"rules\":[" + rules + "]}").status());
    }

    private List<String> lines(Predicate<String> matches) {
        return serverLog.stream().filter(matches).toList();
    }

    private static Predicate<String> op(Operation operation, long key) {
        return l -> l.contains("op=" + operation + " ") && l.contains("key=" + key + " ");
    }

    private void awaitLines(Predicate<String> matches, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (lines(matches).size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(count, lines(matches).size(), serverLog.toString());
    }

    @Test
    @DisplayName("SC-007 / US5-AS1 / US5-AS2: debit delayed until UNKNOWN, then cancelled; the delayed copies replay CANCELLED and no money moves")
    void cancelBeatsDelayedDebit() throws Exception {
        faults("{\"scenario\":\"DELAY_BEFORE_EXECUTE\",\"trigger\":\"NEXT_N\",\"count\":3,\"operation\":\"DEBIT\","
                + "\"delay_ms\":2000}");
        PaymentClient client = client(FAST, ClientHooks.NONE);

        TransactionResult debit = client.debit("A", 100);
        assertEquals(Result.UNKNOWN, debit.result());
        TransactionResult cancel = client.cancel("A", debit.sequenceKey());

        assertEquals(Result.CANCELLED, cancel.result());
        assertEquals(Outcome.CANCELLED, cancel.outcome());
        List<String> cancelLines = lines(op(Operation.CANCEL, debit.sequenceKey()));
        assertTrue(cancelLines.get(0).contains("is_retry=false") && cancelLines.get(0).contains("decision=EXECUTED"),
                cancelLines.toString());
        Predicate<String> delayed = op(Operation.DEBIT, debit.sequenceKey())
                .and(l -> l.contains("fault=DELAY_BEFORE_EXECUTE"));
        awaitLines(delayed, 3);
        for (String line : lines(delayed)) {
            assertTrue(line.contains("decision=DUPLICATE outcome=CANCELLED"), line);
        }
        assertEquals(1000, server.store().readAccount("A").orElseThrow().balance());
        JournalEntry entry = new ClientJournal(journalFile).find(debit.sequenceKey()).orElseThrow();
        assertEquals(Operation.CANCEL, entry.operation());
        assertEquals(JournalState.RESOLVED, entry.state());
    }

    @Test
    @DisplayName("FR-027 / FR-021: a crash right after the switch to cancel; the next start sends the cancel, never the debit")
    void crashAfterSwitchSendsCancel() {
        faults("{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"NEXT_N\",\"count\":3,\"operation\":\"DEBIT\"}");
        TransactionResult debit = client(FAST, ClientHooks.NONE).debit("A", 100);
        assertEquals(Result.UNKNOWN, debit.result());
        int debitLines = lines(op(Operation.DEBIT, debit.sequenceKey())).size();
        ClientHooks crashAfterSwitch = (point, entry) -> {
            if (point == ClientHooks.Point.AFTER_CANCEL_SWITCH) {
                throw new SimulatedCrash();
            }
        };

        assertThrows(SimulatedCrash.class, () -> client(FAST, crashAfterSwitch).cancel("A", debit.sequenceKey()));
        JournalEntry switched = new ClientJournal(journalFile).find(debit.sequenceKey()).orElseThrow();
        assertEquals(Operation.CANCEL, switched.operation());
        assertEquals(JournalState.PENDING, switched.state());
        assertEquals(0, switched.attempts());

        List<TransactionResult> recovered = client(FAST, ClientHooks.NONE).recoverPending();

        assertEquals(Result.CANCELLED, recovered.get(0).result());
        assertEquals(debitLines, lines(op(Operation.DEBIT, debit.sequenceKey())).size(), "the debit was sent again");
        assertTrue(lines(op(Operation.CANCEL, debit.sequenceKey())).get(0).contains("is_retry=false"), serverLog.toString());
        assertEquals(1000, server.store().readAccount("A").orElseThrow().balance());
    }

    @Test
    @DisplayName("research R12: a cancel of a key that this client is sending right now is REFUSED locally")
    void cancelOfInFlightKeyRefused() throws Exception {
        faults("{\"scenario\":\"DELAY_BEFORE_EXECUTE\",\"trigger\":\"NEXT_N\",\"count\":1,\"operation\":\"DEBIT\","
                + "\"delay_ms\":800}");
        RetryPolicy patient = RetryPolicy.defaults();
        PaymentClient client = client(patient, ClientHooks.NONE);
        CompletableFuture<TransactionResult> debit = CompletableFuture.supplyAsync(() -> client.debit("A", 100));
        ClientJournal journal = new ClientJournal(journalFile);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while ((journal.unresolved().isEmpty() || journal.unresolved().get(0).attempts() == 0)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        long key = journal.unresolved().get(0).sequenceKey();

        TransactionResult refused = client.cancel("A", key);

        assertEquals(Result.REFUSED, refused.result());
        assertEquals(Result.SUCCEEDED, debit.get(10, TimeUnit.SECONDS).result());
        assertTrue(lines(op(Operation.CANCEL, key)).isEmpty());
        assertEquals(900, server.store().readAccount("A").orElseThrow().balance());
    }

    @Test
    @DisplayName("FR-027: cancel is REFUSED for a resolved key, a key not in the journal, and a key of another account")
    void cancelRefusedCases() {
        PaymentClient client = client(FAST, ClientHooks.NONE);
        TransactionResult done = client.debit("A", 100);

        assertEquals(Result.REFUSED, client.cancel("A", done.sequenceKey()).result());
        assertEquals(Result.REFUSED, client.cancel("A", 99).result());
        assertEquals(Result.REFUSED, client.cancel("B", done.sequenceKey()).result());
        assertTrue(lines(l -> l.contains("op=CANCEL")).isEmpty());
    }
}
