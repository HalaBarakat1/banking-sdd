package ds.banking.server.log;

import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

/**
 * The server's request log: exactly one line per debit or cancel request.
 *
 * <p>Implements: NFR-004 (account, key, {@code is_retry}, decision, outcome, injected fault and
 * latency on one line), constitution Principle VI. Uses {@code java.util.logging} only. Lines go to
 * standard output (contracts/client-cli.md). {@code java.util.logging.Logger} is thread-safe, and the
 * handler is installed once in the static initializer.
 */
public final class RequestLog {

    public static final String LOGGER_NAME = "ds.banking.server.request";

    private static final Logger LOGGER = Logger.getLogger(LOGGER_NAME);

    static {
        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(Level.INFO);
        LOGGER.addHandler(new FlushingStdoutHandler());
    }

    private RequestLog() {
    }

    /**
     * One request's log values.
     *
     * @param operation   DEBIT or CANCEL
     * @param accountId   account from the path
     * @param sequenceKey key, {@code null} if the body could not be parsed
     * @param isRetry     flag from the body, {@code null} if the body could not be parsed
     * @param decision    what this request did
     * @param outcome     answer outcome, {@code null} when no answer was produced (DROPPED)
     * @param fault       injected fault scenario name, {@code null} for none
     * @param latencyMs   processing time in milliseconds
     */
    public record Entry(
            Operation operation,
            String accountId,
            Long sequenceKey,
            Boolean isRetry,
            LogDecision decision,
            Outcome outcome,
            String fault,
            long latencyMs) {
    }

    /** Formats one entry as {@code key=value} pairs in a fixed order. */
    public static String format(Entry e) {
        return "op=" + e.operation()
                + " account=" + orDash(e.accountId())
                + " key=" + orDash(e.sequenceKey())
                + " is_retry=" + orDash(e.isRetry())
                + " decision=" + e.decision()
                + " outcome=" + orDash(e.outcome())
                + " fault=" + (e.fault() == null ? "none" : e.fault())
                + " latency_ms=" + e.latencyMs();
    }

    /** Writes one INFO line for the entry. */
    public static void log(Entry entry) {
        LOGGER.info(format(entry));
    }

    private static String orDash(Object value) {
        return value == null ? "-" : value.toString();
    }

    /** Writes to {@code System.out} and flushes after every line, so lines are never lost in a buffer. */
    private static final class FlushingStdoutHandler extends StreamHandler {

        FlushingStdoutHandler() {
            super(System.out, new OneLineFormatter());
        }

        @Override
        public synchronized void publish(LogRecord record) {
            super.publish(record);
            flush();
        }

        @Override
        public synchronized void close() {
            flush();
        }
    }
}
