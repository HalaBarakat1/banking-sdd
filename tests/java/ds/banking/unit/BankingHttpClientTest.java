package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import ds.banking.client.AttemptResult;
import ds.banking.client.BankingHttpClient;
import ds.banking.client.RetryPolicy;
import ds.banking.protocol.Json;
import ds.banking.protocol.Outcome;
import ds.banking.support.StubBankServer;
import ds.banking.support.StubBankServer.Reply;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One attempt and its classification: final or retryable (T024; FR-004, FR-022, research R4). */
@Tag("unit")
class BankingHttpClientTest {

    private static final String EXECUTED = "{\"account_id\":\"A\",\"sequence_key\":7,\"outcome\":\"EXECUTED\","
            + "\"duplicate\":false,\"amount\":250,\"balance_after\":750}";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RetryPolicy policy = RetryPolicy.defaults();

    private AttemptResult debitAgainst(StubBankServer stub) {
        return new BankingHttpClient(stub.baseUri(), policy).debit("A", 7, 250, false, TIMEOUT);
    }

    @Test
    @DisplayName("FR-001 / FR-021: the attempt posts the debit body with the key, amount and is_retry flag")
    void sendsContractBody() throws Exception {
        try (StubBankServer stub = StubBankServer.start(Reply.json(200, EXECUTED))) {
            new BankingHttpClient(stub.baseUri(), policy).debit("A", 7, 250, true, TIMEOUT);

            StubBankServer.Received request = stub.received().get(0);
            assertEquals("POST", request.method());
            assertEquals("/accounts/A/debits", request.path());
            assertTrue(request.contentType().startsWith("application/json"));
            JsonNode body = Json.mapper().readTree(request.body());
            assertEquals(7, body.get("sequence_key").asLong());
            assertEquals(250, body.get("amount").asLong());
            assertTrue(body.get("is_retry").asBoolean());
            assertEquals(3, body.size());
        }
    }

    @Test
    @DisplayName("FR-004: a 2xx answer is final and parsed")
    void okIsFinal() {
        try (StubBankServer stub = StubBankServer.start(Reply.json(200, EXECUTED))) {
            AttemptResult.Final result = assertInstanceOf(AttemptResult.Final.class, debitAgainst(stub));

            assertEquals(200, result.status());
            assertEquals(Outcome.EXECUTED, result.answer().outcome());
            assertEquals(750L, result.answer().balanceAfter());
        }
    }

    @Test
    @DisplayName("FR-004: a 4xx answer is final and parsed")
    void clientErrorIsFinal() {
        String refused = "{\"account_id\":\"A\",\"sequence_key\":7,\"outcome\":\"INSUFFICIENT_FUNDS\",\"duplicate\":false}";
        try (StubBankServer stub = StubBankServer.start(Reply.json(422, refused))) {
            AttemptResult.Final result = assertInstanceOf(AttemptResult.Final.class, debitAgainst(stub));

            assertEquals(422, result.status());
            assertEquals(Outcome.INSUFFICIENT_FUNDS, result.answer().outcome());
        }
    }

    @Test
    @DisplayName("FR-004: a 4xx with an unparseable body is still final, with no answer")
    void unparseableClientErrorIsFinal() {
        try (StubBankServer stub = StubBankServer.start(Reply.json(404, "<html>not found</html>"))) {
            AttemptResult.Final result = assertInstanceOf(AttemptResult.Final.class, debitAgainst(stub));

            assertEquals(404, result.status());
            assertNull(result.answer());
        }
    }

    @ParameterizedTest(name = "[{index}] HTTP {0}")
    @ValueSource(ints = {500, 502, 503, 504})
    @DisplayName("FR-004: any 5xx is retryable")
    void serverErrorIsRetryable(int status) {
        try (StubBankServer stub = StubBankServer.start(Reply.json(status, "{\"outcome\":\"UNAVAILABLE\"}"))) {
            AttemptResult.Retryable result = assertInstanceOf(AttemptResult.Retryable.class, debitAgainst(stub));

            assertTrue(result.reason().contains(String.valueOf(status)), result.reason());
        }
    }

    @Test
    @DisplayName("FR-004: a 2xx with an unparseable body is retryable (treated as no answer)")
    void unparseableOkIsRetryable() {
        try (StubBankServer stub = StubBankServer.start(Reply.json(200, "garbage"))) {
            assertInstanceOf(AttemptResult.Retryable.class, debitAgainst(stub));
        }
    }

    @Test
    @DisplayName("FR-022 / FS-4: no response within the response timeout is retryable")
    void responseTimeoutIsRetryable() {
        try (StubBankServer stub = StubBankServer.start(new Reply(200, EXECUTED, 1500))) {
            long started = System.nanoTime();

            AttemptResult result = new BankingHttpClient(stub.baseUri(), policy)
                    .debit("A", 7, 250, false, Duration.ofMillis(200));

            AttemptResult.Retryable retry = assertInstanceOf(AttemptResult.Retryable.class, result);
            assertTrue(retry.reason().toLowerCase().contains("timed out"), retry.reason());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1400);
        }
    }

    @Test
    @DisplayName("FR-004 / FS-2: connection refused (server down) is retryable")
    void connectionRefusedIsRetryable() {
        StubBankServer stub = StubBankServer.start(Reply.json(200, EXECUTED));
        URI uri = stub.baseUri();
        stub.close();

        AttemptResult result = new BankingHttpClient(uri, policy).debit("A", 7, 250, false, TIMEOUT);

        assertInstanceOf(AttemptResult.Retryable.class, result);
    }

    @Test
    @DisplayName("FR-022: the client uses HTTP/1.1 and the policy's connect timeout")
    void usesHttp11AndConnectTimeout() {
        BankingHttpClient client = new BankingHttpClient(URI.create("http://127.0.0.1:1"), policy);

        assertEquals(HttpClient.Version.HTTP_1_1, client.version());
        assertEquals(Optional.of(Duration.ofSeconds(1)), client.connectTimeout());
    }
}
