package ds.banking.server.store;

import java.sql.SQLException;

/** Work done inside one {@code BEGIN IMMEDIATE} transaction (FR-007, research R2). */
@FunctionalInterface
public interface TransactionWork<T> {

    T run(LedgerTransaction tx) throws SQLException;
}
