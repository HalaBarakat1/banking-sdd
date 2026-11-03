package ds.banking.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import ds.banking.server.store.RecordRow;
import ds.banking.support.TestServer;
import ds.banking.support.TestServer.Response;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Inspection and account seeding endpoints, contracts/http-api.md §3–§4 (T018; FR-031). */
@Tag("contract")
class InspectionContractTest {

    @TempDir
    Path dir;

    private TestServer server;

    @BeforeEach
    void start() {
        server = TestServer.start(dir);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private Response seed(String json) {
        return server.post("/admin/accounts", json);
    }

    @Test
    @DisplayName("FR-031: seeding answers 201 when created, 200 when it already exists with the same balance, 409 otherwise")
    void seedStatuses() {
        Response created = seed("{\"account_id\":\"A\",\"balance\":1000}");
        Response again = seed("{\"account_id\":\"A\",\"balance\":1000}");
        Response conflict = seed("{\"account_id\":\"A\",\"balance\":5}");

        assertEquals(201, created.status());
        assertEquals(1000, created.json().get("balance").asLong());
        assertEquals(200, again.status());
        assertEquals(409, conflict.status());
        assertTrue(created.contentType().startsWith("application/json"));
        assertEquals(1000, server.store().readAccount("A").orElseThrow().initialBalance());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "{\"account_id\":\"a b\",\"balance\":1000}",
        "{\"account_id\":\"A\",\"balance\":-1}",
        "{\"account_id\":\"A\",\"balance\":2.5}",
        "{\"account_id\":\"A\"}",
        "{\"account_id\":\"A\",\"balance\":1000,\"extra\":true}",
        "nope"
    })
    @DisplayName("FR-031 / FR-012: an invalid seed body is 400 INVALID_REQUEST and creates nothing")
    void invalidSeed(String body) {
        Response response = seed(body);

        assertEquals(400, response.status());
        assertEquals("INVALID_REQUEST", response.json().get("outcome").asText());
        assertTrue(server.store().readAccount("A").isEmpty());
    }

    @Test
    @DisplayName("FR-031: GET /accounts/{id} returns initial balance, balance and last sequence key")
    void readsAccount() {
        seed("{\"account_id\":\"A\",\"balance\":1000}");

        Response response = server.get("/accounts/A");

        assertEquals(200, response.status());
        JsonNode body = response.json();
        assertEquals("A", body.get("account_id").asText());
        assertEquals(1000, body.get("initial_balance").asLong());
        assertEquals(1000, body.get("balance").asLong());
        assertEquals(0, body.get("last_sequence_key").asLong());
    }

    @Test
    @DisplayName("FR-031: GET /accounts/{id}/transactions lists the records in key order with their fields")
    void readsTransactions() {
        seed("{\"account_id\":\"A\",\"balance\":1000}");
        assertEquals(0, server.get("/accounts/A/transactions").json().size());
        server.store().inImmediateTransaction(tx -> {
            tx.insertRecord(new RecordRow("A", 8, Operation.CANCEL, null, Outcome.CANCELLED, 750,
                    "2026-11-03T18:00:01Z"));
            tx.insertRecord(new RecordRow("A", 7, Operation.DEBIT, 250L, Outcome.EXECUTED, 750,
                    "2026-11-03T18:00:00Z"));
            tx.updateAccount("A", 750, 8);
            return null;
        });

        Response response = server.get("/accounts/A/transactions");

        assertEquals(200, response.status());
        JsonNode list = response.json();
        assertEquals(2, list.size());
        JsonNode debit = list.get(0);
        assertEquals(7, debit.get("sequence_key").asLong());
        assertEquals("DEBIT", debit.get("operation").asText());
        assertEquals(250, debit.get("amount").asLong());
        assertEquals("EXECUTED", debit.get("outcome").asText());
        assertEquals(750, debit.get("balance_after").asLong());
        assertEquals("2026-11-03T18:00:00Z", debit.get("decided_at").asText());
        JsonNode cancel = list.get(1);
        assertEquals("CANCEL", cancel.get("operation").asText());
        assertFalse(cancel.has("amount"));
        assertEquals(8, server.get("/accounts/A").json().get("last_sequence_key").asLong());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"/accounts/NOBODY", "/accounts/NOBODY/transactions", "/accounts/a%20b"})
    @DisplayName("FR-031 / EC-005: an unknown account is 404 UNKNOWN_ACCOUNT")
    void unknownAccount(String path) {
        Response response = server.get(path);

        assertEquals(404, response.status());
        assertEquals("UNKNOWN_ACCOUNT", response.json().get("outcome").asText());
    }
}
