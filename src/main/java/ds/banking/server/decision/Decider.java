package ds.banking.server.decision;

import ds.banking.protocol.Answer;
import ds.banking.protocol.DebitRequest;

/**
 * Decides debit (and later cancel) requests. The HTTP layer depends on this interface, so tests can
 * replace the real rules with one that fails, to check the {@code 500} path (research R9).
 *
 * <p>Implements: FR-005 to FR-014 through {@link DecisionService}. Requests are already validated
 * (FR-012) when they reach a decider.
 */
public interface Decider {

    /** Decides one valid debit request for {@code accountId} and returns its answer. */
    Answer debit(String accountId, DebitRequest request);
}
