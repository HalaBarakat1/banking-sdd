package ds.banking.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.client.AttemptResult;
import ds.banking.client.BankingHttpClient;
import ds.banking.client.PaymentClient;
import ds.banking.client.RetryPolicy;
import ds.banking.client.TransactionResult;
import ds.banking.client.TransactionResult.Result;
import ds.banking.protocol.Outcome;
import ds.banking.support.ClientViews;
import ds.banking.support.Invariants;
import ds.banking.support.TestServer;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** User Story 1 end to end: real client, real server, no faults (T027; US1, FR-021, FR-023). */
@Tag("integration")
class DebitFlowIT {

    @TempDir
    Path dir;

    private TestServer server;
    private PaymentClient client;
    private BankingHttpClient raw;

    @BeforeEach
    void start() {
        server = TestServer.start(dir);
        server.store().seedAccount("A", 1000);
        server.store().seedAccount("B", 50);
        RetryPolicy policy = RetryPolicy.defaults();
        client = PaymentClient.create(server.uri("/"), dir.resolve("client.db"), policy);
        raw = new BankingHttpClient(server.uri("/"), policy);
    }

    @AfterEach
    void stop() {
        Invariants.check(server.store(), "A");
        Invariants.check(server.store(), "B");
        Invariants.crossCheck(ClientViews.from(client.journal()), server.store().readRecords("A"));
        server.close();
    }

    @Test
    @DisplayName("US1-AS1 / FR-023: a debit with a new key executes once and the client reports SUCCEEDED")
    void newDebitExecutes() {
        TransactionResult result = client.debit("A", 100);

        assertEquals(Result.SUCCEEDED, result.result());
        assertEquals(900L, result.balanceAfter());
        assertEquals(1, result.attempts());
        assertEquals(900, server.store().readAccount("A").orElseThrow().balance());
        assertEquals(result.sequenceKey(), server.store().readAccount("A").orElseThrow().lastSequenceKey());
    }

    @Test
    @DisplayName("US1-AS2 / FR-021 / FR-008: resending the same key as a retry gets the same answer, no second debit")
    void resendGetsSameAnswer() {
        TransactionResult result = client.debit("A", 100);

        AttemptResult again = raw.debit("A", result.sequenceKey(), 100, true, Duration.ofSeconds(2));

        AttemptResult.Final replay = assertInstanceOf(AttemptResult.Final.class, again);
        assertEquals(200, replay.status());
        assertEquals(Outcome.EXECUTED, replay.answer().outcome());
        assertTrue(replay.answer().duplicate());
        assertEquals(result.balanceAfter(), replay.answer().balanceAfter());
        assertEquals(900, server.store().readAccount("A").orElseThrow().balance());
    }

    @Test
    @DisplayName("US1-AS3: insufficient funds is final, consumes the key, and a resend gets the same refusal")
    void insufficientFunds() {
        TransactionResult result = client.debit("B", 100);

        AttemptResult again = raw.debit("B", result.sequenceKey(), 100, true, Duration.ofSeconds(2));

        assertEquals(Result.REJECTED, result.result());
        assertEquals(Outcome.INSUFFICIENT_FUNDS, result.outcome());
        AttemptResult.Final replay = assertInstanceOf(AttemptResult.Final.class, again);
        assertEquals(422, replay.status());
        assertTrue(replay.answer().duplicate());
        assertEquals(50, server.store().readAccount("B").orElseThrow().balance());
        assertEquals(result.sequenceKey(), server.store().readAccount("B").orElseThrow().lastSequenceKey());
    }

    @Test
    @DisplayName("US1-AS4 / FR-006: keys of one client counter leave gaps per account, and each gap key is new")
    void gapsAreAllowed() {
        TransactionResult first = client.debit("A", 10);
        client.debit("B", 10);
        TransactionResult third = client.debit("A", 10);

        assertEquals(first.sequenceKey() + 2, third.sequenceKey());
        assertEquals(Result.SUCCEEDED, third.result());
        assertEquals(third.sequenceKey(), server.store().readAccount("A").orElseThrow().lastSequenceKey());
        assertEquals(980, server.store().readAccount("A").orElseThrow().balance());
    }
}
