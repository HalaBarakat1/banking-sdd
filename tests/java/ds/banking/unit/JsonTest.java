package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.protocol.Answer;
import ds.banking.protocol.CancelRequest;
import ds.banking.protocol.DebitRequest;
import ds.banking.protocol.InvalidRequestException;
import ds.banking.protocol.Json;
import ds.banking.protocol.Outcome;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Wire format of the debit/cancel contract (T005; FR-001, FR-002, FR-012, FR-015, EC-004). */
@Tag("unit")
class JsonTest {

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("FR-001: debit request is read from snake_case fields sequence_key, amount, is_retry")
    void parsesDebitRequest() throws Exception {
        DebitRequest request = Json.parseDebit(bytes("{\"sequence_key\":7,\"amount\":250,\"is_retry\":false}"));

        assertEquals(new DebitRequest(7L, 250L, false), request);
    }

    @Test
    @DisplayName("FR-015: cancel request is read from snake_case fields sequence_key, is_retry")
    void parsesCancelRequest() throws Exception {
        CancelRequest request = Json.parseCancel(bytes("{\"sequence_key\":8,\"is_retry\":true}"));

        assertEquals(new CancelRequest(8L, true), request);
    }

    @Test
    @DisplayName("FR-002: answer is written in snake_case, omits absent fields, and reads back unchanged")
    void answerRoundTrip() throws Exception {
        Answer executed = new Answer("A", 7L, Outcome.EXECUTED, false, 250L, 750L, "debit executed");
        Answer stale = new Answer("A", 4L, Outcome.STALE_KEY, false, null, null, "stale key");

        String executedJson = new String(Json.write(executed), StandardCharsets.UTF_8);
        String staleJson = new String(Json.write(stale), StandardCharsets.UTF_8);

        assertEquals("{\"account_id\":\"A\",\"sequence_key\":7,\"outcome\":\"EXECUTED\",\"duplicate\":false,"
                + "\"amount\":250,\"balance_after\":750,\"message\":\"debit executed\"}", executedJson);
        assertFalse(staleJson.contains("amount"), staleJson);
        assertFalse(staleJson.contains("balance_after"), staleJson);
        assertEquals(executed, Json.parseAnswer(bytes(executedJson)));
        assertEquals(stale, Json.parseAnswer(bytes(staleJson)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "{\"sequence_key\":7,\"amount\":250,\"is_retry\":false,\"extra\":1}",
        "{\"sequence_key\":7,\"amount\":250}",
        "{\"sequence_key\":7,\"is_retry\":false}",
        "{\"amount\":250,\"is_retry\":false}",
        "{\"sequence_key\":7,\"amount\":null,\"is_retry\":false}",
        "{\"sequence_key\":7,\"amount\":250,\"is_retry\":null}",
        "{\"sequence_key\":7,\"amount\":2.5,\"is_retry\":false}",
        "{\"sequence_key\":7.0,\"amount\":250,\"is_retry\":false}",
        "{\"sequence_key\":7,\"amount\":\"250\",\"is_retry\":false}",
        "{\"sequence_key\":7,\"amount\":250,\"is_retry\":\"false\"}",
        "{\"sequence_key\":7,\"amount\":250,\"is_retry\":0}",
        "{\"sequence_key\":0,\"amount\":250,\"is_retry\":false}",
        "{\"sequence_key\":-3,\"amount\":250,\"is_retry\":false}",
        "{\"sequence_key\":7,\"amount\":0,\"is_retry\":false}",
        "{\"sequence_key\":7,\"amount\":-5,\"is_retry\":false}",
        "{\"sequence_key\":7,\"amount\":99999999999999999999,\"is_retry\":false}",
        "[7,250,false]",
        "",
        "not json"
    })
    @DisplayName("FR-012 / EC-004 / FR-001: invalid debit bodies are rejected, never rounded or defaulted")
    void rejectsInvalidDebit(String body) {
        assertThrows(InvalidRequestException.class, () -> Json.parseDebit(bytes(body)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "{\"sequence_key\":8}",
        "{\"sequence_key\":8,\"is_retry\":false,\"amount\":5}",
        "{\"sequence_key\":0,\"is_retry\":false}",
        "{\"sequence_key\":\"8\",\"is_retry\":false}"
    })
    @DisplayName("FR-015 / FR-012 / EC-004: invalid cancel bodies are rejected")
    void rejectsInvalidCancel(String body) {
        assertThrows(InvalidRequestException.class, () -> Json.parseCancel(bytes(body)));
    }

    @Test
    @DisplayName("FR-012: the rejection message names the problem")
    void rejectionExplainsWhy() {
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> Json.parseDebit(bytes("{\"sequence_key\":7,\"amount\":2.5,\"is_retry\":false}")));

        assertTrue(e.getMessage().contains("amount"), e.getMessage());
    }
}
