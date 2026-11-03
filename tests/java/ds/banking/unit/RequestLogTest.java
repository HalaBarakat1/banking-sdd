package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import ds.banking.server.log.LogDecision;
import ds.banking.server.log.RequestLog;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** One structured log line per request (T009; NFR-004, data-model "Log decision per answer"). */
@Tag("unit")
class RequestLogTest {

    @Test
    @DisplayName("NFR-004: the line has account, key, is_retry, decision, outcome, fault and latency")
    void formatsAllFields() {
        RequestLog.Entry entry = new RequestLog.Entry(Operation.DEBIT, "A", 7L, true,
                LogDecision.DUPLICATE, Outcome.EXECUTED, "DROP_RESPONSE", 12);

        assertEquals("op=DEBIT account=A key=7 is_retry=true decision=DUPLICATE outcome=EXECUTED "
                + "fault=DROP_RESPONSE latency_ms=12", RequestLog.format(entry));
    }

    @Test
    @DisplayName("NFR-004: missing values print as '-', no fault prints as 'none', a dropped request has no outcome")
    void formatsMissingValues() {
        RequestLog.Entry entry = new RequestLog.Entry(Operation.CANCEL, "B", null, null,
                LogDecision.DROPPED, null, null, 0);

        assertEquals("op=CANCEL account=B key=- is_retry=- decision=DROPPED outcome=- fault=none latency_ms=0",
                RequestLog.format(entry));
    }

    @ParameterizedTest(name = "[{index}] {0} duplicate={1} -> {2}")
    @CsvSource({
        "EXECUTED, false, EXECUTED",
        "CANCELLED, false, EXECUTED",
        "EXECUTED, true, DUPLICATE",
        "INSUFFICIENT_FUNDS, true, DUPLICATE",
        "CANCELLED, true, DUPLICATE",
        "ALREADY_EXECUTED, true, DUPLICATE",
        "ALREADY_REJECTED, true, DUPLICATE",
        "INSUFFICIENT_FUNDS, false, REJECTED",
        "STALE_KEY, false, REJECTED",
        "KEY_CONFLICT, false, REJECTED",
        "INVALID_REQUEST, false, REJECTED",
        "UNKNOWN_ACCOUNT, false, REJECTED",
        "UNAVAILABLE, false, REJECTED"
    })
    @DisplayName("NFR-004: decision follows the data-model table: executed, replayed or rejected")
    void decisionTable(Outcome outcome, boolean duplicate, LogDecision expected) {
        assertEquals(expected, LogDecision.of(outcome, duplicate));
    }

    @Test
    @DisplayName("NFR-004: log() publishes exactly one INFO record per request with the formatted line")
    void logPublishesOneRecord() {
        List<LogRecord> seen = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                seen.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = RequestLog.logger();
        logger.addHandler(capture);
        try {
            RequestLog.Entry entry = new RequestLog.Entry(Operation.DEBIT, "A", 1L, false,
                    LogDecision.EXECUTED, Outcome.EXECUTED, null, 3);

            RequestLog.log(entry);

            assertEquals(1, seen.size());
            assertEquals(Level.INFO, seen.get(0).getLevel());
            assertEquals(RequestLog.format(entry), seen.get(0).getMessage());
        } finally {
            logger.removeHandler(capture);
        }
    }
}
