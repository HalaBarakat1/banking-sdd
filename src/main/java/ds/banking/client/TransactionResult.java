package ds.banking.client;

import ds.banking.protocol.Outcome;

/**
 * What the client tells its caller about one transaction (data-model.md, client-side result table;
 * FR-023, FR-024, FR-025). Immutable.
 *
 * @param result       the client's conclusion
 * @param accountId    account
 * @param sequenceKey  key used; {@code null} if the transaction was refused locally
 * @param outcome      server outcome of the final answer; {@code null} if there was none
 * @param duplicate    whether the final answer replayed an earlier decision
 * @param balanceAfter balance from the final answer, when it had one
 * @param attempts     attempts sent in this run
 * @param message      human-readable explanation
 */
public record TransactionResult(
        Result result,
        String accountId,
        Long sequenceKey,
        Outcome outcome,
        Boolean duplicate,
        Long balanceAfter,
        int attempts,
        String message) {

    /** The client's conclusion about a transaction. */
    public enum Result {
        /** The money was debited exactly once. */
        SUCCEEDED,
        /** The server refused the request (for example insufficient funds); no money moved. */
        REJECTED,
        /** The debit did not and will never happen (key cancelled or stale). */
        NOT_EXECUTED,
        /** The cancel took effect; no money moved. */
        CANCELLED,
        /** Retries of this run were exhausted; the outcome is not known yet (FR-024). */
        UNKNOWN,
        /** Refused locally, nothing sent (FR-025, EC-009). */
        REFUSED
    }

    static TransactionResult refused(String accountId, String message) {
        return new TransactionResult(Result.REFUSED, accountId, null, null, null, null, 0, message);
    }
}
