package ds.banking.cli;

/** Invalid command-line arguments; the program exits with code 2 (contracts/client-cli.md). */
public final class UsageException extends Exception {

    private static final long serialVersionUID = 1L;

    public UsageException(String message) {
        super(message);
    }
}
