package ds.banking.client;

import java.time.Duration;

/**
 * The client's retry limits (FR-022, NFR-003, research R7). All values are configurable from the
 * command line (contracts/client-cli.md). Immutable.
 *
 * @param connectTimeout  time limit to open a connection, per attempt
 * @param responseTimeout time limit to receive the response, per attempt
 * @param maxAttempts     attempts per send run
 * @param maxTotal        total time per send run
 * @param baseBackoff     first wait between attempts
 * @param maxBackoff      longest wait between attempts
 */
public record RetryPolicy(
        Duration connectTimeout,
        Duration responseTimeout,
        int maxAttempts,
        Duration maxTotal,
        Duration baseBackoff,
        Duration maxBackoff) {

    public RetryPolicy {
        requirePositive("connectTimeout", connectTimeout);
        requirePositive("responseTimeout", responseTimeout);
        requirePositive("maxTotal", maxTotal);
        requirePositive("baseBackoff", baseBackoff);
        requirePositive("maxBackoff", maxBackoff);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }
        if (maxBackoff.compareTo(baseBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must be >= baseBackoff");
        }
    }

    /** Defaults of NFR-003. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2), 6, Duration.ofSeconds(30),
                Duration.ofMillis(100), Duration.ofSeconds(2));
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
