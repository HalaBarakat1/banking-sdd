package ds.banking.client;

import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;

/**
 * One row of the client journal (data-model.md; FR-020). Immutable snapshot.
 *
 * @param sequenceKey  key reserved for this transaction
 * @param accountId    account
 * @param operation    DEBIT, or CANCEL after a switch (FR-027)
 * @param amount       amount of the debit
 * @param state        PENDING, UNKNOWN or RESOLVED
 * @param outcome      final outcome once RESOLVED; {@code null} before, or for an unparseable 4xx
 * @param balanceAfter balance from the answer, when it had one
 * @param attempts     attempts of the current operation; decides {@code is_retry} only (FR-021)
 * @param createdAt    ISO-8601 UTC, for logs
 * @param updatedAt    ISO-8601 UTC, for logs
 */
public record JournalEntry(
        long sequenceKey,
        String accountId,
        Operation operation,
        long amount,
        JournalState state,
        Outcome outcome,
        Long balanceAfter,
        int attempts,
        String createdAt,
        String updatedAt) {
}
