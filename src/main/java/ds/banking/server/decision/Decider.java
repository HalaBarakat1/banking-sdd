package ds.banking.server.decision;

import ds.banking.protocol.Answer;
import ds.banking.protocol.CancelRequest;
import ds.banking.protocol.DebitRequest;

/**
 * Decides debit and cancel requests. The HTTP layer depends on this interface, so tests can
 * replace the real rules with one that fails, to check the {@code 500} path (research R9).
 *
 * <p>Implements: FR-005 to FR-018 through {@link DecisionService}. Requests are already validated
 * (FR-012) when they reach a decider.
 */
public interface Decider {

    /** Decides one valid debit request for {@code accountId} and returns its answer. */
    Answer debit(String accountId, DebitRequest request);

    /** Decides one valid cancel request for {@code accountId} and returns its answer (FR-015 to FR-018). */
    Answer cancel(String accountId, CancelRequest request);
}
