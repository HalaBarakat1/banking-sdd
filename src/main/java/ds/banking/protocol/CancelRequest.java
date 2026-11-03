package ds.banking.protocol;

/**
 * Body of {@code POST /accounts/{account_id}/cancellations}.
 *
 * <p>Implements: FR-015. Immutable (constitution Principle III).
 *
 * @param sequenceKey the key to cancel (burn), &gt; 0
 * @param isRetry     informational only (FR-010)
 */
public record CancelRequest(Long sequenceKey, Boolean isRetry) {
}
