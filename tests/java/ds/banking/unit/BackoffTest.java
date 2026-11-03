package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.client.Backoff;
import ds.banking.client.Clock;
import ds.banking.client.RetryPolicy;
import ds.banking.client.RetryRun;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Exponential backoff with equal jitter and the per-run limits (T022; FR-022, NFR-003, research R7). */
@Tag("unit")
class BackoffTest {

    private final RetryPolicy defaults = RetryPolicy.defaults();

    @Test
    @DisplayName("NFR-003: defaults are connect 1 s, response 2 s, 6 attempts, total 30 s, base 100 ms, max 2 s")
    void defaults() {
        assertEquals(new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2), 6, Duration.ofSeconds(30),
                Duration.ofMillis(100), Duration.ofSeconds(2)), defaults);
    }

    @ParameterizedTest(name = "[{index}] after {0} attempts d = {1} ms")
    @CsvSource({"1, 100", "2, 200", "3, 400", "4, 800", "5, 1600", "6, 2000", "7, 2000", "60, 2000", "1000, 2000"})
    @DisplayName("FR-022: the wait is within [d/2, d] with d = min(max, base x 2^(n-1)), capped at 2 s, no overflow")
    void waitIsWithinEqualJitterBounds(int attemptsMade, long dMillis) {
        Duration d = Duration.ofMillis(dMillis);

        Duration low = new Backoff(defaults, () -> 0.0).delayAfter(attemptsMade);
        Duration high = new Backoff(defaults, () -> 0.999999).delayAfter(attemptsMade);
        Duration mid = new Backoff(defaults, () -> 0.5).delayAfter(attemptsMade);

        assertEquals(d.dividedBy(2), low);
        assertTrue(high.compareTo(d) <= 0 && high.compareTo(d.multipliedBy(99).dividedBy(100)) >= 0, high.toString());
        assertEquals(d.multipliedBy(3).dividedBy(4), mid);
    }

    @Test
    @DisplayName("FR-022: the wait is never zero, even with the smallest base")
    void neverZero() {
        RetryPolicy tiny = new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(10), 3, Duration.ofSeconds(1),
                Duration.ofMillis(1), Duration.ofMillis(1));

        assertTrue(new Backoff(tiny, () -> 0.0).delayAfter(1).toNanos() > 0);
    }

    @Test
    @DisplayName("FR-022: a run stops after the maximum number of attempts")
    void stopsAtMaxAttempts() {
        RetryRun run = new RetryRun(defaults, fixedClock(new AtomicLong()));

        for (int i = 0; i < 6; i++) {
            assertTrue(run.mayAttempt(), "attempt " + (i + 1));
            run.attempted();
        }

        assertFalse(run.mayAttempt());
        assertEquals(6, run.attempts());
    }

    @Test
    @DisplayName("FR-022: a run stops at its total deadline, shortens the last timeout, and skips a wait that does not fit")
    void stopsAtDeadline() {
        AtomicLong now = new AtomicLong();
        RetryRun run = new RetryRun(defaults, fixedClock(now));

        assertEquals(Duration.ofSeconds(2), run.attemptTimeout());
        now.set(Duration.ofSeconds(29).toNanos());
        assertTrue(run.mayAttempt());
        assertEquals(Duration.ofSeconds(1), run.attemptTimeout());
        assertTrue(run.waitFits(Duration.ofMillis(500)));
        assertFalse(run.waitFits(Duration.ofMillis(1500)));
        now.set(Duration.ofSeconds(30).toNanos());
        assertFalse(run.mayAttempt());
    }

    @Test
    @DisplayName("FR-022: every run starts fresh limits, whatever earlier runs used")
    void eachRunStartsFresh() {
        AtomicLong now = new AtomicLong();
        RetryRun first = new RetryRun(defaults, fixedClock(now));
        for (int i = 0; i < 6; i++) {
            first.attempted();
        }
        now.addAndGet(Duration.ofMinutes(5).toNanos());

        RetryRun second = new RetryRun(defaults, fixedClock(now));

        assertFalse(first.mayAttempt());
        assertTrue(second.mayAttempt());
        assertEquals(0, second.attempts());
    }

    @Test
    @DisplayName("NFR-003: with defaults the worst case (all limits hit, longest waits) stays within 30 s")
    void worstCaseWithinThirtySeconds() {
        Backoff longest = new Backoff(defaults, () -> 0.999999);
        Duration total = Duration.ZERO;
        for (int n = 1; n <= defaults.maxAttempts(); n++) {
            total = total.plus(defaults.connectTimeout()).plus(defaults.responseTimeout());
            if (n < defaults.maxAttempts()) {
                total = total.plus(longest.delayAfter(n));
            }
        }

        assertTrue(total.compareTo(Duration.ofSeconds(30)) <= 0, total.toString());
        assertTrue(defaults.maxTotal().compareTo(Duration.ofSeconds(30)) <= 0);
    }

    @Test
    @DisplayName("FR-022: a policy with non-positive limits is refused")
    void invalidPolicyRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(Duration.ZERO, Duration.ofSeconds(1), 1,
                Duration.ofSeconds(1), Duration.ofMillis(1), Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1),
                0, Duration.ofSeconds(1), Duration.ofMillis(1), Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1),
                1, Duration.ofSeconds(1), Duration.ofMillis(5), Duration.ofMillis(1)));
    }

    private static Clock fixedClock(AtomicLong now) {
        return now::get;
    }
}
