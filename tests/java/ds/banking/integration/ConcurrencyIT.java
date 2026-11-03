package ds.banking.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.client.AttemptResult;
import ds.banking.client.BankingHttpClient;
import ds.banking.client.RetryPolicy;
import ds.banking.protocol.Answer;
import ds.banking.protocol.Outcome;
import ds.banking.server.store.RecordRow;
import ds.banking.support.Invariants;
import ds.banking.support.TestServer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * User Story 3: simultaneous copies and parallel accounts (T043, verification test; NFR-002, EC-007,
 * SC-003). Requests are sent raw, without a client journal, so only {@link Invariants#check} applies.
 */
@Tag("integration")
class ConcurrencyIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path dir;

    private TestServer server;
    private BankingHttpClient raw;
    private ExecutorService pool;

    @BeforeEach
    void start() {
        server = TestServer.start(dir);
        raw = new BankingHttpClient(server.uri("/"), RetryPolicy.defaults());
        pool = Executors.newFixedThreadPool(50);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
        server.close();
    }

    /** Runs all tasks at the same moment: each waits on one latch that is released after all are queued. */
    private <T> List<T> together(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get());
        }
        return results;
    }

    private static Answer answerOf(AttemptResult result) {
        assertTrue(result instanceof AttemptResult.Final, "no final answer: " + result);
        AttemptResult.Final done = (AttemptResult.Final) result;
        assertEquals(200, done.status(), done.toString());
        return done.answer();
    }

    @Test
    @DisplayName("US3-AS1 / SC-003 / EC-007: 50 simultaneous copies of one debit execute once and all get the same answer")
    void fiftyCopiesExecuteOnce() throws Exception {
        server.store().seedAccount("A", 1000);
        List<Callable<AttemptResult>> copies = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            boolean isRetry = i > 0;
            copies.add(() -> raw.debit("A", 1, 100, isRetry, TIMEOUT));
        }

        List<Answer> answers = together(copies).stream().map(ConcurrencyIT::answerOf).toList();

        List<RecordRow> records = server.store().readRecords("A");
        assertEquals(1, records.size());
        assertEquals(Outcome.EXECUTED, records.get(0).outcome());
        assertEquals(900, server.store().readAccount("A").orElseThrow().balance());
        for (Answer a : answers) {
            assertEquals(Outcome.EXECUTED, a.outcome(), a.toString());
            assertEquals(100L, a.amount());
            assertEquals(900L, a.balanceAfter());
        }
        assertEquals(1, answers.stream().filter(a -> !a.duplicate()).count(), "exactly one decided the key");
        Invariants.check(server.store(), "A");
    }

    @Test
    @DisplayName("US3-AS4 / NFR-002: debits on 10 accounts in parallel keep every account's balance correct")
    void parallelAccountsStayCorrect() throws Exception {
        List<String> accounts = new ArrayList<>();
        for (int a = 0; a < 10; a++) {
            accounts.add("ACC-" + a);
            server.store().seedAccount("ACC-" + a, 10_000);
        }
        List<Callable<Long>> workers = new ArrayList<>();
        for (String account : accounts) {
            workers.add(() -> {
                long debited = 0;
                for (int key = 1; key <= 20; key++) {
                    Answer answer = answerOf(raw.debit(account, key, key * 7L, false, TIMEOUT));
                    assertEquals(Outcome.EXECUTED, answer.outcome());
                    debited += key * 7L;
                }
                return debited;
            });
        }

        List<Long> debited = together(workers);

        for (int a = 0; a < accounts.size(); a++) {
            String account = accounts.get(a);
            assertEquals(10_000 - debited.get(a), server.store().readAccount(account).orElseThrow().balance());
            assertEquals(20, server.store().readRecords(account).size());
            Invariants.check(server.store(), account);
        }
    }
}
