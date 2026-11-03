package ds.banking.support;

import ds.banking.client.ClientJournal;
import ds.banking.client.JournalEntry;
import ds.banking.client.JournalState;
import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import ds.banking.support.ExpectedDebit.ClientView;
import java.util.List;

/** Turns a client journal into the neutral {@link ExpectedDebit} list for {@link Invariants#crossCheck}. */
public final class ClientViews {

    private ClientViews() {
    }

    public static List<ExpectedDebit> from(ClientJournal journal) {
        return journal.entries().stream().map(ClientViews::view).toList();
    }

    private static ExpectedDebit view(JournalEntry e) {
        ClientView view;
        if (e.state() != JournalState.RESOLVED) {
            view = ClientView.UNKNOWN;
        } else if (e.operation() == Operation.DEBIT && e.outcome() == Outcome.EXECUTED
                || e.operation() == Operation.CANCEL && e.outcome() == Outcome.ALREADY_EXECUTED) {
            view = ClientView.SUCCEEDED;
        } else {
            view = ClientView.NOT_SUCCEEDED;
        }
        return new ExpectedDebit(e.accountId(), e.sequenceKey(), e.amount(), view);
    }
}
