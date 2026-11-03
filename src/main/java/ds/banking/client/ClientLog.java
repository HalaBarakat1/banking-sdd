package ds.banking.client;

import ds.banking.protocol.Operation;
import java.time.Instant;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

/**
 * The client's attempt log: one line per attempt (NFR-004: "the client MUST log every attempt and its
 * result"). Lines go to standard error, so the final JSON result on standard output stays clean for
 * scripts (contracts/client-cli.md). {@code java.util.logging} only; thread-safe.
 */
public final class ClientLog {

    public static final String LOGGER_NAME = "ds.banking.client.attempt";

    private static final Logger LOGGER = Logger.getLogger(LOGGER_NAME);

    static {
        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(Level.INFO);
        LOGGER.addHandler(new StreamHandler(System.err, new Formatter() {
            @Override
            public String format(LogRecord record) {
                return Instant.ofEpochMilli(record.getMillis()) + " " + record.getLevel().getName() + " "
                        + formatMessage(record) + System.lineSeparator();
            }
        }) {
            @Override
            public synchronized void publish(LogRecord record) {
                super.publish(record);
                flush();
            }
        });
    }

    private ClientLog() {
    }

    /** The attempt logger; tests attach handlers here (see {@code RequestLog.logger()} for why). */
    public static Logger logger() {
        return LOGGER;
    }

    /** Logs one attempt; {@code attempt} counts within the current send run. */
    static void attempt(Operation operation, String accountId, long key, int attempt, boolean isRetry,
            AttemptResult result) {
        String outcome = switch (result) {
            case AttemptResult.Final f -> "result=final status=" + f.status() + " outcome="
                    + (f.answer() == null ? "-" : f.answer().outcome())
                    + (f.answer() == null || f.answer().duplicate() == null ? "" : " duplicate=" + f.answer().duplicate());
            case AttemptResult.Retryable r -> "result=retryable reason=" + r.reason();
        };
        LOGGER.info("op=" + operation + " account=" + accountId + " key=" + key + " attempt=" + attempt
                + " is_retry=" + isRetry + " " + outcome);
    }

    /** Logs a decision that sent nothing (local refusal, run exhausted). */
    static void note(String message) {
        LOGGER.info(message);
    }
}
