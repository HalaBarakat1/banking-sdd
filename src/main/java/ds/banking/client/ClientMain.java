package ds.banking.client;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ds.banking.cli.Args;
import ds.banking.cli.UsageException;
import ds.banking.client.TransactionResult.Result;
import ds.banking.protocol.AccountIds;
import ds.banking.protocol.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * {@code java -jar banking-sdd.jar client ...}: the Order Service command line
 * (contracts/client-cli.md).
 *
 * <p>Implements: FR-022 (every retry limit is a flag), FR-024 and FR-025 (exit codes 3 for UNKNOWN
 * and 4 for REFUSED), FR-026 ({@code debit} and {@code recover} first resend every unresolved entry),
 * and the read-only {@code journal} command. Output: attempt lines on standard
 * error, one JSON line per result on standard output.
 */
public final class ClientMain {

    private static final Set<String> OPTIONS = Set.of("url", "db", "connect-timeout-ms", "timeout-ms", "attempts",
            "max-total-ms", "backoff-ms", "max-backoff-ms");
    private static final Set<String> COMMANDS = Set.of("debit", "cancel", "recover", "journal");

    static final String USAGE = """
            Usage: java -jar banking-sdd.jar client [--url http://127.0.0.1:8080] [--db data/client.db]
                     [--connect-timeout-ms 1000] [--timeout-ms 2000] [--attempts 6] [--max-total-ms 30000]
                     [--backoff-ms 100] [--max-backoff-ms 2000] <command>
            Commands:
              debit <account> <amount>   resend unresolved entries, then debit an amount (minor units)
              cancel <account> <key>      burn the key of an unresolved debit, then resend the rest
              recover                    resend every unresolved journal entry
              journal [account]          print journal entries (read-only)""";

    private ClientMain() {
    }

    /**
     * Parsed client options.
     *
     * @param url       Banking Service base URL
     * @param database  client journal file
     * @param policy    retry limits
     * @param command   sub-command
     * @param arguments sub-command arguments, already validated
     */
    public record Options(URI url, Path database, RetryPolicy policy, String command, List<String> arguments) {
    }

    public static Options parse(String[] args) throws UsageException {
        Args a = Args.parse(args, OPTIONS);
        RetryPolicy d = RetryPolicy.defaults();
        RetryPolicy policy;
        try {
            policy = new RetryPolicy(
                    millis(a, "connect-timeout-ms", d.connectTimeout()),
                    millis(a, "timeout-ms", d.responseTimeout()),
                    (int) a.number("attempts", d.maxAttempts(), 1, 10_000),
                    millis(a, "max-total-ms", d.maxTotal()),
                    millis(a, "backoff-ms", d.baseBackoff()),
                    millis(a, "max-backoff-ms", d.maxBackoff()));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
        URI url = parseUrl(a.text("url", "http://127.0.0.1:8080"));
        Path database = Path.of(a.text("db", Path.of("data", "client.db").toString()));
        List<String> rest = a.positional();
        if (rest.isEmpty()) {
            throw new UsageException("missing command");
        }
        String command = rest.get(0);
        List<String> arguments = List.copyOf(rest.subList(1, rest.size()));
        if (!COMMANDS.contains(command)) {
            throw new UsageException("unknown command " + command);
        }
        switch (command) {
            case "debit" -> {
                if (arguments.size() != 2) {
                    throw new UsageException("debit expects <account> <amount>");
                }
                requireAccount(arguments.get(0));
                Args.parseNumber("amount", arguments.get(1), 1, Long.MAX_VALUE);
            }
            case "cancel" -> {
                if (arguments.size() != 2) {
                    throw new UsageException("cancel expects <account> <key>");
                }
                requireAccount(arguments.get(0));
                Args.parseNumber("key", arguments.get(1), 1, Long.MAX_VALUE);
            }
            case "recover" -> {
                if (!arguments.isEmpty()) {
                    throw new UsageException("recover takes no arguments");
                }
            }
            case "journal" -> {
                if (arguments.size() > 1) {
                    throw new UsageException("journal expects at most one account");
                }
                if (arguments.size() == 1) {
                    requireAccount(arguments.get(0));
                }
            }
            default -> throw new UsageException("unknown command " + command);
        }
        return new Options(url, database, policy, command, arguments);
    }

    private static Duration millis(Args a, String name, Duration fallback) throws UsageException {
        return Duration.ofMillis(a.number(name, fallback.toMillis(), 1, Long.MAX_VALUE / 1_000_000));
    }

    private static URI parseUrl(String text) throws UsageException {
        try {
            URI uri = URI.create(text);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()) || uri.getHost() == null) {
                throw new UsageException("--url must be an http(s) URL with a host: " + text);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new UsageException("--url is not a valid URL: " + text);
        }
    }

    private static void requireAccount(String account) throws UsageException {
        if (!AccountIds.isValid(account)) {
            throw new UsageException("account must be 1-64 chars of [A-Za-z0-9_-]: " + account);
        }
    }

    /** 3 if any result is UNKNOWN, else 4 if any is REFUSED, else 0 (contracts/client-cli.md). */
    public static int exitCodeFor(Collection<Result> results) {
        if (results.contains(Result.UNKNOWN)) {
            return 3;
        }
        if (results.contains(Result.REFUSED)) {
            return 4;
        }
        return 0;
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = parse(args);
        } catch (UsageException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 2;
        }
        try {
            Path parent = options.database().toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return switch (options.command()) {
                case "debit" -> debit(options, out, err);
                case "cancel" -> cancel(options, out, err);
                case "recover" -> recover(options, out, err);
                case "journal" -> journal(options, out);
                default -> 2;
            };
        } catch (IOException | RuntimeException e) {
            err.println("unexpected error: " + e);
            return 1;
        }
    }

    /** Sending commands start by resending every unresolved entry (FR-026), then do their own work. */
    private static int debit(Options options, PrintStream out, PrintStream err) {
        PaymentClient client = PaymentClient.create(options.url(), options.database(), options.policy());
        List<TransactionResult> results = new ArrayList<>(client.recoverPending());
        results.add(client.debit(options.arguments().get(0), Long.parseLong(options.arguments().get(1))));
        return print(results, out, err);
    }

    /**
     * Saves the switch to cancel and sends it first (FR-027), so start-up recovery can never resend the
     * debit the user wants stopped; then resends any other unresolved entry (FR-026).
     */
    private static int cancel(Options options, PrintStream out, PrintStream err) {
        PaymentClient client = PaymentClient.create(options.url(), options.database(), options.policy());
        List<TransactionResult> results = new ArrayList<>();
        results.add(client.cancel(options.arguments().get(0), Long.parseLong(options.arguments().get(1))));
        results.addAll(client.recoverPending());
        return print(results, out, err);
    }

    private static int recover(Options options, PrintStream out, PrintStream err) {
        PaymentClient client = PaymentClient.create(options.url(), options.database(), options.policy());
        return print(client.recoverPending(), out, err);
    }

    /** One JSON line per result; the exit code covers all of them (contracts/client-cli.md). */
    private static int print(List<TransactionResult> results, PrintStream out, PrintStream err) {
        List<Result> outcomes = new ArrayList<>();
        for (TransactionResult result : results) {
            err.println(result.result() + ": " + result.message());
            out.println(new String(Json.write(resultJson(result)), StandardCharsets.UTF_8));
            outcomes.add(result.result());
        }
        return exitCodeFor(outcomes);
    }

    private static int journal(Options options, PrintStream out) {
        ClientJournal journal = new ClientJournal(options.database());
        String account = options.arguments().isEmpty() ? null : options.arguments().get(0);
        for (JournalEntry e : journal.entries()) {
            if (account == null || account.equals(e.accountId())) {
                out.println(new String(Json.write(entryJson(e)), StandardCharsets.UTF_8));
            }
        }
        return 0;
    }

    static ObjectNode resultJson(TransactionResult r) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("result", r.result().name());
        node.put("account_id", r.accountId());
        if (r.sequenceKey() != null) {
            node.put("sequence_key", r.sequenceKey());
        }
        if (r.outcome() != null) {
            node.put("outcome", r.outcome().name());
        }
        if (r.duplicate() != null) {
            node.put("duplicate", r.duplicate());
        }
        if (r.balanceAfter() != null) {
            node.put("balance_after", r.balanceAfter());
        }
        node.put("attempts", r.attempts());
        return node;
    }

    private static ObjectNode entryJson(JournalEntry e) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("sequence_key", e.sequenceKey());
        node.put("account_id", e.accountId());
        node.put("operation", e.operation().name());
        node.put("amount", e.amount());
        node.put("state", e.state().name());
        if (e.outcome() != null) {
            node.put("outcome", e.outcome().name());
        }
        if (e.balanceAfter() != null) {
            node.put("balance_after", e.balanceAfter());
        }
        node.put("attempts", e.attempts());
        node.put("created_at", e.createdAt());
        node.put("updated_at", e.updatedAt());
        return node;
    }
}
