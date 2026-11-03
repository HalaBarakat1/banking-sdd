package ds.banking.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal {@code --name value} parser shared by the server and client sub-commands
 * (contracts/client-cli.md). Options come first; everything from the first non-option on is
 * positional. Unknown options and malformed numbers are a {@link UsageException}.
 */
public final class Args {

    private final Map<String, String> options;
    private final List<String> positional;

    private Args(Map<String, String> options, List<String> positional) {
        this.options = options;
        this.positional = positional;
    }

    public static Args parse(String[] args, Set<String> allowed) throws UsageException {
        Map<String, String> options = new HashMap<>();
        int i = 0;
        while (i < args.length && args[i].startsWith("--")) {
            String name = args[i].substring(2);
            if (!allowed.contains(name)) {
                throw new UsageException("unknown option --" + name);
            }
            if (i + 1 >= args.length) {
                throw new UsageException("missing value for --" + name);
            }
            options.put(name, args[i + 1]);
            i += 2;
        }
        List<String> positional = new ArrayList<>();
        for (; i < args.length; i++) {
            positional.add(args[i]);
        }
        return new Args(options, positional);
    }

    public List<String> positional() {
        return positional;
    }

    public String text(String name, String fallback) {
        return options.getOrDefault(name, fallback);
    }

    public long number(String name, long fallback, long min, long max) throws UsageException {
        String value = options.get(name);
        return value == null ? fallback : parseNumber("--" + name, value, min, max);
    }

    /** Parses a whole number in {@code [min, max]}. */
    public static long parseNumber(String what, String value, long min, long max) throws UsageException {
        long n;
        try {
            n = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new UsageException(what + " must be a whole number: " + value);
        }
        if (n < min || n > max) {
            throw new UsageException(what + " must be between " + min + " and " + max + ": " + value);
        }
        return n;
    }
}
