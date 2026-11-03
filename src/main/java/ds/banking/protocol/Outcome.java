package ds.banking.protocol;

/**
 * Every outcome a debit or cancel answer can carry.
 *
 * <p>Implements: FR-003 (the exact outcome set), FR-004 (retry class). The HTTP status of an outcome
 * depends on the operation as well, so it lives in {@code server.http.StatusMapper}, not here
 * (research R4).
 */
public enum Outcome {
    EXECUTED,
    INSUFFICIENT_FUNDS,
    CANCELLED,
    STALE_KEY,
    KEY_CONFLICT,
    INVALID_REQUEST,
    UNKNOWN_ACCOUNT,
    UNAVAILABLE,
    ALREADY_EXECUTED,
    ALREADY_REJECTED;

    /** True when the client should resend with the same key; every other outcome is final. */
    public boolean retryable() {
        return this == UNAVAILABLE;
    }
}
