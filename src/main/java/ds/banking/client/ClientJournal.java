package ds.banking.client;

import ds.banking.protocol.AccountIds;
import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.sqlite.SQLiteConfig;

/**
 * The client's durable state in its own SQLite file ({@code client.db}): the key counter and the
 * journal of transactions.
 *
 * <p>Implements: FR-019 (keys strictly increase and are never handed out twice: the counter is
 * advanced and saved before anything is sent), FR-020 (the pending transaction is recorded in the same
 * transaction as the key), FR-021 (the attempt counter is committed before each send), FR-024
 * ({@code UNKNOWN} entries keep their key), FR-025 and EC-009 (at most one unresolved entry per
 * account, enforced by a partial unique index), FR-027 (the switch from debit to cancel is committed
 * before anything is sent), NFR-001 (WAL and {@code synchronous=FULL}, as on the
 * server; research R6).
 *
 * <p>Thread safety: no mutable fields; each call uses its own connection and an {@code IMMEDIATE}
 * transaction, so concurrent client threads are serialized by the database (constitution Principle
 * III).
 */
public final class ClientJournal {

    private static final int BUSY_TIMEOUT_MS = 5000;
    private static final Pattern PRAGMA_NAME = Pattern.compile("[a-z_]+");

    private static final String COLUMNS = "sequence_key, account_id, operation, amount, state, outcome, "
            + "balance_after, attempts, created_at, updated_at";

    private final String url;
    private final SQLiteConfig config;

    public ClientJournal(Path file) {
        this.url = "jdbc:sqlite:" + file.toAbsolutePath();
        this.config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
        initSchema();
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private Connection open() throws SQLException {
        return config.createConnection(url);
    }

    private <T> T inTransaction(Work<T> work) {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            T result;
            try {
                result = work.run(c);
            } catch (SQLException | RuntimeException e) {
                s.execute("ROLLBACK");
                throw e;
            }
            s.execute("COMMIT");
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("client journal failure: " + e.getMessage(), e);
        }
    }

    private void initSchema() {
        inTransaction(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("""
                        CREATE TABLE IF NOT EXISTS key_counter (
                          id       INTEGER PRIMARY KEY CHECK (id = 1),
                          last_key INTEGER NOT NULL CHECK (last_key >= 0)
                        ) STRICT""");
                s.execute("INSERT OR IGNORE INTO key_counter (id, last_key) VALUES (1, 0)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS journal (
                          sequence_key  INTEGER PRIMARY KEY CHECK (sequence_key > 0),
                          account_id    TEXT    NOT NULL,
                          operation     TEXT    NOT NULL CHECK (operation IN ('DEBIT', 'CANCEL')),
                          amount        INTEGER NOT NULL CHECK (amount > 0),
                          state         TEXT    NOT NULL CHECK (state IN ('PENDING', 'UNKNOWN', 'RESOLVED')),
                          outcome       TEXT,
                          balance_after INTEGER,
                          attempts      INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
                          created_at    TEXT    NOT NULL,
                          updated_at    TEXT    NOT NULL
                        ) STRICT""");
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_unresolved_per_account "
                        + "ON journal (account_id) WHERE state <> 'RESOLVED'");
            }
            return null;
        });
    }

    /**
     * Reserves the next key and records the transaction as {@code PENDING}, in one transaction. Returns
     * empty, consuming no key, if the account already has an unresolved entry (FR-025).
     */
    public Optional<JournalEntry> reserve(String accountId, Operation operation, long amount) {
        if (!AccountIds.isValid(accountId)) {
            throw new IllegalArgumentException("account_id must be 1-64 chars of [A-Za-z0-9_-]: " + accountId);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        return inTransaction(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT 1 FROM journal WHERE account_id = ? AND state <> 'RESOLVED'")) {
                s.setString(1, accountId);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        return Optional.empty();
                    }
                }
            }
            long key;
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE key_counter SET last_key = last_key + 1 WHERE id = 1");
                try (ResultSet r = s.executeQuery("SELECT last_key FROM key_counter WHERE id = 1")) {
                    r.next();
                    key = r.getLong(1);
                }
            }
            String now = Instant.now().toString();
            try (PreparedStatement s = c.prepareStatement("INSERT INTO journal (" + COLUMNS + ") "
                    + "VALUES (?, ?, ?, ?, 'PENDING', NULL, NULL, 0, ?, ?)")) {
                s.setLong(1, key);
                s.setString(2, accountId);
                s.setString(3, operation.name());
                s.setLong(4, amount);
                s.setString(5, now);
                s.setString(6, now);
                s.executeUpdate();
            }
            return Optional.of(new JournalEntry(key, accountId, operation, amount, JournalState.PENDING, null, null,
                    0, now, now));
        });
    }

    /** Counts one more attempt of the current operation, committed before sending; returns the previous count. */
    public int countAttempt(long key) {
        return inTransaction(c -> {
            int previous;
            try (PreparedStatement s = c.prepareStatement("SELECT attempts FROM journal WHERE sequence_key = ?")) {
                s.setLong(1, key);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) {
                        throw new SQLException("no journal entry " + key);
                    }
                    previous = r.getInt(1);
                }
            }
            update(c, "UPDATE journal SET attempts = attempts + 1, updated_at = ? WHERE sequence_key = ?", key);
            return previous;
        });
    }

    /** Records the final outcome ({@code null} for an unparseable 4xx) and marks the entry RESOLVED. */
    public void resolve(long key, Outcome outcome, Long balanceAfter) {
        inTransaction(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE journal SET state = 'RESOLVED', outcome = ?, "
                    + "balance_after = ?, updated_at = ? WHERE sequence_key = ?")) {
                if (outcome == null) {
                    s.setNull(1, Types.VARCHAR);
                } else {
                    s.setString(1, outcome.name());
                }
                if (balanceAfter == null) {
                    s.setNull(2, Types.INTEGER);
                } else {
                    s.setLong(2, balanceAfter);
                }
                s.setString(3, Instant.now().toString());
                s.setLong(4, key);
                requireOne(s.executeUpdate(), key);
            }
            return null;
        });
    }

    /**
     * Switches an unresolved debit to a cancel in one committed update: {@code operation := CANCEL},
     * {@code attempts := 0}, {@code state := PENDING} (FR-027). It is committed before anything is sent
     * for the key, so a later start always resends the cancel, never the old debit. Returns the switched
     * entry, or empty if the key is not an unresolved debit of {@code accountId}.
     */
    public Optional<JournalEntry> switchToCancel(String accountId, long key) {
        boolean switched = inTransaction(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE journal SET operation = 'CANCEL', attempts = 0, "
                    + "state = 'PENDING', updated_at = ? WHERE sequence_key = ? AND account_id = ? "
                    + "AND operation = 'DEBIT' AND state <> 'RESOLVED'")) {
                s.setString(1, Instant.now().toString());
                s.setLong(2, key);
                s.setString(3, accountId);
                return s.executeUpdate() == 1;
            }
        });
        return switched ? find(key) : Optional.empty();
    }

    /** Marks the entry UNKNOWN: retries of this run were exhausted; the key is kept (FR-024). */
    public void markUnknown(long key) {
        inTransaction(c -> {
            update(c, "UPDATE journal SET state = 'UNKNOWN', updated_at = ? WHERE sequence_key = ?", key);
            return null;
        });
    }

    public Optional<JournalEntry> find(long key) {
        List<JournalEntry> found = query("SELECT " + COLUMNS + " FROM journal WHERE sequence_key = " + key);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** PENDING and UNKNOWN entries, in key order. */
    public List<JournalEntry> unresolved() {
        return query("SELECT " + COLUMNS + " FROM journal WHERE state <> 'RESOLVED' ORDER BY sequence_key");
    }

    /** All entries, in key order. */
    public List<JournalEntry> entries() {
        return query("SELECT " + COLUMNS + " FROM journal ORDER BY sequence_key");
    }

    /** The last key handed out (0 if none). */
    public long lastKey() {
        return inTransaction(c -> {
            try (Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("SELECT last_key FROM key_counter WHERE id = 1")) {
                r.next();
                return r.getLong(1);
            }
        });
    }

    /** Reads one pragma as text, for diagnostics and tests (NFR-001). */
    public String pragma(String name) {
        if (!PRAGMA_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a pragma name: " + name);
        }
        try (Connection c = open(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA " + name)) {
            return r.next() ? r.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read pragma " + name, e);
        }
    }

    private List<JournalEntry> query(String sql) {
        try (Connection c = open(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            List<JournalEntry> entries = new ArrayList<>();
            while (r.next()) {
                long balance = r.getLong(7);
                Long balanceAfter = r.wasNull() ? null : balance;
                String outcome = r.getString(6);
                entries.add(new JournalEntry(r.getLong(1), r.getString(2), Operation.valueOf(r.getString(3)),
                        r.getLong(4), JournalState.valueOf(r.getString(5)),
                        outcome == null ? null : Outcome.valueOf(outcome), balanceAfter, r.getInt(8),
                        r.getString(9), r.getString(10)));
            }
            return entries;
        } catch (SQLException e) {
            throw new IllegalStateException("client journal failure: " + e.getMessage(), e);
        }
    }

    private static void update(Connection c, String sql, long key) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, Instant.now().toString());
            s.setLong(2, key);
            requireOne(s.executeUpdate(), key);
        }
    }

    private static void requireOne(int rows, long key) throws SQLException {
        if (rows != 1) {
            throw new SQLException("no journal entry " + key);
        }
    }
}
