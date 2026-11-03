package ds.banking.server.log;

import java.time.Instant;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/** Formats a log record as {@code <UTC time> <LEVEL> <message>} on one line (NFR-004). */
public final class OneLineFormatter extends Formatter {

    @Override
    public String format(LogRecord record) {
        StringBuilder line = new StringBuilder()
                .append(Instant.ofEpochMilli(record.getMillis()))
                .append(' ')
                .append(record.getLevel().getName())
                .append(' ')
                .append(formatMessage(record))
                .append(System.lineSeparator());
        if (record.getThrown() != null) {
            line.append("  caused by: ").append(record.getThrown()).append(System.lineSeparator());
        }
        return line.toString();
    }
}
