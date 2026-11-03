package ds.banking.server.fault;

/**
 * The injectable failure scenarios (FR-028; spec FS-1 to FS-4b; research R5).
 *
 * <p>The "before" scenarios act before the decision is made, the "after" scenarios act after it has
 * been committed.
 */
public enum FaultScenario {
    /** FS-1: the request is lost; the server closes the connection without deciding or answering. */
    DROP_REQUEST(true),
    /** FS-2: the server stops after receiving the request and before executing it, then restarts. */
    CRASH_BEFORE_EXECUTE(true),
    /** FS-3: the decision is committed, then the response is not delivered. */
    DROP_RESPONSE(false),
    /** FS-4a: the server waits {@code delay_ms} before deciding. */
    DELAY_BEFORE_EXECUTE(true),
    /** FS-4b: the server decides and commits, then waits {@code delay_ms} before answering. */
    DELAY_AFTER_EXECUTE(false);

    private final boolean beforeDecision;

    FaultScenario(boolean beforeDecision) {
        this.beforeDecision = beforeDecision;
    }

    /** True if the scenario acts before the decision, false if after the commit. */
    public boolean beforeDecision() {
        return beforeDecision;
    }

    /** True for the two delay scenarios, which require {@code delay_ms}. */
    public boolean isDelay() {
        return this == DELAY_BEFORE_EXECUTE || this == DELAY_AFTER_EXECUTE;
    }
}
