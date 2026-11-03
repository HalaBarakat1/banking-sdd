package ds.banking.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.client.PaymentClient;
import ds.banking.client.RetryPolicy;
import ds.banking.client.TransactionResult;
import ds.banking.client.TransactionResult.Result;
import ds.banking.protocol.Outcome;
import ds.banking.server.log.RequestLog;
import ds.banking.server.store.RecordRow;
import ds.banking.support.ClientViews;
import ds.banking.support.Invariants;
import ds.banking.support.TestServer;
import ds.banking.support.TestServer.Response;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * User Story 2: each fault scenario, injected for one request, and the debit still executes exactly
 * once (T038; FR-028, FR-029, SC-002, spec FS-1 to FS-4b).
 */
@Tag("integration")
class FaultScenariosIT {

    private static final RetryPolicy POLICY = new RetryPolicy(Duration.ofSeconds(1), Duration.ofMillis(300), 10,
            Duration.ofSeconds(10), Duration.ofMillis(20), Duration.ofMillis(100));

    @TempDir
    Path dir;

    private TestServer server;
    private PaymentClient client;
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            log.add(record.getMessage());
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
        client = PaymentClient.create(server.uri("/"), dir.resolve("client.db"), POLICY);
    }

    @AfterEach
    void stop() {
        try {
            Invariants.check(server.store(), "A");
            Invariants.crossCheck(ClientViews.from(client.journal()), server.store().readRecords("A"));
        } finally {
            server.close();
            RequestLog.logger().removeHandler(capture);
        }
    }

    private void configure(String rules) {
        Response r = server.send("PUT", "/admin/faults", "{\"rules\":[" + rules + "]}");
        assertEquals(200, r.status(), r.body());
    }

    /** Debit 100 from A and check it executed exactly once with the same answer the client got. */
    private TransactionResult debitOnce() {
        TransactionResult result = client.debit("A", 100);

        assertEquals(Result.SUCCEEDED, result.result(), result.toString());
        List<RecordRow> records = server.store().readRecords("A").stream()
                .filter(r -> r.sequenceKey() == result.sequenceKey()).toList();
        assertEquals(1, records.size());
        assertEquals(Outcome.EXECUTED, records.get(0).outcome());
        assertEquals(records.get(0).balanceAfter(), result.balanceAfter());
        assertEquals(900, server.store().readAccount("A").orElseThrow().balance());
        return result;
    }

    private List<String> linesFor(TransactionResult result) {
        String key = "key=" + result.sequenceKey() + " ";
        return log.stream().filter(l -> l.contains(key)).toList();
    }

    /** Waits until a log line matches; delayed requests write their line when they finish. */
    private String awaitLine(Predicate<String> matches) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            for (String line : log) {
                if (matches.test(line)) {
                    return line;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no log line matched; log was " + log);
    }

    private static boolean has(List<String> lines, String... parts) {
        return lines.stream().anyMatch(l -> List.of(parts).stream().allMatch(l::contains));
    }

    @Test
    @DisplayName("FS-1 / FR-028: a lost request is retried with is_retry=true and executes once")
    void requestLost() {
        configure("{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"NEXT_N\",\"count\":1}");

        TransactionResult result = debitOnce();

        assertTrue(result.attempts() >= 2, result.toString());
        List<String> lines = linesFor(result);
        assertTrue(has(lines, "is_retry=false", "decision=DROPPED", "fault=DROP_REQUEST"), lines.toString());
        assertTrue(has(lines, "is_retry=true", "decision=EXECUTED", "fault=none"), lines.toString());
    }

    @Test
    @DisplayName("FS-2 / FR-028: a crash before execution restarts the server; the retry executes once")
    void crashBeforeExecution() {
        configure("{\"scenario\":\"CRASH_BEFORE_EXECUTE\",\"trigger\":\"NEXT_N\",\"count\":1,\"downtime_ms\":300}");

        TransactionResult result = debitOnce();

        assertEquals(1, server.server().restarts());
        List<String> lines = linesFor(result);
        assertTrue(has(lines, "is_retry=false", "decision=DROPPED", "fault=CRASH_BEFORE_EXECUTE"), lines.toString());
        assertTrue(has(lines, "is_retry=true", "decision=EXECUTED"), lines.toString());
    }

    @Test
    @DisplayName("FS-3 / FR-008: the response of a committed debit is lost; the retry replays it as a duplicate")
    void responseLost() {
        configure("{\"scenario\":\"DROP_RESPONSE\",\"trigger\":\"NEXT_N\",\"count\":1}");

        TransactionResult result = debitOnce();

        assertTrue(result.duplicate(), result.toString());
        List<String> lines = linesFor(result);
        assertTrue(has(lines, "is_retry=false", "decision=EXECUTED", "fault=DROP_RESPONSE"), lines.toString());
        assertTrue(has(lines, "is_retry=true", "decision=DUPLICATE"), lines.toString());
    }

    @Test
    @DisplayName("FS-4a / EC-007: a request delayed before deciding races its retry; exactly one executes")
    void delayBeforeExecution() throws Exception {
        configure("{\"scenario\":\"DELAY_BEFORE_EXECUTE\",\"trigger\":\"NEXT_N\",\"count\":1,\"delay_ms\":600}");

        TransactionResult result = debitOnce();

        String delayed = awaitLine(l -> l.contains("key=" + result.sequenceKey() + " ")
                && l.contains("fault=DELAY_BEFORE_EXECUTE"));
        assertTrue(delayed.contains("is_retry=false") && delayed.contains("decision=DUPLICATE"), delayed);
        assertTrue(has(linesFor(result), "is_retry=true", "decision=EXECUTED"), log.toString());
    }

    @Test
    @DisplayName("FS-4b / FR-008: a response delayed past the client timeout; the retry is a duplicate")
    void delayAfterExecution() throws Exception {
        configure("{\"scenario\":\"DELAY_AFTER_EXECUTE\",\"trigger\":\"NEXT_N\",\"count\":1,\"delay_ms\":600}");

        TransactionResult result = debitOnce();

        assertTrue(result.duplicate(), result.toString());
        String delayed = awaitLine(l -> l.contains("key=" + result.sequenceKey() + " ")
                && l.contains("fault=DELAY_AFTER_EXECUTE"));
        assertTrue(delayed.contains("is_retry=false") && delayed.contains("decision=EXECUTED"), delayed);
        assertTrue(has(linesFor(result), "is_retry=true", "decision=DUPLICATE"), log.toString());
    }

    @Test
    @DisplayName("FR-029 / FS-2: the fault config survives a crash-restart and keeps firing")
    void configSurvivesRestart() throws Exception {
        configure("{\"scenario\":\"CRASH_BEFORE_EXECUTE\",\"trigger\":\"NEXT_N\",\"count\":1,\"downtime_ms\":300},"
                + "{\"scenario\":\"DROP_RESPONSE\",\"trigger\":\"NEXT_N\",\"count\":1}");

        TransactionResult result = debitOnce();

        assertEquals(1, server.server().restarts());
        assertTrue(result.duplicate(), result.toString());
        awaitLine(l -> l.contains("key=" + result.sequenceKey() + " ") && l.contains("fault=DROP_RESPONSE"));
        assertEquals(2, server.get("/admin/faults").json().get("rules").size());
    }

    @Test
    @DisplayName("FR-030 / US2-AS5: with no rule configured no fault is injected")
    void noRuleNoFault() {
        TransactionResult result = debitOnce();

        assertEquals(1, result.attempts());
        assertEquals(0, server.server().restarts());
        assertTrue(linesFor(result).stream().allMatch(l -> l.contains("fault=none")), log.toString());
    }
}
