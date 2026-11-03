package ds.banking.client;

/** State of a client journal entry (data-model.md, journal state machine; FR-020, FR-024). */
public enum JournalState {
    /** Reserved, possibly sent; the outcome is not known yet. */
    PENDING,
    /** Retries of a run were exhausted; the entry must be resent or cancelled later. */
    UNKNOWN,
    /** A final outcome was received. Terminal. */
    RESOLVED
}
