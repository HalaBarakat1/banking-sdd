package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.protocol.Answer;
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

/** Debit rows 1-6 of the data-model decision table (T020; FR-005..FR-011, FR-014). */
@Tag("unit")
class DecisionServiceDebitTest {

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

    private Answer debit(long key, long amount, boolean isRetry) {
        return decisions.debit("A", new DebitRequest(key, amount, isRetry));
    }

    private AccountRow account() {
        return store.readAccount("A").orElseThrow();
    }

    @Test
    @DisplayName("FR-006 / FR-007: a new key with enough funds executes, debits once and sets the last key")
    void newKeyExecutes() {
        Answer answer = debit(1, 100, false);

        assertEquals(new Answer("A", 1L, Outcome.EXECUTED, false, 100L, 900L, answer.message()), answer);
        assertEquals(new AccountRow("A", 1000, 900, 1), account());
        List<RecordRow> records = store.readRecords("A");
        assertEquals(1, records.size());
        assertEquals(Operation.DEBIT, records.get(0).operation());
        assertEquals(Outcome.EXECUTED, records.get(0).outcome());
        assertEquals(900, records.get(0).balanceAfter());
        Invariants.check(store, "A");
    }

    @Test
    @DisplayName("FR-008: a copy of a decided key replays the same answer with duplicate=true and writes nothing")
    void duplicateReplays() {
        Answer first = debit(1, 100, false);
        List<RecordRow> recordsBefore = store.readRecords("A");

        Answer copy = debit(1, 100, true);

        assertEquals(Outcome.EXECUTED, copy.outcome());
        assertTrue(copy.duplicate());
        assertEquals(first.amount(), copy.amount());
        assertEquals(first.balanceAfter(), copy.balanceAfter());
        assertEquals(new AccountRow("A", 1000, 900, 1), account());
        assertEquals(recordsBefore, store.readRecords("A"));
    }

    @Test
    @DisplayName("FR-006: insufficient funds consumes the key, moves no money, and every copy gets the same answer")
    void insufficientFundsConsumesKey() {
        Answer refused = debit(1, 5000, false);
        Answer copy = debit(1, 5000, true);

        assertEquals(new Answer("A", 1L, Outcome.INSUFFICIENT_FUNDS, false, 5000L, 1000L, refused.message()), refused);
        assertEquals(Outcome.INSUFFICIENT_FUNDS, copy.outcome());
        assertTrue(copy.duplicate());
        assertEquals(1000L, copy.balanceAfter());
        assertEquals(new AccountRow("A", 1000, 1000, 1), account());
        assertEquals(Outcome.INSUFFICIENT_FUNDS, store.readRecords("A").get(0).outcome());
        Invariants.check(store, "A");
    }

    @Test
    @DisplayName("FR-006: a key greater than last + 1 is new (gaps are allowed)")
    void gapKeyIsNew() {
        debit(1, 100, false);

        Answer answer = debit(5, 100, false);

        assertEquals(Outcome.EXECUTED, answer.outcome());
        assertEquals(5, account().lastSequenceKey());
        Invariants.check(store, "A");
    }

    @Test
    @DisplayName("FR-009: an undecided key below the last key is STALE_KEY, moves no money and stays undecided")
    void staleKeyRejected() {
        debit(5, 100, false);

        Answer stale = debit(3, 100, false);
        Answer again = debit(3, 100, true);

        assertEquals(Outcome.STALE_KEY, stale.outcome());
        assertFalse(stale.duplicate());
        assertEquals(Outcome.STALE_KEY, again.outcome());
        assertEquals(new AccountRow("A", 1000, 900, 5), account());
        assertEquals(1, store.readRecords("A").size());
    }

    @Test
    @DisplayName("FR-011 / EC-003: a debit copy with a different amount is KEY_CONFLICT and changes nothing")
    void differentAmountIsConflict() {
        debit(1, 100, false);

        Answer conflict = debit(1, 999, true);

        assertEquals(Outcome.KEY_CONFLICT, conflict.outcome());
        assertFalse(conflict.duplicate());
        assertEquals(new AccountRow("A", 1000, 900, 1), account());
        assertEquals(100L, store.readRecords("A").get(0).amount());
    }

    @Test
    @DisplayName("EC-005 / FR-012: an unknown account answers UNKNOWN_ACCOUNT and writes nothing")
    void unknownAccount() {
        Answer answer = decisions.debit("NOBODY", new DebitRequest(1L, 10L, false));

        assertEquals(Outcome.UNKNOWN_ACCOUNT, answer.outcome());
        assertNull(answer.amount());
        assertTrue(store.readAccount("NOBODY").isEmpty());
        assertTrue(store.readRecords("NOBODY").isEmpty());
    }

    @Test
    @DisplayName("EC-006 / FR-014: a debit of the whole balance executes and leaves exactly 0; one more cent is refused")
    void wholeBalance() {
        Answer all = debit(1, 1000, false);
        Answer more = debit(2, 1, false);

        assertEquals(Outcome.EXECUTED, all.outcome());
        assertEquals(0L, all.balanceAfter());
        assertEquals(Outcome.INSUFFICIENT_FUNDS, more.outcome());
        assertEquals(0, account().balance());
        Invariants.check(store, "A");
    }

    @Test
    @DisplayName("EC-001 / FR-010: a first-ever key marked is_retry=true is still new and executes")
    void retryFlagOnNewKeyExecutes() {
        Answer answer = debit(1, 100, true);

        assertEquals(Outcome.EXECUTED, answer.outcome());
        assertFalse(answer.duplicate());
        assertEquals(900, account().balance());
    }

    @Test
    @DisplayName("EC-002 / FR-010: a decided key marked is_retry=false is still a duplicate")
    void notRetryFlagOnDecidedKeyIsDuplicate() {
        debit(1, 100, false);

        Answer answer = debit(1, 100, false);

        assertEquals(Outcome.EXECUTED, answer.outcome());
        assertTrue(answer.duplicate());
        assertEquals(900, account().balance());
    }
}
