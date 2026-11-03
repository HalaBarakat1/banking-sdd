package ds.banking.server.fault;

import ds.banking.protocol.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chooses which fault, if any, a request suffers (FR-028 to FR-030; research R5).
 *
 * <p>A request triggers at most one scenario. First the {@code NEXT_N} rules that match the request
 * are checked in list order, and the first one with count left fires. Otherwise one number
 * {@code u ∈ [0, 1)} is drawn and the matching {@code PROBABILITY} rules are laid side by side; the rule
 * whose slice contains {@code u} fires. With the default empty configuration nothing ever fires
 * (FR-030). The injector only chooses; it never touches the decision rules (NFR-005).
 *
 * <p>Thread safety: the configuration and its counters are swapped as one immutable value in an
 * {@link AtomicReference}; {@code NEXT_N} counters are {@link AtomicInteger}s, and draw {@code n}
 * comes from an {@link AtomicLong} as {@code new SplittableRandom(seed + n).nextDouble()}, so there is
 * no shared {@code Random}. Draws are exactly reproducible for requests that arrive one at a time; under
 * concurrency only the fault rate is reproducible (constitution Principle III).
 */
public final class FaultInjector {

    /** A configuration together with its live counters. */
    private record Armed(FaultConfig config, List<AtomicInteger> remaining, long seed, AtomicLong draws) {
    }

    private final AtomicReference<Armed> armed = new AtomicReference<>(arm(FaultConfig.empty()));

    private static Armed arm(FaultConfig config) {
        List<AtomicInteger> remaining = new ArrayList<>();
        for (FaultRule r : config.rules()) {
            remaining.add(new AtomicInteger(r.trigger() == FaultTrigger.NEXT_N ? r.count() : 0));
        }
        long seed = config.seed() != null ? config.seed() : ThreadLocalRandom.current().nextLong();
        return new Armed(config, List.copyOf(remaining), seed, new AtomicLong());
    }

    /** Replaces the rules and their counters as a whole. */
    public void configure(FaultConfig config) {
        armed.set(arm(config));
    }

    public FaultConfig config() {
        return armed.get().config();
    }

    /** Chooses the fault for one request, or empty for none. Called once per request. */
    public Optional<FaultRule> choose(String accountId, Operation operation) {
        Armed a = armed.get();
        List<FaultRule> rules = a.config().rules();
        boolean anyProbability = false;
        for (int i = 0; i < rules.size(); i++) {
            FaultRule r = rules.get(i);
            if (!r.matches(accountId, operation)) {
                continue;
            }
            if (r.trigger() == FaultTrigger.NEXT_N) {
                if (a.remaining().get(i).getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                    return Optional.of(r);
                }
            } else {
                anyProbability = true;
            }
        }
        if (!anyProbability) {
            return Optional.empty();
        }
        double u = new SplittableRandom(a.seed() + a.draws().getAndIncrement()).nextDouble();
        double upper = 0;
        for (FaultRule r : rules) {
            if (r.trigger() == FaultTrigger.PROBABILITY && r.matches(accountId, operation)) {
                upper += r.probability();
                if (u < upper) {
                    return Optional.of(r);
                }
            }
        }
        return Optional.empty();
    }
}
