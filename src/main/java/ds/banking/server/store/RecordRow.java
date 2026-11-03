package ds.banking.server.store;

import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;

/**
 * One row of {@code transaction_records}: the final decision for one key of one account
 * (data-model.md; FR-005, FR-008). Insert-only and immutable.
 *
 * @param accountId    account
 * @param sequenceKey  decided key
 * @param operation    DEBIT or CANCEL
 * @param amount       debit amount; {@code null} for a cancel
 * @param outcome      EXECUTED, INSUFFICIENT_FUNDS or CANCELLED
 * @param balanceAfter account balance right after this decision
 * @param decidedAt    ISO-8601 UTC time, for logs only (no decision depends on it)
 */
public record RecordRow(
        String accountId,
        long sequenceKey,
        Operation operation,
        Long amount,
        Outcome outcome,
        long balanceAfter,
        String decidedAt) {
}
