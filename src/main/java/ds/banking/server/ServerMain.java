package ds.banking.server;

import ds.banking.cli.Args;
import ds.banking.cli.UsageException;
import ds.banking.protocol.AccountIds;
import ds.banking.server.store.SeedResult;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * {@code java -jar banking-sdd.jar server ...}: starts the Banking Service (contracts/client-cli.md).
 *
 * <p>Implements: FR-031 ({@code --seed} creates accounts with a starting balance before clients use
 * them, as the spec's assumptions require), NFR-002 ({@code --threads}). Runs until the process is
 * stopped.
 */
public final class ServerMain {

    private static final Set<String> OPTIONS = Set.of("port", "db", "threads", "seed");

    static final String USAGE = """
            Usage: java -jar banking-sdd.jar server [--port 8080] [--db data/bank.db] [--threads 32]
                                                    [--seed A=1000,B=500]""";

    private ServerMain() {
    }

    /**
     * Parsed server options.
     *
     * @param database SQLite file
     * @param port     listening port (0 = any free port)
     * @param threads  worker threads
     * @param seeds    accounts to create at start, in order
     */
    public record Options(Path database, int port, int threads, Map<String, Long> seeds) {
    }

    public static Options parse(String[] args) throws UsageException {
        Args a = Args.parse(args, OPTIONS);
        if (!a.positional().isEmpty()) {
            throw new UsageException("unexpected argument " + a.positional().get(0));
        }
        int port = (int) a.number("port", BankingServer.DEFAULT_PORT, 0, 65535);
        int threads = (int) a.number("threads", BankingServer.DEFAULT_THREADS, 1, 1024);
        Path database = Path.of(a.text("db", Path.of("data", "bank.db").toString()));
        return new Options(database, port, threads, parseSeeds(a.text("seed", "")));
    }

    private static Map<String, Long> parseSeeds(String text) throws UsageException {
        Map<String, Long> seeds = new LinkedHashMap<>();
        if (text.isEmpty()) {
            return Collections.unmodifiableMap(seeds);
        }
        for (String part : text.split(",")) {
            String[] kv = part.split("=", -1);
            if (kv.length != 2 || !AccountIds.isValid(kv[0])) {
                throw new UsageException("--seed expects ACCOUNT=BALANCE[,ACCOUNT=BALANCE...]: " + part);
            }
            seeds.put(kv[0], Args.parseNumber("--seed balance of " + kv[0], kv[1], 0, Long.MAX_VALUE));
        }
        return Collections.unmodifiableMap(seeds);
    }

    /** Starts the server and blocks until the process is stopped; returns an exit code on failure. */
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
            BankingServer server = BankingServer.start(BankingServer.Config.defaults(options.database())
                    .withPort(options.port())
                    .withThreads(options.threads()));
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "banking-shutdown"));
            for (Map.Entry<String, Long> seed : options.seeds().entrySet()) {
                SeedResult result = server.store().seedAccount(seed.getKey(), seed.getValue());
                out.println("seed " + seed.getKey() + "=" + seed.getValue() + ": " + result);
            }
            out.println("Banking Service listening on http://" + BankingServer.DEFAULT_HOST + ":" + server.port()
                    + " (database " + options.database().toAbsolutePath() + ")");
            new CountDownLatch(1).await();
            return 0;
        } catch (IOException e) {
            err.println("cannot start the server: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }
}
