package ds.banking.client;

import java.time.Duration;

/**
 * The limits of one send run: a command, or one recovery pass over a journal entry.
 *
 * <p>Implements: FR-022 (stop at the maximum number of attempts or the total time, whichever comes
 * first; both start fresh on every run, so an {@code UNKNOWN} entry can always be resent later). The
 * durable attempt counter in the journal is separate and only decides {@code is_retry} (FR-021).
 *
 * <p>Thread safety: used by one thread for one run; not shared.
 */
public final class RetryRun {

    private final RetryPolicy policy;
    private final Clock clock;
    private final long deadline;
    private int attempts;

    public RetryRun(RetryPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
        this.deadline = clock.nanoTime() + policy.maxTotal().toNanos();
    }

    /** True while another attempt is allowed. */
    public boolean mayAttempt() {
        return attempts < policy.maxAttempts() && remaining().toNanos() > 0;
    }

    /** Response timeout for the next attempt, shortened to the time left in this run. */
    public Duration attemptTimeout() {
        Duration remaining = remaining();
        return remaining.compareTo(policy.responseTimeout()) < 0 ? remaining : policy.responseTimeout();
    }

    /** True if waiting {@code wait} still leaves time for an attempt. */
    public boolean waitFits(Duration wait) {
        return remaining().compareTo(wait) > 0;
    }

    public void attempted() {
        attempts++;
    }

    public int attempts() {
        return attempts;
    }

    public Duration remaining() {
        return Duration.ofNanos(Math.max(0, deadline - clock.nanoTime()));
    }
}
