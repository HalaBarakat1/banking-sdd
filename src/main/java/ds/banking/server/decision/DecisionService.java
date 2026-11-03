package ds.banking.server.decision;

import ds.banking.protocol.Answer;
import ds.banking.protocol.CancelRequest;
import ds.banking.protocol.DebitRequest;
import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import ds.banking.server.store.AccountRow;
import ds.banking.server.store.LedgerStore;
import ds.banking.server.store.RecordRow;
import java.time.Instant;
import java.util.Optional;

/**
 * The decision table of data-model.md: whether a request is new, a duplicate, stale or a conflict, and
 * what happens to the money.
 *
 * <p>Implements: FR-005 (state per account and per decided key), FR-006 (a new key executes or is
 * refused, and either way becomes the last key), FR-007 (check, balance change, last-key update and
 * record in one {@code BEGIN IMMEDIATE} transaction), FR-008 (a decided key replays its record),
 * FR-009 (an undecided key below the last key is stale and can never execute), FR-010 (the
 * {@code is_retry} flag is not read here at all), FR-011 (a different amount is a conflict), FR-013
 * (the answer is returned only after commit), FR-014 (never a negative balance), FR-015 to FR-018 (cancel
 * burns a key so no copy of its debit can ever execute).
 *
 * <p>Thread safety: no fields besides the stateless store. Concurrent calls for the same account are
 * serialized by the database write lock that {@code BEGIN IMMEDIATE} takes before the first read, so
 * no two requests can both see the same key as new (constitution Principles II and III).
 */
public final class DecisionService implements Decider {

    private final LedgerStore store;

    public DecisionService(LedgerStore store) {
        this.store = store;
    }

    @Override
    public Answer debit(String accountId, DebitRequest request) {
        long key = request.sequenceKey();
        long amount = request.amount();
        return store.inImmediateTransaction(tx -> {
            // Row 1: unknown account. Not a decision: nothing is written (FR-012).
            Optional<AccountRow> found = tx.account(accountId);
            if (found.isEmpty()) {
                return answer(accountId, key, Outcome.UNKNOWN_ACCOUNT, false, null, null, "no account " + accountId);
            }
            AccountRow account = found.get();

            Optional<RecordRow> decided = tx.record(accountId, key);
            if (decided.isPresent()) {
                RecordRow record = decided.get();
                // Row 2: same key, different amount (FR-011). The original decision stands.
                if (record.operation() == Operation.DEBIT && record.amount() != amount) {
                    return answer(accountId, key, Outcome.KEY_CONFLICT, false, null, null,
                            "key " + key + " was decided for amount " + record.amount());
                }
                // Row 3: replay the recorded decision (FR-008).
                return replay(record);
            }

            // Row 4: undecided and not above the last key (FR-009).
            if (key <= account.lastSequenceKey()) {
                return answer(accountId, key, Outcome.STALE_KEY, false, null, null,
                        "key " + key + " is not above the last key " + account.lastSequenceKey());
            }

            // Rows 5 and 6: a new key. Either way the key is consumed (FR-006).
            boolean enough = account.balance() >= amount;
            long balanceAfter = enough ? account.balance() - amount : account.balance();
            Outcome outcome = enough ? Outcome.EXECUTED : Outcome.INSUFFICIENT_FUNDS;
            tx.updateAccount(accountId, balanceAfter, key);
            tx.insertRecord(new RecordRow(accountId, key, Operation.DEBIT, amount, outcome, balanceAfter,
                    Instant.now().toString()));
            return answer(accountId, key, outcome, false, amount, balanceAfter, message(outcome));
        });
    }

    private static Answer replay(RecordRow record) {
        if (record.operation() == Operation.CANCEL) {
            return answer(record.accountId(), record.sequenceKey(), Outcome.CANCELLED, true, null, null,
                    "key was cancelled");
        }
        return answer(record.accountId(), record.sequenceKey(), record.outcome(), true, record.amount(),
                record.balanceAfter(), message(record.outcome()));
    }

    /**
     * Cancel rows of the decision table (FR-015 to FR-018): a new key is burnt as {@code CANCELLED}
     * without moving money, so no copy of its debit can ever execute; a decided key is answered from its
     * record ({@code ALREADY_EXECUTED}, {@code ALREADY_REJECTED} or {@code CANCELLED}, all replays); a
     * stale undecided key is {@code STALE_KEY}. Same single {@code BEGIN IMMEDIATE} transaction as debit.
     */
    @Override
    public Answer cancel(String accountId, CancelRequest request) {
        long key = request.sequenceKey();
        return store.inImmediateTransaction(tx -> {
            Optional<AccountRow> found = tx.account(accountId);
            if (found.isEmpty()) {
                return answer(accountId, key, Outcome.UNKNOWN_ACCOUNT, false, null, null, "no account " + accountId);
            }
            AccountRow account = found.get();

            Optional<RecordRow> decided = tx.record(accountId, key);
            if (decided.isPresent()) {
                RecordRow record = decided.get();
                return switch (record.outcome()) {
                    case EXECUTED -> answer(accountId, key, Outcome.ALREADY_EXECUTED, true, record.amount(),
                            record.balanceAfter(), "too late: the debit was already executed");
                    case INSUFFICIENT_FUNDS -> answer(accountId, key, Outcome.ALREADY_REJECTED, true, record.amount(),
                            record.balanceAfter(), "the debit was refused; nothing will be debited");
                    case CANCELLED -> answer(accountId, key, Outcome.CANCELLED, true, null, null, "key was cancelled");
                    default -> throw new IllegalStateException("record with outcome " + record.outcome());
                };
            }

            if (key <= account.lastSequenceKey()) {
                return answer(accountId, key, Outcome.STALE_KEY, false, null, null,
                        "key " + key + " is not above the last key " + account.lastSequenceKey()
                                + "; it never executed and never will");
            }

            tx.updateAccount(accountId, account.balance(), key);
            tx.insertRecord(new RecordRow(accountId, key, Operation.CANCEL, null, Outcome.CANCELLED,
                    account.balance(), Instant.now().toString()));
            return answer(accountId, key, Outcome.CANCELLED, false, null, null, "key cancelled");
        });
    }

    private static String message(Outcome outcome) {
        return switch (outcome) {
            case EXECUTED -> "debit executed";
            case INSUFFICIENT_FUNDS -> "insufficient funds";
            default -> outcome.name().toLowerCase();
        };
    }

    private static Answer answer(String accountId, long key, Outcome outcome, boolean duplicate, Long amount,
            Long balanceAfter, String message) {
        return new Answer(accountId, key, outcome, duplicate, amount, balanceAfter, message);
    }
}
