package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import ds.banking.server.store.AccountRow;
import ds.banking.server.store.RecordRow;
import ds.banking.support.ExpectedDebit;
import ds.banking.support.ExpectedDebit.ClientView;
import ds.banking.support.Invariants;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The test-side invariant checks must catch broken states (T010; Money Invariant, SC-001). */
@Tag("unit")
class InvariantsTest {

    private static RecordRow executed(long key, long amount, long balanceAfter) {
        return new RecordRow("A", key, Operation.DEBIT, amount, Outcome.EXECUTED, balanceAfter, "t");
    }

    private static RecordRow refused(long key, long amount, long balanceAfter) {
        return new RecordRow("A", key, Operation.DEBIT, amount, Outcome.INSUFFICIENT_FUNDS, balanceAfter, "t");
    }

    private static RecordRow cancelled(long key, long balanceAfter) {
        return new RecordRow("A", key, Operation.CANCEL, null, Outcome.CANCELLED, balanceAfter, "t");
    }

    private final List<RecordRow> records = List.of(executed(1, 100, 900), refused(2, 5000, 900),
            cancelled(4, 900), executed(5, 50, 850));

    @Test
    @DisplayName("SC-001: a correct account passes the money invariant check")
    void correctAccountPasses() {
        assertDoesNotThrow(() -> Invariants.check(new AccountRow("A", 1000, 850, 5), records));
        assertDoesNotThrow(() -> Invariants.check(new AccountRow("A", 1000, 1000, 0), List.of()));
    }

    @Test
    @DisplayName("SC-001: a balance that does not equal initial balance minus executed debits is caught")
    void balanceMismatchCaught() {
        assertThrows(AssertionError.class, () -> Invariants.check(new AccountRow("A", 1000, 800, 5), records));
    }

    @Test
    @DisplayName("SC-001: two records for one key (a double debit) are caught")
    void twoRecordsForOneKeyCaught() {
        List<RecordRow> doubled = List.of(executed(1, 100, 900), executed(1, 100, 800));

        assertThrows(AssertionError.class, () -> Invariants.check(new AccountRow("A", 1000, 800, 1), doubled));
    }

    @Test
    @DisplayName("SC-001: a last_sequence_key that is not the highest decided key is caught")
    void lastKeyMismatchCaught() {
        assertThrows(AssertionError.class, () -> Invariants.check(new AccountRow("A", 1000, 850, 4), records));
        assertThrows(AssertionError.class, () -> Invariants.check(new AccountRow("A", 1000, 1000, 3), List.of()));
    }

    @Test
    @DisplayName("SC-001: the client's view matching the server's records passes the cross-check")
    void matchingViewsPass() {
        List<ExpectedDebit> expected = List.of(
                new ExpectedDebit("A", 1, 100, ClientView.SUCCEEDED),
                new ExpectedDebit("A", 2, 5000, ClientView.NOT_SUCCEEDED),
                new ExpectedDebit("A", 4, 70, ClientView.NOT_SUCCEEDED),
                new ExpectedDebit("A", 5, 50, ClientView.UNKNOWN),
                new ExpectedDebit("A", 6, 10, ClientView.UNKNOWN));

        assertDoesNotThrow(() -> Invariants.crossCheck(expected, records));
    }

    @Test
    @DisplayName("SC-001: the client reports success but the server never executed the debit")
    void successWithoutRecordCaught() {
        List<ExpectedDebit> expected = List.of(
                new ExpectedDebit("A", 1, 100, ClientView.SUCCEEDED),
                new ExpectedDebit("A", 2, 5000, ClientView.SUCCEEDED),
                new ExpectedDebit("A", 5, 50, ClientView.SUCCEEDED));

        assertThrows(AssertionError.class, () -> Invariants.crossCheck(expected, records));
    }

    @Test
    @DisplayName("SC-001: the client reports success for a different amount than the server debited")
    void amountMismatchCaught() {
        List<ExpectedDebit> expected = List.of(
                new ExpectedDebit("A", 1, 999, ClientView.SUCCEEDED),
                new ExpectedDebit("A", 5, 50, ClientView.SUCCEEDED));

        assertThrows(AssertionError.class, () -> Invariants.crossCheck(expected, records));
    }

    @Test
    @DisplayName("SC-001: the server executed a debit the client does not know about")
    void executedWithoutEntryCaught() {
        List<ExpectedDebit> expected = List.of(new ExpectedDebit("A", 1, 100, ClientView.SUCCEEDED));

        assertThrows(AssertionError.class, () -> Invariants.crossCheck(expected, records));
    }

    @Test
    @DisplayName("SC-001: the client reports not-executed but the server executed the debit")
    void notSucceededButExecutedCaught() {
        List<ExpectedDebit> expected = List.of(
                new ExpectedDebit("A", 1, 100, ClientView.NOT_SUCCEEDED),
                new ExpectedDebit("A", 5, 50, ClientView.SUCCEEDED));

        assertThrows(AssertionError.class, () -> Invariants.crossCheck(expected, records));
    }

    @Test
    @DisplayName("SC-001: one key claimed by two client entries is caught")
    void keyClaimedTwiceCaught() {
        List<ExpectedDebit> expected = List.of(
                new ExpectedDebit("A", 1, 100, ClientView.SUCCEEDED),
                new ExpectedDebit("A", 1, 100, ClientView.SUCCEEDED),
                new ExpectedDebit("A", 5, 50, ClientView.SUCCEEDED));

        assertThrows(AssertionError.class, () -> Invariants.crossCheck(expected, records));
    }
}
