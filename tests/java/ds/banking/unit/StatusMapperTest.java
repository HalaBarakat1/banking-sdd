package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import ds.banking.server.http.StatusMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** HTTP status per (operation, outcome), as in contracts/http-api.md (T007; FR-003, FR-004, FR-017). */
@Tag("unit")
class StatusMapperTest {

    @ParameterizedTest(name = "[{index}] {0} {1} -> {2}")
    @CsvSource({
        "DEBIT, EXECUTED, 200",
        "DEBIT, INSUFFICIENT_FUNDS, 422",
        "DEBIT, CANCELLED, 409",
        "DEBIT, STALE_KEY, 409",
        "DEBIT, KEY_CONFLICT, 409",
        "DEBIT, INVALID_REQUEST, 400",
        "DEBIT, UNKNOWN_ACCOUNT, 404",
        "DEBIT, UNAVAILABLE, 503",
        "CANCEL, CANCELLED, 200",
        "CANCEL, ALREADY_REJECTED, 200",
        "CANCEL, ALREADY_EXECUTED, 409",
        "CANCEL, STALE_KEY, 409",
        "CANCEL, INVALID_REQUEST, 400",
        "CANCEL, UNKNOWN_ACCOUNT, 404",
        "CANCEL, UNAVAILABLE, 503"
    })
    @DisplayName("FR-003 / FR-004 / FR-017: status follows the contract tables; 2xx and 4xx final, 5xx retryable")
    void statusFollowsContract(Operation operation, Outcome outcome, int status) {
        assertEquals(status, StatusMapper.statusFor(operation, outcome));
        assertEquals(outcome.retryable(), status >= 500);
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @CsvSource({
        "DEBIT, ALREADY_EXECUTED",
        "DEBIT, ALREADY_REJECTED",
        "CANCEL, EXECUTED",
        "CANCEL, INSUFFICIENT_FUNDS",
        "CANCEL, KEY_CONFLICT"
    })
    @DisplayName("FR-003 / FR-017: outcomes that an operation can never produce are a programming error")
    void impossiblePairsAreRejected(Operation operation, Outcome outcome) {
        assertThrows(IllegalArgumentException.class, () -> StatusMapper.statusFor(operation, outcome));
    }
}
