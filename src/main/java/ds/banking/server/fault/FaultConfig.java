package ds.banking.server.fault;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ds.banking.protocol.InvalidRequestException;
import ds.banking.protocol.Json;
import ds.banking.protocol.Operation;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The whole fault configuration: an ordered list of rules and an optional seed for the probability
 * draws (data-model.md "Fault configuration"; FR-029). Empty by default (FR-030). Validated as a whole
 * by the constructor, so an invalid configuration is never half-applied (EC-012). Immutable.
 *
 * @param rules ordered rules
 * @param seed  seed of the draw sequence for PROBABILITY rules; {@code null} picks one at random
 */
public record FaultConfig(List<FaultRule> rules, Long seed) {

    private static final double EPSILON = 1e-9;

    public FaultConfig {
        if (rules == null) {
            throw new IllegalArgumentException("rules is required");
        }
        rules = List.copyOf(rules);
        checkProbabilities(rules);
    }

    public static FaultConfig empty() {
        return new FaultConfig(List.of(), null);
    }

    /** Parses and validates {@code {"rules": [...], "seed": 42}}; anything invalid is refused whole. */
    public static FaultConfig parse(byte[] body) throws InvalidRequestException {
        return Json.parse(body, FaultConfig.class);
    }

    /**
     * Rules that can match the same request must have probabilities adding up to at most 1, so one
     * draw can choose at most one of them and each fires with exactly its own probability (research R5).
     */
    private static void checkProbabilities(List<FaultRule> rules) {
        Set<String> accounts = new HashSet<>();
        for (FaultRule r : rules) {
            if (r.trigger() == FaultTrigger.PROBABILITY && r.accountId() != null) {
                accounts.add(r.accountId());
            }
        }
        accounts.add(null); // stands for "any other account"
        for (String account : accounts) {
            for (Operation op : Operation.values()) {
                double sum = 0;
                for (FaultRule r : rules) {
                    boolean accountMatches = r.accountId() == null || r.accountId().equals(account);
                    boolean opMatches = r.operation() == null || r.operation() == op;
                    if (r.trigger() == FaultTrigger.PROBABILITY && accountMatches && opMatches) {
                        sum += r.probability();
                    }
                }
                if (sum > 1 + EPSILON) {
                    throw new IllegalArgumentException("probabilities of rules matching account "
                            + (account == null ? "(any)" : account) + " / " + op + " add up to " + sum + " > 1");
                }
            }
        }
    }

    /** JSON body for {@code GET /admin/faults} (contracts/http-api.md §4). */
    public ObjectNode toJson() {
        ObjectNode node = Json.mapper().createObjectNode();
        ArrayNode list = node.putArray("rules");
        for (FaultRule r : rules) {
            ObjectNode rule = list.addObject();
            rule.put("scenario", r.scenario().name());
            rule.put("trigger", r.trigger().name());
            if (r.count() != null) {
                rule.put("count", r.count());
            }
            if (r.probability() != null) {
                rule.put("probability", r.probability());
            }
            if (r.accountId() != null) {
                rule.put("account_id", r.accountId());
            }
            if (r.operation() != null) {
                rule.put("operation", r.operation().name());
            }
            if (r.delayMs() != null) {
                rule.put("delay_ms", r.delayMs());
            }
            if (r.downtimeMs() != null) {
                rule.put("downtime_ms", r.downtimeMs());
            }
        }
        if (seed != null) {
            node.put("seed", seed);
        }
        return node;
    }
}
