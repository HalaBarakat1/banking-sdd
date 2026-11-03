package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import ds.banking.server.store.AccountRow;
import ds.banking.server.store.LedgerStore;
import ds.banking.server.store.RecordRow;
import ds.banking.server.store.SeedResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SQLite schema, pragmas and transactions of the server store (T008; data-model.md, research R2/R3). */
@Tag("unit")
class LedgerStoreTest {

    @TempDir
    Path dir;

    private Path file;
    private LedgerStore store;

    @BeforeEach
    void setUp() {
        file = dir.resolve("bank.db");
        store = new LedgerStore(file);
        store.initSchema();
        store.seedAccount("A", 1000);
    }

    /** A plain connection that bypasses LedgerStore, to try writes the schema must refuse. */
    private Connection raw() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
        }
        return c;
    }

    private void assertRefused(String sql) throws SQLException {
        try (Connection c = raw(); Statement s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.executeUpdate(sql), sql);
        }
    }

    private void assertAccepted(String sql) throws SQLException {
        try (Connection c = raw(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    @Test
    @DisplayName("NFR-001 / FR-007: every connection uses WAL, synchronous FULL, foreign keys and a 5 s busy timeout")
    void pragmas() {
        assertEquals("wal", store.pragma("journal_mode"));
        assertEquals("2", store.pragma("synchronous"));
        assertEquals("1", store.pragma("foreign_keys"));
        assertEquals("5000", store.pragma("busy_timeout"));
    }

    @Test
    @DisplayName("FR-005: initSchema is idempotent and keeps existing data")
    void initSchemaIsIdempotent() {
        store.initSchema();

        assertEquals(new AccountRow("A", 1000, 1000, 0), store.readAccount("A").orElseThrow());
    }

    @Test
    @DisplayName("FR-014: CHECK (balance >= 0) refuses a negative balance")
    void balanceNeverNegative() throws SQLException {
        assertRefused("UPDATE accounts SET balance = -1 WHERE account_id = 'A'");
        assertAccepted("UPDATE accounts SET balance = 0 WHERE account_id = 'A'");
    }

    @Test
    @DisplayName("FR-005: CHECK (last_sequence_key >= 0) refuses a negative last key")
    void lastKeyNeverNegative() throws SQLException {
        assertRefused("UPDATE accounts SET last_sequence_key = -1 WHERE account_id = 'A'");
    }

    @Test
    @DisplayName("FR-005 / FR-007: primary key (account_id, sequence_key) allows one record per key, ever")
    void oneRecordPerKey() throws SQLException {
        String insert = "INSERT INTO transaction_records VALUES "
                + "('A', 1, 'DEBIT', 100, 'EXECUTED', 900, '2026-11-03T00:00:00Z')";
        assertAccepted(insert);
        assertRefused(insert);
        assertRefused("INSERT INTO transaction_records VALUES "
                + "('A', 1, 'CANCEL', NULL, 'CANCELLED', 900, '2026-11-03T00:00:00Z')");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "('A', 2, 'DEBIT', NULL, 'EXECUTED', 900, 't')",
        "('A', 2, 'DEBIT', 0, 'EXECUTED', 900, 't')",
        "('A', 2, 'DEBIT', -5, 'INSUFFICIENT_FUNDS', 900, 't')",
        "('A', 2, 'CANCEL', 10, 'CANCELLED', 900, 't')",
        "('A', 2, 'CANCEL', NULL, 'EXECUTED', 900, 't')",
        "('A', 2, 'DEBIT', 10, 'CANCELLED', 900, 't')",
        "('A', 2, 'REFUND', 10, 'EXECUTED', 900, 't')",
        "('A', 2, 'DEBIT', 10, 'DONE', 900, 't')",
        "('A', 0, 'DEBIT', 10, 'EXECUTED', 900, 't')",
        "('NOBODY', 2, 'DEBIT', 10, 'EXECUTED', 900, 't')",
        "('A', 2, 'DEBIT', '10', 'EXECUTED', 900, 't')"
    })
    @DisplayName("FR-005: record CHECKs refuse rows that contradict the data model, including a DEBIT with NULL amount")
    void recordChecks(String values) throws SQLException {
        assertRefused("INSERT INTO transaction_records VALUES " + values);
    }

    @Test
    @DisplayName("FR-005: valid DEBIT and CANCEL records are accepted")
    void validRecordsAccepted() throws SQLException {
        assertAccepted("INSERT INTO transaction_records VALUES ('A', 2, 'DEBIT', 10, 'INSUFFICIENT_FUNDS', 1000, 't')");
        assertAccepted("INSERT INTO transaction_records VALUES ('A', 3, 'CANCEL', NULL, 'CANCELLED', 1000, 't')");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"", "a b", "A/B", "Ä", "x".repeat(65)})
    @DisplayName("FR-005: account_id is 1-64 chars of [A-Za-z0-9_-], in the schema and when seeding")
    void accountIdFormat(String id) throws SQLException {
        assertRefused("INSERT INTO accounts VALUES ('" + id + "', 10, 10, 0)");
        assertThrows(IllegalArgumentException.class, () -> store.seedAccount(id, 10));
    }

    @Test
    @DisplayName("FR-031: seeding is idempotent for the same balance and refused for a different one")
    void seeding() {
        assertEquals(SeedResult.CREATED, store.seedAccount("B_2-x", 500));
        assertEquals(SeedResult.ALREADY_EXISTS, store.seedAccount("B_2-x", 500));
        assertEquals(SeedResult.CONFLICT, store.seedAccount("B_2-x", 600));
        assertThrows(IllegalArgumentException.class, () -> store.seedAccount("C", -1));
        assertEquals(new AccountRow("B_2-x", 500, 500, 0), store.readAccount("B_2-x").orElseThrow());
    }

    @Test
    @DisplayName("FR-007 / EC-011: an exception inside inImmediateTransaction rolls everything back")
    void failedTransactionLeavesNoChange() {
        RuntimeException boom = new RuntimeException("boom");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> store.inImmediateTransaction(tx -> {
            tx.updateAccount("A", 0, 1);
            tx.insertRecord(new RecordRow("A", 1, Operation.DEBIT, 1000L, Outcome.EXECUTED, 0, "t"));
            throw boom;
        }));

        assertEquals(boom, thrown);
        assertEquals(new AccountRow("A", 1000, 1000, 0), store.readAccount("A").orElseThrow());
        assertTrue(store.readRecords("A").isEmpty());
    }

    @Test
    @DisplayName("FR-007: a committed transaction updates the account and inserts the record together")
    void committedTransaction() {
        long balance = store.inImmediateTransaction(tx -> {
            AccountRow a = tx.account("A").orElseThrow();
            tx.updateAccount("A", a.balance() - 100, 1);
            tx.insertRecord(new RecordRow("A", 1, Operation.DEBIT, 100L, Outcome.EXECUTED, a.balance() - 100, "t"));
            return a.balance() - 100;
        });

        assertEquals(900, balance);
        assertEquals(new AccountRow("A", 1000, 900, 1), store.readAccount("A").orElseThrow());
        assertEquals(List.of(new RecordRow("A", 1, Operation.DEBIT, 100L, Outcome.EXECUTED, 900, "t")),
                store.readRecords("A"));
        assertEquals(new RecordRow("A", 1, Operation.DEBIT, 100L, Outcome.EXECUTED, 900, "t"),
                store.inImmediateTransaction(tx -> tx.record("A", 1)).orElseThrow());
    }

    @Test
    @DisplayName("FR-031: records are read in key order and an unknown account reads as empty")
    void readsInKeyOrder() {
        store.inImmediateTransaction(tx -> {
            tx.insertRecord(new RecordRow("A", 5, Operation.CANCEL, null, Outcome.CANCELLED, 1000, "t"));
            tx.insertRecord(new RecordRow("A", 2, Operation.DEBIT, 50L, Outcome.INSUFFICIENT_FUNDS, 1000, "t"));
            tx.updateAccount("A", 1000, 5);
            return null;
        });

        assertEquals(List.of(2L, 5L), store.readRecords("A").stream().map(RecordRow::sequenceKey).toList());
        assertTrue(store.readAccount("NOBODY").isEmpty());
        assertTrue(store.readRecords("NOBODY").isEmpty());
    }
}
