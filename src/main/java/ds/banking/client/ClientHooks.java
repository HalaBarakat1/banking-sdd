package ds.banking.client;

/**
 * Test seam for client crashes (research R9; FR-026, SC-005). {@link PaymentClient} calls it at fixed
 * points; the default does nothing. A test makes it throw at one point to stop the client there, as a
 * process crash would, and then starts a new client on the same {@code client.db}.
 */
@FunctionalInterface
public interface ClientHooks {

    /** Points where a crash is interesting. */
    enum Point {
        /** The key is reserved and the entry journaled as PENDING; nothing was sent. */
        AFTER_RESERVE,
        /** A final answer arrived but is not recorded in the journal yet. */
        AFTER_RESPONSE_BEFORE_RECORD,
        /** The switch from debit to cancel is committed; the cancel was not sent yet (FR-027). */
        AFTER_CANCEL_SWITCH
    }

    /** Does nothing: the production behaviour. */
    ClientHooks NONE = (point, entry) -> { };

    void at(Point point, JournalEntry entry);
}
