package ds.banking.support;

import ds.banking.protocol.Outcome;
import ds.banking.server.store.AccountRow;
import ds.banking.server.store.LedgerStore;
import ds.banking.server.store.RecordRow;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Money invariant checks used by fault, integration and soak tests (spec "Money Invariant", SC-001,
 * research R9). Each method throws {@link AssertionError} with an explanation when the state is wrong.
 */
public final class Invariants {

    private Invariants() {
    }

    /** Checks the server invariant for one account, reading it from the store. */
    public static void check(LedgerStore store, String accountId) {
        AccountRow account = store.readAccount(accountId)
                .orElseThrow(() -> new AssertionError("no account " + accountId));
        check(account, store.readRecords(accountId));
    }

    /**
     * Server invariant: every key has at most one record,
     * {@code balance = initial_balance − SUM(amount WHERE outcome = 'EXECUTED')}, and
     * {@code last_sequence_key = MAX(sequence_key)} (0 without records).
     */
    public static void check(AccountRow account, List<RecordRow> records) {
        Set<Long> keys = new HashSet<>();
        long executed = 0;
        long maxKey = 0;
        for (RecordRow r : records) {
            if (!r.accountId().equals(account.accountId())) {
                throw new AssertionError("record of " + r.accountId() + " listed under " + account.accountId());
            }
            if (!keys.add(r.sequenceKey())) {
                throw new AssertionError("key " + r.sequenceKey() + " of " + account.accountId()
                        + " has more than one record (double decision)");
            }
            if (r.outcome() == Outcome.EXECUTED) {
                executed += r.amount();
            }
            maxKey = Math.max(maxKey, r.sequenceKey());
        }
        long expectedBalance = account.initialBalance() - executed;
        if (account.balance() != expectedBalance) {
            throw new AssertionError("balance of " + account.accountId() + " is " + account.balance()
                    + " but initial " + account.initialBalance() + " - executed " + executed + " = " + expectedBalance);
        }
        if (account.lastSequenceKey() != maxKey) {
            throw new AssertionError("last_sequence_key of " + account.accountId() + " is "
                    + account.lastSequenceKey() + " but the highest decided key is " + maxKey);
        }
    }

    /**
     * Client/server cross-check: every {@code SUCCEEDED} entry has exactly one {@code EXECUTED} record
     * with the same key and amount, no {@code NOT_SUCCEEDED} entry has one, and every {@code EXECUTED}
     * record belongs to exactly one entry.
     */
    public static void crossCheck(List<ExpectedDebit> expected, List<RecordRow> records) {
        Map<String, RecordRow> executedByKey = new HashMap<>();
        for (RecordRow r : records) {
            if (r.outcome() == Outcome.EXECUTED
                    && executedByKey.put(id(r.accountId(), r.sequenceKey()), r) != null) {
                throw new AssertionError("key " + id(r.accountId(), r.sequenceKey()) + " executed twice");
            }
        }
        Set<String> claimed = new HashSet<>();
        for (ExpectedDebit e : expected) {
            String id = id(e.accountId(), e.sequenceKey());
            if (!claimed.add(id)) {
                throw new AssertionError("key " + id + " is claimed by two client entries");
            }
            RecordRow record = executedByKey.get(id);
            switch (e.view()) {
                case SUCCEEDED -> {
                    if (record == null) {
                        throw new AssertionError("client reports " + id + " executed, the server has no execution");
                    }
                    if (record.amount() != e.amount()) {
                        throw new AssertionError("client reports " + id + " executed for " + e.amount()
                                + ", the server debited " + record.amount());
                    }
                }
                case NOT_SUCCEEDED -> {
                    if (record != null) {
                        throw new AssertionError("client reports " + id + " not executed, the server executed it");
                    }
                }
                case UNKNOWN -> {
                    // Either outcome is allowed; "at most once" is already enforced above.
                }
            }
        }
        for (String id : executedByKey.keySet()) {
            if (!claimed.contains(id)) {
                throw new AssertionError("the server executed " + id + " but no client entry knows it");
            }
        }
    }

    private static String id(String accountId, long key) {
        return accountId + "#" + key;
    }
}
