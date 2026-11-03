package ds.banking.protocol;

/**
 * Answer to a debit or cancel request.
 *
 * <p>Implements: FR-002 (answer fields), FR-008 ({@code duplicate} marks a replayed decision).
 * {@code amount} and {@code balanceAfter} are {@code null} when the outcome has none; they are then
 * left out of the JSON. Immutable (constitution Principle III).
 *
 * @param accountId    account the request was for
 * @param sequenceKey  key of the request
 * @param outcome      the outcome (FR-003)
 * @param duplicate    true when this answer replays an earlier decision
 * @param amount       debited (or refused) amount, when the outcome has one
 * @param balanceAfter account balance right after the decision, when the outcome has one
 * @param message      human-readable text, never parsed
 */
public record Answer(
        String accountId,
        Long sequenceKey,
        Outcome outcome,
        Boolean duplicate,
        Long amount,
        Long balanceAfter,
        String message) {
}
