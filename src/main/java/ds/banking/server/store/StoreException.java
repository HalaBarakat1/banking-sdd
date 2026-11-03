package ds.banking.server.store;

import java.sql.SQLException;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * A database failure. {@link #isBusy()} tells the HTTP layer to answer {@code 503 UNAVAILABLE}, which
 * the client retries (FR-004, research R4); any other failure becomes {@code 500}.
 */
public final class StoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StoreException(String message, SQLException cause) {
        super(message, cause);
    }

    /** True when the database lock could not be taken within {@code busy_timeout}. */
    public boolean isBusy() {
        if (getCause() instanceof SQLiteException e) {
            SQLiteErrorCode code = e.getResultCode();
            return code.name().startsWith("SQLITE_BUSY") || code.name().startsWith("SQLITE_LOCKED");
        }
        return false;
    }
}
