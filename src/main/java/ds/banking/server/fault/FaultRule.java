package ds.banking.server.fault;

import ds.banking.protocol.AccountIds;
import ds.banking.protocol.Operation;

/**
 * One fault rule (data-model.md "Fault configuration"; FR-028, FR-029). Invalid combinations are
 * refused by the constructor (EC-012). Immutable.
 *
 * @param scenario    which failure to inject
 * @param trigger     NEXT_N or PROBABILITY
 * @param count       for NEXT_N: 1-10,000 requests; otherwise absent
 * @param probability for PROBABILITY: 0 &lt; p ≤ 1; otherwise absent
 * @param accountId   optional: only requests for this account
 * @param operation   optional: only this operation
 * @param delayMs     for DELAY_*: 1-60,000 ms; otherwise absent
 * @param downtimeMs  for CRASH_BEFORE_EXECUTE: 0-60,000 ms, default 1000; otherwise absent
 */
public record FaultRule(
        FaultScenario scenario,
        FaultTrigger trigger,
        Integer count,
        Double probability,
        String accountId,
        Operation operation,
        Integer delayMs,
        Integer downtimeMs) {

    public static final int DEFAULT_DOWNTIME_MS = 1000;

    public FaultRule {
        require(scenario != null, "scenario is required");
        require(trigger != null, "trigger is required");
        if (trigger == FaultTrigger.NEXT_N) {
            require(count != null && count >= 1 && count <= 10_000, "count is required for NEXT_N, 1-10,000");
            require(probability == null, "probability is only allowed for PROBABILITY");
        } else {
            require(probability != null && probability > 0 && probability <= 1,
                    "probability is required for PROBABILITY, 0 < p <= 1");
            require(count == null, "count is only allowed for NEXT_N");
        }
        if (scenario.isDelay()) {
            require(delayMs != null && delayMs >= 1 && delayMs <= 60_000, "delay_ms is required for DELAY_*, 1-60,000");
        } else {
            require(delayMs == null, "delay_ms is only allowed for DELAY_* scenarios");
        }
        if (scenario == FaultScenario.CRASH_BEFORE_EXECUTE) {
            if (downtimeMs == null) {
                downtimeMs = DEFAULT_DOWNTIME_MS;
            }
            require(downtimeMs >= 0 && downtimeMs <= 60_000, "downtime_ms must be 0-60,000");
        } else {
            require(downtimeMs == null, "downtime_ms is only allowed for CRASH_BEFORE_EXECUTE");
        }
        require(accountId == null || AccountIds.isValid(accountId), "account_id must be 1-64 chars of [A-Za-z0-9_-]");
    }

    /** True if this rule applies to a request for {@code account} and {@code op}. */
    public boolean matches(String account, Operation op) {
        return (accountId == null || accountId.equals(account)) && (operation == null || operation == op);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
