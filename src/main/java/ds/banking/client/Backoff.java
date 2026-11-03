package ds.banking.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Wait between attempts: exponential growth with "equal jitter" (FR-022, research R7).
 *
 * <p>After {@code n} attempts, {@code d = min(maxBackoff, baseBackoff × 2^(n−1))} and the wait is
 * {@code d/2 + random × d/2}: it grows exponentially, never drops to zero, and spreads the retries of
 * many clients. The random source is injected so tests are repeatable.
 *
 * <p>Thread safety: immutable; {@link ThreadLocalRandom} is safe per thread.
 */
public final class Backoff {

    private final RetryPolicy policy;
    private final DoubleSupplier random;

    /** @param random values in {@code [0, 1)} */
    public Backoff(RetryPolicy policy, DoubleSupplier random) {
        this.policy = policy;
        this.random = random;
    }

    public static Backoff withRandomJitter(RetryPolicy policy) {
        return new Backoff(policy, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** Wait before the next attempt, after {@code attemptsMade} (≥ 1) attempts. */
    public Duration delayAfter(int attemptsMade) {
        if (attemptsMade < 1) {
            throw new IllegalArgumentException("attemptsMade must be >= 1: " + attemptsMade);
        }
        long base = policy.baseBackoff().toNanos();
        long max = policy.maxBackoff().toNanos();
        int doublings = attemptsMade - 1;
        // Stop doubling once the cap is reached, so large attempt numbers cannot overflow.
        long d = base;
        for (int i = 0; i < doublings && d < max; i++) {
            d = d > max / 2 ? max : d * 2;
        }
        d = Math.min(d, max);
        long half = d / 2;
        return Duration.ofNanos(half + (long) (random.getAsDouble() * (d - half)));
    }
}
