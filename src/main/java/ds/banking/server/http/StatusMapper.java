package ds.banking.server.http;

import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;

/**
 * HTTP status of an answer, from the operation and the outcome together (contracts/http-api.md).
 *
 * <p>Implements: FR-003, FR-004, FR-017. The status class alone tells the client what to do:
 * {@code 2xx} final (in effect), {@code 4xx} final (rejected), {@code 5xx} retryable (research R4).
 * The same outcome can have different statuses: {@code CANCELLED} is 409 for a debit (the debit did
 * not happen) and 200 for a cancel (the cancel took effect).
 */
public final class StatusMapper {

    private StatusMapper() {
    }

    /**
     * Returns the status for this pair.
     *
     * @throws IllegalArgumentException if the operation can never produce this outcome
     */
    public static int statusFor(Operation operation, Outcome outcome) {
        return switch (operation) {
            case DEBIT -> switch (outcome) {
                case EXECUTED -> 200;
                case INSUFFICIENT_FUNDS -> 422;
                case CANCELLED, STALE_KEY, KEY_CONFLICT -> 409;
                case INVALID_REQUEST -> 400;
                case UNKNOWN_ACCOUNT -> 404;
                case UNAVAILABLE -> 503;
                case ALREADY_EXECUTED, ALREADY_REJECTED -> throw impossible(operation, outcome);
            };
            case CANCEL -> switch (outcome) {
                case CANCELLED, ALREADY_REJECTED -> 200;
                case ALREADY_EXECUTED, STALE_KEY -> 409;
                case INVALID_REQUEST -> 400;
                case UNKNOWN_ACCOUNT -> 404;
                case UNAVAILABLE -> 503;
                case EXECUTED, INSUFFICIENT_FUNDS, KEY_CONFLICT -> throw impossible(operation, outcome);
            };
        };
    }

    private static IllegalArgumentException impossible(Operation operation, Outcome outcome) {
        return new IllegalArgumentException(operation + " never answers " + outcome);
    }
}
