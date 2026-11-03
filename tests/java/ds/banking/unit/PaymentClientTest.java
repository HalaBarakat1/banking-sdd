package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import ds.banking.client.Backoff;
import ds.banking.client.BankingHttpClient;
import ds.banking.client.ClientJournal;
import ds.banking.client.ClientLog;
import ds.banking.client.Clock;
import ds.banking.client.JournalEntry;
import ds.banking.client.JournalState;
import ds.banking.client.PaymentClient;
import ds.banking.client.RetryPolicy;
import ds.banking.client.Sleeper;
import ds.banking.client.TransactionResult;
import ds.banking.client.TransactionResult.Result;
import ds.banking.protocol.Json;
import ds.banking.protocol.Outcome;
import ds.banking.support.StubBankServer;
import ds.banking.support.StubBankServer.Reply;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The retry loop of one debit against a scripted server (T025; FR-021..FR-025, NFR-003, NFR-004). */
@Tag("unit")
class PaymentClientTest {

    private static final String EXECUTED = "{\"account_id\":\"A\",\"sequence_key\":1,\"outcome\":\"EXECUTED\","
            + "\"duplicate\":false,\"amount\":100,\"balance_after\":900}";
    private static final Reply UNAVAILABLE = Reply.json(503, "{\"outcome\":\"UNAVAILABLE\"}");

    @TempDir
    Path dir;

    private final RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(1), Duration.ofMillis(300), 4,
            Duration.ofSeconds(5), Duration.ofMillis(10), Duration.ofMillis(20));
    private final AtomicLong now = new AtomicLong();
    private final List<Duration> sleeps = new ArrayList<>();
    private final Clock fakeClock = now::get;
    private final Sleeper fakeSleeper = d -> {
        sleeps.add(d);
        now.addAndGet(d.toNanos());
    };

    private PaymentClient client(StubBankServer stub, ClientJournal journal) {
        return new PaymentClient(journal, new BankingHttpClient(stub.baseUri(), policy), policy,
                Backoff.withRandomJitter(policy), fakeSleeper, fakeClock);
    }

    private ClientJournal journal() {
        return new ClientJournal(dir.resolve("client.db"));
    }

    private static JsonNode body(StubBankServer.Received r) throws Exception {
        return Json.mapper().readTree(r.body());
    }

    @Test
    @DisplayName("FR-021 / FR-023: retries reuse the key and amount, only the first attempt has is_retry=false")
    void retriesReuseKey() throws Exception {
        try (StubBankServer stub = StubBankServer.start(UNAVAILABLE, UNAVAILABLE, Reply.json(200, EXECUTED))) {
            ClientJournal journal = journal();

            TransactionResult result = client(stub, journal).debit("A", 100);

            assertEquals(Result.SUCCEEDED, result.result());
            assertEquals(Outcome.EXECUTED, result.outcome());
            assertEquals(900L, result.balanceAfter());
            assertEquals(3, result.attempts());
            List<StubBankServer.Received> received = stub.received();
            assertEquals(3, received.size());
            assertFalse(body(received.get(0)).get("is_retry").asBoolean());
            for (StubBankServer.Received r : received.subList(1, 3)) {
                assertTrue(body(r).get("is_retry").asBoolean());
            }
            for (StubBankServer.Received r : received) {
                assertEquals(result.sequenceKey(), body(r).get("sequence_key").asLong());
                assertEquals(100, body(r).get("amount").asLong());
            }
            JournalEntry entry = journal.find(result.sequenceKey()).orElseThrow();
            assertEquals(JournalState.RESOLVED, entry.state());
            assertEquals(Outcome.EXECUTED, entry.outcome());
            assertEquals(3, entry.attempts());
            assertEquals(2, sleeps.size());
            assertTrue(sleeps.get(0).compareTo(Duration.ofMillis(5)) >= 0 && sleeps.get(0).compareTo(Duration.ofMillis(10)) <= 0);
        }
    }

    @Test
    @DisplayName("FR-024 / EC-008: when every attempt fails the run stops at maxAttempts and reports UNKNOWN, keeping the key")
    void exhaustedAttemptsAreUnknown() {
        try (StubBankServer stub = StubBankServer.start(UNAVAILABLE)) {
            ClientJournal journal = journal();

            TransactionResult result = client(stub, journal).debit("A", 100);

            assertEquals(Result.UNKNOWN, result.result());
            assertEquals(4, stub.received().size());
            assertEquals(4, result.attempts());
            JournalEntry entry = journal.find(result.sequenceKey()).orElseThrow();
            assertEquals(JournalState.UNKNOWN, entry.state());
            assertEquals(100, entry.amount());
        }
    }

    @Test
    @DisplayName("FR-022 / NFR-003: a server that never answers is given up at the run's total deadline")
    void neverAnsweringStopsAtDeadline() {
        RetryPolicy shortRun = new RetryPolicy(Duration.ofSeconds(1), Duration.ofMillis(200), 100,
                Duration.ofSeconds(1), Duration.ofMillis(10), Duration.ofMillis(20));
        try (StubBankServer stub = StubBankServer.start(new Reply(200, EXECUTED, 3000))) {
            PaymentClient client = new PaymentClient(journal(), new BankingHttpClient(stub.baseUri(), shortRun),
                    shortRun, Backoff.withRandomJitter(shortRun), Sleeper.system(), Clock.system());
            long started = System.nanoTime();

            TransactionResult result = client.debit("A", 100);

            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertEquals(Result.UNKNOWN, result.result());
            assertTrue(elapsedMs < 1800, "took " + elapsedMs + " ms");
            assertTrue(result.attempts() >= 2 && result.attempts() < 100, "attempts " + result.attempts());
        }
    }

    @Test
    @DisplayName("EC-008 / EC-009 / FR-025: an UNKNOWN entry blocks new debits on its account; nothing is sent")
    void unknownBlocksAccount() {
        try (StubBankServer stub = StubBankServer.start(UNAVAILABLE)) {
            ClientJournal journal = journal();
            PaymentClient client = client(stub, journal);
            client.debit("A", 100);
            int sent = stub.received().size();

            TransactionResult refused = client.debit("A", 5);

            assertEquals(Result.REFUSED, refused.result());
            assertNull(refused.sequenceKey());
            assertEquals(sent, stub.received().size());
            assertEquals(1, journal.lastKey());
        }
    }

    @Test
    @DisplayName("FR-023 / FR-004: a 4xx answer is final at once: no retry, no wait")
    void clientErrorIsFinal() {
        String refused = "{\"account_id\":\"A\",\"sequence_key\":1,\"outcome\":\"INSUFFICIENT_FUNDS\","
                + "\"duplicate\":false,\"amount\":100,\"balance_after\":50}";
        try (StubBankServer stub = StubBankServer.start(Reply.json(422, refused))) {
            TransactionResult result = client(stub, journal()).debit("A", 100);

            assertEquals(Result.REJECTED, result.result());
            assertEquals(Outcome.INSUFFICIENT_FUNDS, result.outcome());
            assertEquals(1, stub.received().size());
            assertTrue(sleeps.isEmpty());
        }
    }

    @Test
    @DisplayName("FR-023: CANCELLED and STALE_KEY mean NOT_EXECUTED; an unparseable 4xx is REJECTED with no outcome")
    void notExecutedAndUnparseable() {
        try (StubBankServer cancelled = StubBankServer.start(Reply.json(409,
                "{\"account_id\":\"A\",\"sequence_key\":1,\"outcome\":\"CANCELLED\",\"duplicate\":true}"));
                StubBankServer garbage = StubBankServer.start(Reply.json(404, "not json"))) {
            TransactionResult notExecuted = client(cancelled, journal()).debit("A", 100);
            TransactionResult rejected = client(garbage, new ClientJournal(dir.resolve("other.db"))).debit("A", 100);

            assertEquals(Result.NOT_EXECUTED, notExecuted.result());
            assertEquals(Result.REJECTED, rejected.result());
            assertNull(rejected.outcome());
        }
    }

    @Test
    @DisplayName("NFR-004: every attempt writes one client log line with key, attempt number, is_retry and result")
    void oneLogLinePerAttempt() {
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
        Logger logger = ClientLog.logger();
        logger.addHandler(capture);
        try (StubBankServer stub = StubBankServer.start(UNAVAILABLE, Reply.json(200, EXECUTED))) {
            client(stub, journal()).debit("A", 100);
        } finally {
            logger.removeHandler(capture);
        }

        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("op=DEBIT account=A key=1 attempt=1 is_retry=false result=retryable"),
                lines.get(0));
        assertTrue(lines.get(1).contains("attempt=2 is_retry=true result=final status=200 outcome=EXECUTED"),
                lines.get(1));
    }
}
