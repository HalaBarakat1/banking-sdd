package ds.banking.cli;

/**
 * Invalid command-line arguments, such as a bad retry limit (FR-022) or seed (FR-031); the program
 * exits with code 2 (contracts/client-cli.md).
 */
public final class UsageException extends Exception {

    private static final long serialVersionUID = 1L;

    public UsageException(String message) {
        super(message);
    }
}
