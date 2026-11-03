package ds.banking.client;

import ds.banking.protocol.Answer;

/**
 * Result of one attempt (FR-004, research R4): either final, so the client stops, or retryable, so
 * the client resends the same key after a backoff.
 */
public sealed interface AttemptResult permits AttemptResult.Final, AttemptResult.Retryable {

    /**
     * A 2xx or 4xx answer.
     *
     * @param status HTTP status
     * @param answer parsed answer; {@code null} for a 4xx whose body could not be parsed
     */
    record Final(int status, Answer answer) implements AttemptResult {
    }

    /**
     * No usable answer: timeout, connection failure, any 5xx, or an unparseable 2xx.
     *
     * @param reason human-readable cause, for the attempt log
     */
    record Retryable(String reason) implements AttemptResult {
    }
}
