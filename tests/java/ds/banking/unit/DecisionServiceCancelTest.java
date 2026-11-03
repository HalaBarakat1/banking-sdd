package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.protocol.Answer;
import ds.banking.protocol.CancelRequest;
import ds.banking.protocol.DebitRequest;
import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import ds.banking.server.decision.DecisionService;
import ds.banking.server.store.AccountRow;
import ds.banking.server.store.LedgerStore;
import ds.banking.server.store.RecordRow;
import ds.banking.support.Invariants;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cancel rows of the data-model decision table (T049; FR-011, FR-015..FR-018, EC-010). */
@Tag("unit")
class DecisionServiceCancelTest {

    @TempDir
    Path dir;

    private LedgerStore store;
    private DecisionService decisions;

    @BeforeEach
    void setUp() {
        store = new LedgerStore(dir.resolve("bank.db"));
        store.initSchema();
        store.seedAccount("A", 1000);
        decisions = new DecisionService(store);
    }

    private Answer cancel(long key, boolean isRetry) {
        return decisions.cancel("A", new CancelRequest(key, isRetry));
    }

    private Answer debit(long key, long amount) {
        return decisions.debit("A", new DebitRequest(key, amount, false));
    }

    private AccountRow account() {
        return store.readAccount("A").orElseThrow();
    }

    @Test
    @DisplayName("US5-AS1 / FR-016: cancelling a new key records CANCELLED, sets the last key and moves no money")
    void cancelNewKey() {
        Answer answer = cancel(8, false);

        assertEquals(Outcome.CANCELLED, answer.outcome());
        assertFalse(answer.duplicate());
        assertEquals(new AccountRow("A", 1000, 1000, 8), account());
        List<RecordRow> records = store.readRecords("A");
        assertEquals(1, records.size());
        assertEquals(Operation.CANCEL, records.get(0).operation());
        assertNull(records.get(0).amount());
        assertEquals(Outcome.CANCELLED, records.get(0).outcome());
        Invariants.check(store, "A");
    }

    @Test
    @DisplayName("US5-AS4 / FR-017: cancelling an already cancelled key answers CANCELLED again with duplicate=true")
    void cancelTwice() {
        cancel(8, false);

        Answer again = cancel(8, true);

        assertEquals(Outcome.CANCELLED, again.outcome());
        assertTrue(again.duplicate());
        assertEquals(1, store.readRecords("A").size());
    }

    @Test
    @DisplayName("US5-AS3 / FR-017: cancelling an executed key answers ALREADY_EXECUTED with the original amount and balance")
    void cancelExecuted() {
        debit(8, 100);

        Answer late = cancel(8, false);

        assertEquals(Outcome.ALREADY_EXECUTED, late.outcome());
        assertTrue(late.duplicate());
        assertEquals(100L, late.amount());
        assertEquals(900L, late.balanceAfter());
        assertEquals(900, account().balance());
        assertEquals(Outcome.EXECUTED, store.readRecords("A").get(0).outcome());
    }

    @Test
    @DisplayName("EC-010 / FR-017: cancelling a key refused for insufficient funds answers ALREADY_REJECTED; no money moves")
    void cancelRefused() {
        debit(8, 5000);

        Answer late = cancel(8, false);

        assertEquals(Outcome.ALREADY_REJECTED, late.outcome());
        assertTrue(late.duplicate());
        assertEquals(1000, account().balance());
    }

    @Test
    @DisplayName("FR-018: cancelling an undecided key below the last key answers STALE_KEY and writes nothing")
    void cancelStale() {
        debit(8, 100);

        Answer stale = cancel(5, false);

        assertEquals(Outcome.STALE_KEY, stale.outcome());
        assertFalse(stale.duplicate());
        assertEquals(1, store.readRecords("A").size());
        assertEquals(8, account().lastSequenceKey());
    }

    @Test
    @DisplayName("US5-AS2 / FR-011: a late debit copy of a cancelled key replays CANCELLED with duplicate=true, not KEY_CONFLICT")
    void lateDebitOfCancelledKey() {
        cancel(8, false);

        Answer late = debit(8, 100);

        assertEquals(Outcome.CANCELLED, late.outcome());
        assertTrue(late.duplicate());
        assertEquals(1000, account().balance());
        Invariants.check(store, "A");
    }

    @Test
    @DisplayName("FR-015 / EC-005: cancelling on an unknown account answers UNKNOWN_ACCOUNT and writes nothing")
    void cancelUnknownAccount() {
        Answer answer = decisions.cancel("NOBODY", new CancelRequest(1L, false));

        assertEquals(Outcome.UNKNOWN_ACCOUNT, answer.outcome());
        assertTrue(store.readRecords("NOBODY").isEmpty());
    }
}
