package ds.banking.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import ds.banking.support.TestServer;
import ds.banking.support.TestServer.Response;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /admin/faults}, contracts/http-api.md §4 (T037; FR-029, FR-030, EC-012). */
@Tag("contract")
class FaultAdminContractTest {

    private static final String VALID = "{\"rules\":[{\"scenario\":\"DROP_RESPONSE\",\"trigger\":\"NEXT_N\","
            + "\"count\":1,\"account_id\":\"A\"},{\"scenario\":\"DELAY_BEFORE_EXECUTE\",\"trigger\":\"PROBABILITY\","
            + "\"probability\":0.1,\"delay_ms\":600,\"operation\":\"DEBIT\"}],\"seed\":42}";

    @TempDir
    Path dir;

    private TestServer server;

    @BeforeEach
    void start() {
        server = TestServer.start(dir);
        server.store().seedAccount("A", 1000);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private Response put(String json) {
        return server.send("PUT", "/admin/faults", json);
    }

    @Test
    @DisplayName("FR-030: the fault injector starts empty")
    void startsEmpty() {
        Response r = server.get("/admin/faults");

        assertEquals(200, r.status());
        assertEquals(0, r.json().get("rules").size());
    }

    @Test
    @DisplayName("FR-029: PUT replaces the rules at runtime and GET returns them")
    void putThenGet() {
        Response put = put(VALID);
        JsonNode current = server.get("/admin/faults").json();

        assertEquals(200, put.status(), put.body());
        assertEquals(put.json(), current);
        assertEquals(2, current.get("rules").size());
        JsonNode first = current.get("rules").get(0);
        assertEquals("DROP_RESPONSE", first.get("scenario").asText());
        assertEquals("A", first.get("account_id").asText());
        assertEquals(600, current.get("rules").get(1).get("delay_ms").asInt());
        assertEquals(42, current.get("seed").asLong());
    }

    @Test
    @DisplayName("EC-012: an invalid config is 400 INVALID_REQUEST and the previous config stays active")
    void invalidKeepsPrevious() {
        put(VALID);

        Response bad = put("{\"rules\":[{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"NEXT_N\",\"count\":0}]}");

        assertEquals(400, bad.status(), bad.body());
        assertEquals("INVALID_REQUEST", bad.json().get("outcome").asText());
        assertEquals(2, server.get("/admin/faults").json().get("rules").size());
    }

    @Test
    @DisplayName("FR-030: DELETE removes every rule")
    void deleteClears() {
        put(VALID);

        Response deleted = server.send("DELETE", "/admin/faults", null);

        assertEquals(200, deleted.status());
        assertEquals(0, deleted.json().get("rules").size());
        assertEquals(0, server.get("/admin/faults").json().get("rules").size());
    }

    @Test
    @DisplayName("FR-029: other methods on /admin/faults are 405")
    void otherMethods() {
        assertEquals(405, server.post("/admin/faults", VALID).status());
    }

    @Test
    @DisplayName("FR-030: inspection and admin endpoints are never faulted; a debit is")
    void onlyOperationsAreFaulted() {
        put("{\"rules\":[{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"NEXT_N\",\"count\":5}]}");

        assertEquals(200, server.get("/accounts/A").status());
        assertEquals(200, server.get("/accounts/A/transactions").status());
        assertEquals(200, server.post("/admin/accounts", "{\"account_id\":\"A\",\"balance\":1000}").status());
        assertEquals(200, server.get("/admin/faults").status());
        assertThrows(UncheckedIOException.class, () -> server.post("/accounts/A/debits",
                "{\"sequence_key\":1,\"amount\":10,\"is_retry\":false}"));
        assertEquals(1000, server.store().readAccount("A").orElseThrow().balance());
    }
}
