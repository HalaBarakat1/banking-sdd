package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.client.ClientJournal;
import ds.banking.client.JournalEntry;
import ds.banking.client.JournalState;
import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The client's durable key counter and journal in client.db (T023; research R6, data-model.md). */
@Tag("unit")
class ClientJournalTest {

    @TempDir
    Path dir;

    private Path file;
    private ClientJournal journal;

    @BeforeEach
    void setUp() {
        file = dir.resolve("client.db");
        journal = new ClientJournal(file);
    }

    @Test
    @DisplayName("FR-019 / FR-020: reserve takes the next key and records a PENDING entry with no attempts, in one step")
    void reserveRecordsPending() {
        JournalEntry a = journal.reserve("A", Operation.DEBIT, 100).orElseThrow();
        JournalEntry b = journal.reserve("B", Operation.DEBIT, 50).orElseThrow();

        assertEquals(1, a.sequenceKey());
        assertEquals("A", a.accountId());
        assertEquals(Operation.DEBIT, a.operation());
        assertEquals(100, a.amount());
        assertEquals(JournalState.PENDING, a.state());
        assertEquals(0, a.attempts());
        assertNull(a.outcome());
        assertEquals(2, b.sequenceKey());
        assertEquals(2, journal.lastKey());
        assertEquals(Optional.of(a), journal.find(1));
    }

    @Test
    @DisplayName("FR-019 / NFR-001: keys strictly increase and are never handed out twice, also after reopening the file")
    void keysSurviveReopen() {
        long first = journal.reserve("A", Operation.DEBIT, 100).orElseThrow().sequenceKey();
        journal.resolve(first, Outcome.EXECUTED, 900L);

        ClientJournal reopened = new ClientJournal(file);
        long second = reopened.reserve("A", Operation.DEBIT, 100).orElseThrow().sequenceKey();

        assertTrue(second > first, first + " then " + second);
        assertEquals(second, reopened.lastKey());
    }

    @Test
    @DisplayName("FR-021: countAttempt is committed before sending and returns the previous count, also after reopening")
    void countAttempt() {
        long key = journal.reserve("A", Operation.DEBIT, 100).orElseThrow().sequenceKey();

        assertEquals(0, journal.countAttempt(key));
        assertEquals(1, journal.countAttempt(key));
        assertEquals(2, new ClientJournal(file).countAttempt(key));
        assertEquals(3, journal.find(key).orElseThrow().attempts());
    }

    @Test
    @DisplayName("FR-025 / EC-009: a second unresolved entry on the same account is refused and consumes no key")
    void oneUnresolvedPerAccount() {
        long key = journal.reserve("A", Operation.DEBIT, 100).orElseThrow().sequenceKey();

        assertTrue(journal.reserve("A", Operation.DEBIT, 5).isEmpty());
        assertEquals(key, journal.lastKey());
        journal.markUnknown(key);
        assertTrue(journal.reserve("A", Operation.DEBIT, 5).isEmpty());
        assertTrue(journal.reserve("B", Operation.DEBIT, 5).isPresent());
        journal.resolve(key, Outcome.EXECUTED, 900L);
        assertTrue(journal.reserve("A", Operation.DEBIT, 5).isPresent());
    }

    @Test
    @DisplayName("FR-025: the partial unique index enforces one unresolved entry per account in the database itself")
    void uniqueIndexInDatabase() throws SQLException {
        journal.reserve("A", Operation.DEBIT, 100);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                Statement s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.executeUpdate("INSERT INTO journal VALUES "
                    + "(99, 'A', 'DEBIT', 5, 'UNKNOWN', NULL, NULL, 0, 't', 't')"));
            s.executeUpdate("INSERT INTO journal VALUES (98, 'A', 'DEBIT', 5, 'RESOLVED', 'EXECUTED', 0, 1, 't', 't')");
        }
    }

    @Test
    @DisplayName("FR-023 / FR-024: resolve records the final outcome; markUnknown keeps the entry and its key unresolved")
    void transitions() {
        long done = journal.reserve("A", Operation.DEBIT, 100).orElseThrow().sequenceKey();
        long lost = journal.reserve("B", Operation.DEBIT, 50).orElseThrow().sequenceKey();
        long refused = journal.reserve("C", Operation.DEBIT, 5).orElseThrow().sequenceKey();

        journal.resolve(done, Outcome.EXECUTED, 900L);
        journal.markUnknown(lost);
        journal.resolve(refused, null, null);

        JournalEntry resolved = journal.find(done).orElseThrow();
        assertEquals(JournalState.RESOLVED, resolved.state());
        assertEquals(Outcome.EXECUTED, resolved.outcome());
        assertEquals(900L, resolved.balanceAfter());
        assertEquals(JournalState.UNKNOWN, journal.find(lost).orElseThrow().state());
        assertNull(journal.find(refused).orElseThrow().outcome());
        assertEquals(List.of(lost), journal.unresolved().stream().map(JournalEntry::sequenceKey).toList());
        assertEquals(List.of(done, lost, refused), journal.entries().stream().map(JournalEntry::sequenceKey).toList());
    }

    @Test
    @DisplayName("NFR-001: client.db uses WAL, synchronous FULL and a 5 s busy timeout, like the server store")
    void pragmas() {
        assertEquals("wal", journal.pragma("journal_mode"));
        assertEquals("2", journal.pragma("synchronous"));
        assertEquals("5000", journal.pragma("busy_timeout"));
    }

    @Test
    @DisplayName("FR-001: reserve refuses an invalid account id or a non-positive amount before writing anything")
    void invalidReserveRefused() {
        assertThrows(IllegalArgumentException.class, () -> journal.reserve("a b", Operation.DEBIT, 10));
        assertThrows(IllegalArgumentException.class, () -> journal.reserve("A", Operation.DEBIT, 0));
        assertEquals(0, journal.lastKey());
    }
}
