package ds.banking.support;

/**
 * What the client believes about one debit, in a form that needs no client code (research R9). Built
 * from the client journal, or written by hand in tests.
 *
 * @param accountId   account
 * @param sequenceKey key of the debit
 * @param amount      amount the client asked to debit
 * @param view        the client's final belief
 */
public record ExpectedDebit(String accountId, long sequenceKey, long amount, ClientView view) {

    /** The client's belief about whether the money moved. */
    public enum ClientView {
        /** The client reported the debit as executed. */
        SUCCEEDED,
        /** The client reported that the debit did not and will not happen (rejected, cancelled, stale). */
        NOT_SUCCEEDED,
        /** The client does not know (retries exhausted); either outcome is allowed, but at most once. */
        UNKNOWN
    }
}
