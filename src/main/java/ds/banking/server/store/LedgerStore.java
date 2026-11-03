package ds.banking.server.store;

import ds.banking.protocol.AccountIds;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.sqlite.SQLiteConfig;

/**
 * The server's durable state in one SQLite file ({@code bank.db}).
 *
 * <p>Implements: FR-005 (accounts and transaction records), FR-007 (one indivisible step per
 * decision), FR-013 (nothing is reported before {@code COMMIT} returns), FR-014 (balance never
 * negative), NFR-001 (survives crashes). Settings follow research R2/R3: every connection uses WAL,
 * {@code synchronous=FULL}, foreign keys and a busy timeout, and every decision runs in a
 * {@code BEGIN IMMEDIATE} transaction, which takes the database write lock before the first read.
 *
 * <p>Thread safety: this class keeps no mutable state; each call opens its own connection, so it can
 * be used from any number of threads (and processes). SQLite's file lock is the single point where
 * decisions are serialized (constitution Principle III).
 */
public final class LedgerStore {

    public static final int DEFAULT_BUSY_TIMEOUT_MS = 5000;

    private static final Pattern PRAGMA_NAME = Pattern.compile("[a-z_]+");

    private static final String SCHEMA_ACCOUNTS = """
            CREATE TABLE IF NOT EXISTS accounts (
              account_id        TEXT    PRIMARY KEY
                                CHECK (length(account_id) BETWEEN 1 AND 64
                                       AND account_id NOT GLOB '*[^A-Za-z0-9_-]*'),
              initial_balance   INTEGER NOT NULL CHECK (initial_balance >= 0),
              balance           INTEGER NOT NULL CHECK (balance >= 0),
              last_sequence_key INTEGER NOT NULL DEFAULT 0 CHECK (last_sequence_key >= 0)
            ) STRICT""";

    private static final String SCHEMA_RECORDS = """
            CREATE TABLE IF NOT EXISTS transaction_records (
              account_id    TEXT    NOT NULL REFERENCES accounts (account_id),
              sequence_key  INTEGER NOT NULL CHECK (sequence_key > 0),
              operation     TEXT    NOT NULL CHECK (operation IN ('DEBIT', 'CANCEL')),
              amount        INTEGER,
              outcome       TEXT    NOT NULL CHECK (outcome IN ('EXECUTED', 'INSUFFICIENT_FUNDS', 'CANCELLED')),
              balance_after INTEGER NOT NULL,
              decided_at    TEXT    NOT NULL,
              PRIMARY KEY (account_id, sequence_key),
              CHECK ((operation = 'DEBIT' AND amount IS NOT NULL AND amount > 0)
                     OR (operation = 'CANCEL' AND amount IS NULL)),
              CHECK ((operation = 'CANCEL') = (outcome = 'CANCELLED'))
            ) STRICT""";

    private final String url;
    private final SQLiteConfig config;

    public LedgerStore(Path file) {
        this(file, DEFAULT_BUSY_TIMEOUT_MS);
    }

    public LedgerStore(Path file, int busyTimeoutMs) {
        this.url = "jdbc:sqlite:" + file.toAbsolutePath();
        this.config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(busyTimeoutMs);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
    }

    private Connection open() throws SQLException {
        return config.createConnection(url);
    }

    /** Creates the tables if they do not exist; keeps existing data. */
    public void initSchema() {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute(SCHEMA_ACCOUNTS);
            s.execute(SCHEMA_RECORDS);
        } catch (SQLException e) {
            throw new StoreException("cannot create schema", e);
        }
    }

    /**
     * Runs {@code work} in one {@code BEGIN IMMEDIATE} transaction and commits it. If {@code work} throws,
     * everything it did is rolled back and the exception is rethrown (a {@link SQLException} wrapped in
     * a {@link StoreException}). The result is returned only after {@code COMMIT} succeeded (FR-013).
     */
    public <T> T inImmediateTransaction(TransactionWork<T> work) {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            T result;
            try {
                result = work.run(new LedgerTransaction(c));
            } catch (SQLException | RuntimeException | Error e) {
                rollback(s, e);
                throw e;
            }
            s.execute("COMMIT");
            return result;
        } catch (SQLException e) {
            throw new StoreException("transaction failed: " + e.getMessage(), e);
        }
    }

    private static void rollback(Statement s, Throwable cause) {
        try {
            s.execute("ROLLBACK");
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }

    /**
     * Creates an account with a starting balance (FR-031; tests and demonstrations only).
     *
     * @throws IllegalArgumentException if the id format or the balance is invalid
     */
    public SeedResult seedAccount(String accountId, long balance) {
        if (!AccountIds.isValid(accountId)) {
            throw new IllegalArgumentException("account_id must be 1-64 chars of [A-Za-z0-9_-]: " + accountId);
        }
        if (balance < 0) {
            throw new IllegalArgumentException("balance must be >= 0: " + balance);
        }
        return inImmediateTransaction(tx -> {
            Optional<AccountRow> existing = tx.account(accountId);
            if (existing.isPresent()) {
                return existing.get().initialBalance() == balance ? SeedResult.ALREADY_EXISTS : SeedResult.CONFLICT;
            }
            tx.insertAccount(accountId, balance);
            return SeedResult.CREATED;
        });
    }

    /** Reads one account outside any transaction (inspection, FR-031). */
    public Optional<AccountRow> readAccount(String accountId) {
        try (Connection c = open(); PreparedStatement s = c.prepareStatement(
                "SELECT account_id, initial_balance, balance, last_sequence_key FROM accounts WHERE account_id = ?")) {
            s.setString(1, accountId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(toAccount(r)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("cannot read account " + accountId, e);
        }
    }

    /** Reads all transaction records of an account in key order (inspection, FR-031). */
    public List<RecordRow> readRecords(String accountId) {
        try (Connection c = open(); PreparedStatement s = c.prepareStatement(
                "SELECT account_id, sequence_key, operation, amount, outcome, balance_after, decided_at "
                        + "FROM transaction_records WHERE account_id = ? ORDER BY sequence_key")) {
            s.setString(1, accountId);
            try (ResultSet r = s.executeQuery()) {
                List<RecordRow> records = new ArrayList<>();
                while (r.next()) {
                    records.add(toRecord(r));
                }
                return records;
            }
        } catch (SQLException e) {
            throw new StoreException("cannot read records of " + accountId, e);
        }
    }

    /** Reads one pragma as text, for diagnostics and tests (NFR-001). */
    public String pragma(String name) {
        if (!PRAGMA_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a pragma name: " + name);
        }
        try (Connection c = open(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA " + name)) {
            return r.next() ? r.getString(1) : null;
        } catch (SQLException e) {
            throw new StoreException("cannot read pragma " + name, e);
        }
    }

    static AccountRow toAccount(ResultSet r) throws SQLException {
        return new AccountRow(r.getString(1), r.getLong(2), r.getLong(3), r.getLong(4));
    }

    static RecordRow toRecord(ResultSet r) throws SQLException {
        long amount = r.getLong(4);
        Long boxedAmount = r.wasNull() ? null : amount;
        return new RecordRow(r.getString(1), r.getLong(2), LedgerTransaction.operation(r.getString(3)),
                boxedAmount, LedgerTransaction.outcome(r.getString(5)), r.getLong(6), r.getString(7));
    }
}
