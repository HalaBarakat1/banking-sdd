package ds.banking.protocol;

/** A request body that breaks the contract; answered with {@code INVALID_REQUEST} (FR-012, EC-004). */
public final class InvalidRequestException extends Exception {

    private static final long serialVersionUID = 1L;

    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
