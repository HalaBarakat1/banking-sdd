package ds.banking.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.client.AttemptResult;
import ds.banking.client.BankingHttpClient;
import ds.banking.client.RetryPolicy;
import ds.banking.protocol.Outcome;
import ds.banking.support.Invariants;
import ds.banking.support.TestServer;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * User Story 3: copies that arrive after newer keys were decided (T044, verification test; FR-008,
 * FR-009). Requests are sent raw, without a client journal, so only {@link Invariants#check} applies.
 */
@Tag("integration")
class LateCopiesIT {

    @TempDir
    Path dir;

    private TestServer server;
    private BankingHttpClient raw;

    @BeforeEach
    void start() {
        server = TestServer.start(dir);
        server.store().seedAccount("A", 1000);
        raw = new BankingHttpClient(server.uri("/"), RetryPolicy.defaults());
    }

    @AfterEach
    void stop() {
        try {
            Invariants.check(server.store(), "A");
        } finally {
            server.close();
        }
    }

    private AttemptResult.Final debit(long key, long amount, boolean isRetry) {
        return assertInstanceOf(AttemptResult.Final.class, raw.debit("A", key, amount, isRetry, Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("US3-AS2 / FR-008: a late copy of key 5 after key 6 was executed replays key 5's original answer")
    void lateCopyReplaysOriginal() {
        AttemptResult.Final original = debit(5, 100, false);
        debit(6, 50, false);

        AttemptResult.Final late = debit(5, 100, true);

        assertEquals(200, late.status());
        assertEquals(Outcome.EXECUTED, late.answer().outcome());
        assertTrue(late.answer().duplicate());
        assertEquals(original.answer().balanceAfter(), late.answer().balanceAfter());
        assertEquals(850, server.store().readAccount("A").orElseThrow().balance());
        assertEquals(6, server.store().readAccount("A").orElseThrow().lastSequenceKey());
    }

    @Test
    @DisplayName("US3-AS3 / FR-009: an undecided key below the last key is STALE_KEY now and stays so after newer keys")
    void staleKeyNeverExecutes() {
        debit(5, 100, false);

        AttemptResult.Final stale = debit(4, 100, false);
        debit(7, 10, false);
        AttemptResult.Final staleAgain = debit(4, 100, true);

        assertEquals(409, stale.status());
        assertEquals(Outcome.STALE_KEY, stale.answer().outcome());
        assertFalse(stale.answer().duplicate());
        assertEquals(Outcome.STALE_KEY, staleAgain.answer().outcome());
        assertTrue(server.store().readRecords("A").stream().noneMatch(r -> r.sequenceKey() == 4));
        assertEquals(890, server.store().readAccount("A").orElseThrow().balance());
    }

    @Test
    @DisplayName("FR-008 / FR-006: a late copy of a key refused for insufficient funds replays INSUFFICIENT_FUNDS")
    void lateCopyOfRefusalReplaysRefusal() {
        AttemptResult.Final refused = debit(8, 5000, false);
        debit(9, 100, false);

        AttemptResult.Final late = debit(8, 5000, true);

        assertEquals(422, refused.status());
        assertEquals(422, late.status());
        assertEquals(Outcome.INSUFFICIENT_FUNDS, late.answer().outcome());
        assertTrue(late.answer().duplicate());
        assertEquals(refused.answer().balanceAfter(), late.answer().balanceAfter());
        assertEquals(900, server.store().readAccount("A").orElseThrow().balance());
    }
}
