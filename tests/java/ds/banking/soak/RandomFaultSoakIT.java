package ds.banking.soak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.client.ClientJournal;
import ds.banking.client.JournalState;
import ds.banking.client.PaymentClient;
import ds.banking.client.RetryPolicy;
import ds.banking.client.TransactionResult;
import ds.banking.client.TransactionResult.Result;
import ds.banking.server.store.RecordRow;
import ds.banking.support.ClientViews;
import ds.banking.support.Invariants;
import ds.banking.support.TestServer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 1,000 debits over 10 accounts in parallel while every fault scenario fires at random (T055,
 * verification test; SC-001, SC-006, NFR-003, FS-1 to FS-4b).
 */
@Tag("soak")
class RandomFaultSoakIT {

    private static final int ACCOUNTS = 10;
    private static final int DEBITS_PER_ACCOUNT = 100;
    private static final long INITIAL = 1_000_000;
    private static final RetryPolicy POLICY = new RetryPolicy(Duration.ofSeconds(1), Duration.ofMillis(150), 6,
            Duration.ofSeconds(5), Duration.ofMillis(10), Duration.ofMillis(50));
    private static final String FAULTS = "{\"rules\":["
            + "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"PROBABILITY\",\"probability\":0.1},"
            + "{\"scenario\":\"CRASH_BEFORE_EXECUTE\",\"trigger\":\"PROBABILITY\",\"probability\":0.1,\"downtime_ms\":100},"
            + "{\"scenario\":\"DROP_RESPONSE\",\"trigger\":\"PROBABILITY\",\"probability\":0.1},"
            + "{\"scenario\":\"DELAY_BEFORE_EXECUTE\",\"trigger\":\"PROBABILITY\",\"probability\":0.1,\"delay_ms\":300},"
            + "{\"scenario\":\"DELAY_AFTER_EXECUTE\",\"trigger\":\"PROBABILITY\",\"probability\":0.1,\"delay_ms\":300}"
            + "],\"seed\":42}";

    @TempDir
    Path dir;

    @Test
    @DisplayName("SC-001 / SC-006 / NFR-003 / FS-1 / FS-2 / FS-3 / FS-4a / FS-4b: 1,000 debits under random faults; "
            + "every balance is exact, no key is executed twice, and every transaction ends within its deadline")
    void thousandDebitsUnderRandomFaults() throws Exception {
        try (TestServer server = TestServer.start(dir)) {
            for (int a = 0; a < ACCOUNTS; a++) {
                server.store().seedAccount("S" + a, INITIAL);
            }
            assertEquals(200, server.send("PUT", "/admin/faults", FAULTS).status());
            PaymentClient client = PaymentClient.create(server.uri("/"), dir.resolve("client.db"), POLICY);
            AtomicLong slowest = new AtomicLong();
            AtomicLong unknowns = new AtomicLong();
            AtomicLong refusals = new AtomicLong();

            ExecutorService pool = Executors.newFixedThreadPool(ACCOUNTS);
            List<Future<?>> workers = new ArrayList<>();
            for (int a = 0; a < ACCOUNTS; a++) {
                String account = "S" + a;
                Random amounts = new Random(a);
                workers.add(pool.submit(() -> {
                    for (int i = 0; i < DEBITS_PER_ACCOUNT; i++) {
                        long amount = 1 + amounts.nextInt(100);
                        TransactionResult r;
                        // Like a real Order Service: if the account is blocked by an unresolved
                        // transaction (FR-025), finish that one first, then retry this debit.
                        do {
                            long started = System.nanoTime();
                            r = client.debit(account, amount);
                            slowest.accumulateAndGet(System.nanoTime() - started, Math::max);
                            if (r.result() == Result.UNKNOWN) {
                                unknowns.incrementAndGet();
                                resolve(client, r.sequenceKey());
                            } else if (r.result() == Result.REFUSED) {
                                refusals.incrementAndGet();
                                resolveAccount(client, account);
                            }
                        } while (r.result() == Result.REFUSED);
                    }
                    return null;
                }));
            }
            for (Future<?> w : workers) {
                w.get();
            }
            pool.shutdown();

            // Faults off, then finish anything still unresolved.
            assertEquals(200, server.send("DELETE", "/admin/faults", null).status());
            ClientJournal journal = client.journal();
            for (int round = 0; round < 10 && !journal.unresolved().isEmpty(); round++) {
                client.recoverPending();
            }
            assertTrue(journal.unresolved().isEmpty(), "unresolved: " + journal.unresolved());

            List<RecordRow> records = new ArrayList<>();
            for (int a = 0; a < ACCOUNTS; a++) {
                Invariants.check(server.store(), "S" + a);
                records.addAll(server.store().readRecords("S" + a));
            }
            Invariants.crossCheck(ClientViews.from(journal), records);
            assertEquals(ACCOUNTS * DEBITS_PER_ACCOUNT, journal.entries().size());
            assertTrue(journal.entries().stream().allMatch(e -> e.state() == JournalState.RESOLVED));
            Duration slowestRun = Duration.ofNanos(slowest.get());
            assertTrue(slowestRun.compareTo(POLICY.maxTotal().plusSeconds(1)) <= 0, "slowest debit " + slowestRun);
            assertTrue(server.server().restarts() > 0, "FS-2 never fired");
            System.out.printf("soak: %d debits, %d UNKNOWN then resolved, %d local refusals retried, "
                    + "%d crash-restarts, slowest %d ms%n", journal.entries().size(), unknowns.get(), refusals.get(),
                    server.server().restarts(), slowestRun.toMillis());
        }
    }

    /**
     * Resends until the key is resolved. While faults are on a run can end UNKNOWN again, and another
     * worker's recovery may be sending the same entry right now (then it is skipped), so pause briefly
     * between rounds.
     */
    private static void resolve(PaymentClient client, long key) throws InterruptedException {
        for (int round = 0; round < 200; round++) {
            client.recoverPending();
            if (client.journal().find(key).orElseThrow().state() == JournalState.RESOLVED) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("key " + key + " still unresolved");
    }

    private static void resolveAccount(PaymentClient client, String account) throws InterruptedException {
        for (var entry : client.journal().unresolved()) {
            if (entry.accountId().equals(account)) {
                resolve(client, entry.sequenceKey());
            }
        }
    }
}
