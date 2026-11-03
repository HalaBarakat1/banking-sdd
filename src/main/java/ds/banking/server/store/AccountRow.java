package ds.banking.server.store;

/**
 * One row of {@code accounts} (data-model.md; FR-005, FR-031). Immutable.
 *
 * @param accountId       identifier
 * @param initialBalance  balance when seeded; never changes (used by the money invariant)
 * @param balance         current balance, never negative (FR-014)
 * @param lastSequenceKey highest decided key, 0 if none; only increases (FR-006, FR-016)
 */
public record AccountRow(String accountId, long initialBalance, long balance, long lastSequenceKey) {
}
