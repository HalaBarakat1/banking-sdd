package ds.banking.server;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import ds.banking.server.decision.Decider;
import ds.banking.server.decision.DecisionService;
import ds.banking.server.http.AccountQueryHandler;
import ds.banking.server.http.AdminHandler;
import ds.banking.server.http.HttpIo;
import ds.banking.server.http.OperationHandler;
import ds.banking.server.store.LedgerStore;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Banking Service process: an HTTP listener in front of the ledger store.
 *
 * <p>Implements: NFR-002 (a fixed pool of worker threads serves requests in parallel; research R1),
 * NFR-001 (all money state lives in the database file, so a new server on the same file continues
 * where the old one stopped), research R11 (rebinding the same port is retried for up to 2 s).
 *
 * <p>Thread safety: the fields are written once in the constructor and read afterwards; requests are
 * handled by the pool threads and share only the stateless {@link LedgerStore}.
 */
public final class BankingServer implements AutoCloseable {

    public static final int DEFAULT_THREADS = 32;
    public static final int DEFAULT_PORT = 8080;
    public static final String DEFAULT_HOST = "127.0.0.1";

    private static final Logger LOG = Logger.getLogger(BankingServer.class.getName());
    private static final Duration REBIND_WINDOW = Duration.ofSeconds(2);
    private static final long REBIND_PAUSE_MS = 50;

    /**
     * Server settings.
     *
     * @param database      SQLite file of the ledger
     * @param host          address to listen on (loopback by default)
     * @param port          port; 0 picks a free one
     * @param threads       worker threads
     * @param busyTimeoutMs how long a request waits for the database lock before answering 503
     * @param deciders      builds the decider on the store; the real rules unless a test replaces them
     */
    public record Config(Path database, String host, int port, int threads, int busyTimeoutMs,
            Function<LedgerStore, Decider> deciders) {

        public static Config defaults(Path database) {
            return new Config(database, DEFAULT_HOST, DEFAULT_PORT, DEFAULT_THREADS,
                    LedgerStore.DEFAULT_BUSY_TIMEOUT_MS, DecisionService::new);
        }

        public Config withPort(int newPort) {
            return new Config(database, host, newPort, threads, busyTimeoutMs, deciders);
        }

        public Config withThreads(int newThreads) {
            return new Config(database, host, port, newThreads, busyTimeoutMs, deciders);
        }

        public Config withBusyTimeoutMs(int newBusyTimeoutMs) {
            return new Config(database, host, port, threads, newBusyTimeoutMs, deciders);
        }

        /** Test seam: replaces the decision rules (research R9, the 500 path). */
        public Config withDecider(Function<LedgerStore, Decider> newDeciders) {
            return new Config(database, host, port, threads, busyTimeoutMs, newDeciders);
        }
    }

    private final Config config;
    private final LedgerStore store;
    private final HttpServer http;
    private final ExecutorService pool;

    private BankingServer(Config config) throws IOException {
        this.config = config;
        this.store = new LedgerStore(config.database(), config.busyTimeoutMs());
        store.initSchema();
        this.http = bind(new InetSocketAddress(config.host(), config.port()));
        this.pool = Executors.newFixedThreadPool(config.threads(), threadFactory());
        http.setExecutor(pool);
        HttpHandler query = new AccountQueryHandler(store);
        HttpHandler operations = new OperationHandler(config.deciders().apply(store));
        http.createContext("/", exchange -> HttpIo.sendMessage(exchange, 404,
                "no such endpoint: " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()));
        http.createContext("/accounts/", guarded(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                query.handle(exchange);
            } else if (exchange.getRequestMethod().equals("POST")) {
                operations.handle(exchange);
            } else {
                HttpIo.sendMessage(exchange, 405, "method not allowed: " + exchange.getRequestMethod());
            }
        }));
        http.createContext("/admin/", guarded(new AdminHandler(store)));
        http.start();
    }

    /**
     * Wraps a handler so that an unexpected exception still produces a JSON answer: {@code 500} with
     * {@code outcome = UNAVAILABLE}, which the client retries (FR-004, constitution Principle IV).
     */
    private static HttpHandler guarded(HttpHandler handler) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "unexpected error in " + exchange.getRequestURI(), e);
                HttpIo.sendOutcome(exchange, 500, "UNAVAILABLE", "unexpected server error");
            } finally {
                exchange.close();
            }
        };
    }

    /** Creates the schema if needed and starts listening. */
    public static BankingServer start(Config config) throws IOException {
        return new BankingServer(config);
    }

    private static HttpServer bind(InetSocketAddress address) throws IOException {
        long deadline = System.nanoTime() + REBIND_WINDOW.toNanos();
        while (true) {
            try {
                return HttpServer.create(address, 128);
            } catch (BindException e) {
                if (System.nanoTime() >= deadline) {
                    throw e;
                }
                try {
                    Thread.sleep(REBIND_PAUSE_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread t = new Thread(task, "banking-http-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public int threads() {
        return config.threads();
    }

    public Config config() {
        return config;
    }

    public LedgerStore store() {
        return store;
    }

    /** Stops listening immediately and stops the worker threads. */
    public void stop() {
        http.stop(0);
        pool.shutdownNow();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }
}
