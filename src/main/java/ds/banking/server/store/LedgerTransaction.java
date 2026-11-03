package ds.banking.server.store;

import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;

/**
 * Reads and writes available inside an open {@code BEGIN IMMEDIATE} transaction.
 *
 * <p>Implements: FR-005, FR-007. Everything done through one instance commits or rolls back together;
 * because the write lock is held from the first statement, no other request can change the account
 * between a read here and a write here (research R2). Valid only inside
 * {@link LedgerStore#inImmediateTransaction}; not shared between threads.
 */
public final class LedgerTransaction {

    private final Connection connection;

    LedgerTransaction(Connection connection) {
        this.connection = connection;
    }

    public Optional<AccountRow> account(String accountId) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(
                "SELECT account_id, initial_balance, balance, last_sequence_key FROM accounts WHERE account_id = ?")) {
            s.setString(1, accountId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(LedgerStore.toAccount(r)) : Optional.empty();
            }
        }
    }

    public Optional<RecordRow> record(String accountId, long sequenceKey) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(
                "SELECT account_id, sequence_key, operation, amount, outcome, balance_after, decided_at "
                        + "FROM transaction_records WHERE account_id = ? AND sequence_key = ?")) {
            s.setString(1, accountId);
            s.setLong(2, sequenceKey);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(LedgerStore.toRecord(r)) : Optional.empty();
            }
        }
    }

    public void insertAccount(String accountId, long balance) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(
                "INSERT INTO accounts (account_id, initial_balance, balance, last_sequence_key) VALUES (?, ?, ?, 0)")) {
            s.setString(1, accountId);
            s.setLong(2, balance);
            s.setLong(3, balance);
            s.executeUpdate();
        }
    }

    public void updateAccount(String accountId, long balance, long lastSequenceKey) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(
                "UPDATE accounts SET balance = ?, last_sequence_key = ? WHERE account_id = ?")) {
            s.setLong(1, balance);
            s.setLong(2, lastSequenceKey);
            s.setString(3, accountId);
            if (s.executeUpdate() != 1) {
                throw new SQLException("no account " + accountId);
            }
        }
    }

    public void insertRecord(RecordRow record) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(
                "INSERT INTO transaction_records "
                        + "(account_id, sequence_key, operation, amount, outcome, balance_after, decided_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            s.setString(1, record.accountId());
            s.setLong(2, record.sequenceKey());
            s.setString(3, record.operation().name());
            if (record.amount() == null) {
                s.setNull(4, Types.INTEGER);
            } else {
                s.setLong(4, record.amount());
            }
            s.setString(5, record.outcome().name());
            s.setLong(6, record.balanceAfter());
            s.setString(7, record.decidedAt());
            s.executeUpdate();
        }
    }

    static Operation operation(String name) {
        return Operation.valueOf(name);
    }

    static Outcome outcome(String name) {
        return Outcome.valueOf(name);
    }
}
