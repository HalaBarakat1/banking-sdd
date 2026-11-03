package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ds.banking.protocol.Outcome;
import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Retry class of every outcome (T006; FR-003, FR-004). */
@Tag("unit")
class OutcomeTest {

    @Test
    @DisplayName("FR-003: the outcome set is exactly the one listed in the spec")
    void outcomeSetMatchesSpec() {
        assertEquals(EnumSet.of(
                Outcome.EXECUTED, Outcome.INSUFFICIENT_FUNDS, Outcome.CANCELLED, Outcome.STALE_KEY,
                Outcome.KEY_CONFLICT, Outcome.INVALID_REQUEST, Outcome.UNKNOWN_ACCOUNT, Outcome.UNAVAILABLE,
                Outcome.ALREADY_EXECUTED, Outcome.ALREADY_REJECTED),
                EnumSet.allOf(Outcome.class));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(Outcome.class)
    @DisplayName("FR-004: only UNAVAILABLE is retryable, every other outcome is final")
    void onlyUnavailableIsRetryable(Outcome outcome) {
        assertEquals(outcome == Outcome.UNAVAILABLE, outcome.retryable());
    }
}
