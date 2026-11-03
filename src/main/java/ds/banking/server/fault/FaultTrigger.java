package ds.banking.server.fault;

/** When a fault rule fires (FR-029; data-model.md "Fault configuration"). */
public enum FaultTrigger {
    /** For the next {@code count} matching requests. */
    NEXT_N,
    /** For each matching request with probability {@code probability}. */
    PROBABILITY
}
