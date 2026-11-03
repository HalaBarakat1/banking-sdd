package ds.banking.protocol;

/**
 * Body of {@code POST /accounts/{account_id}/debits}.
 *
 * <p>Implements: FR-001. Fields are boxed so that a missing value is seen as missing, never
 * defaulted (FR-012, research R8). Immutable (constitution Principle III).
 *
 * @param sequenceKey client-generated key, &gt; 0
 * @param amount      amount in minor units, &gt; 0
 * @param isRetry     informational only; never changes the decision (FR-010)
 */
public record DebitRequest(Long sequenceKey, Long amount, Boolean isRetry) {
}
