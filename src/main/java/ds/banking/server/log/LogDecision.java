package ds.banking.server.log;

import ds.banking.protocol.Outcome;

/**
 * What one request did, for the request log (NFR-004; data-model.md "Log decision per answer").
 * The {@code outcome} field of the log line gives the detail.
 */
public enum LogDecision {
    /** This request wrote a record that executed a debit or burnt a key with a cancel. */
    EXECUTED,
    /** This request replayed an existing record (FR-008, FR-017). */
    DUPLICATE,
    /** This request wrote a refusal record or nothing at all. */
    REJECTED,
    /** An injected fault prevented any decision (FS-1, FS-2). */
    DROPPED;

    /** Decision for a request that was answered (DROPPED is set directly by the fault hooks). */
    public static LogDecision of(Outcome outcome, boolean duplicate) {
        if (duplicate || outcome == Outcome.ALREADY_EXECUTED || outcome == Outcome.ALREADY_REJECTED) {
            return DUPLICATE;
        }
        return outcome == Outcome.EXECUTED || outcome == Outcome.CANCELLED ? EXECUTED : REJECTED;
    }
}
