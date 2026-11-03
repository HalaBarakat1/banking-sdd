package ds.banking;

import java.io.PrintStream;

/**
 * Entry point of {@code banking-sdd.jar}: dispatches to the {@code server} and {@code client}
 * sub-commands (contracts/client-cli.md).
 *
 * <p>Implements: SC-002 (one runnable artifact). Exit code {@value #EXIT_INVALID_ARGUMENTS} means
 * invalid arguments, as in the CLI contract's exit-code table.
 */
public final class Main {

    /** Exit code for invalid arguments (contracts/client-cli.md). */
    public static final int EXIT_INVALID_ARGUMENTS = 2;

    private static final String USAGE = """
            Usage:
              java -jar banking-sdd.jar server [options]   start the Banking Service
              java -jar banking-sdd.jar client [options] <command>   run the Order Service client
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs one sub-command and returns its exit code, without calling {@link System#exit}, so tests
     * can drive it.
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.print(USAGE);
            return EXIT_INVALID_ARGUMENTS;
        }
        String command = args[0];
        err.println("Unknown sub-command: " + command);
        err.print(USAGE);
        return EXIT_INVALID_ARGUMENTS;
    }
}
