package ds.banking.server;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import ds.banking.server.decision.Decider;
import ds.banking.server.decision.DecisionService;
import ds.banking.server.fault.FaultInjector;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Banking Service process: an HTTP listener in front of the ledger store.
 *
 * <p>Implements: NFR-002 (a fixed pool of worker threads serves requests in parallel; research R1),
 * NFR-001 (all money state lives in the database file, so a new server on the same file continues
 * where the old one stopped), FR-028 FS-2 ({@link #crashAndRestart}), research R11 (rebinding the
 * same port is retried for up to 2 s).
 *
 * <p>Thread safety: the current {@code Running} value is replaced only under the {@code lifecycle} lock
 * and published through a volatile field; the restart is single-flight through an
 * {@link AtomicBoolean}. Requests share only the stateless {@link LedgerStore} and the thread-safe
 * {@link FaultInjector}.
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

    /**
     * Everything a real process crash would lose: the listener, its worker threads, the store object
     * and the handlers. Money state is not here; it is in the database file.
     */
    private record Running(HttpServer http, ExecutorService pool, LedgerStore store) {
    }

    private final Config config;
    private final int port;
    /** Test infrastructure, not server state: kept across a crash-restart (FR-029, research R5). */
    private final FaultInjector faults = new FaultInjector();
    private final Object lifecycle = new Object();
    private final AtomicBoolean restarting = new AtomicBoolean();
    private final AtomicInteger restarts = new AtomicInteger();
    private volatile Running running;
    private volatile boolean closed;

    private BankingServer(Config config) throws IOException {
        this.config = config;
        this.running = startRunning(config.port());
        this.port = running.http().getAddress().getPort();
    }

    private Running startRunning(int listenPort) throws IOException {
        LedgerStore store = new LedgerStore(config.database(), config.busyTimeoutMs());
        store.initSchema();
        HttpServer http = bind(new InetSocketAddress(config.host(), listenPort));
        ExecutorService pool = Executors.newFixedThreadPool(config.threads(), threadFactory());
        http.setExecutor(pool);
        HttpHandler query = new AccountQueryHandler(store);
        HttpHandler operations = new OperationHandler(config.deciders().apply(store), faults, this::crashAndRestart);
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
        http.createContext("/admin/", guarded(new AdminHandler(store, faults)));
        http.start();
        return new Running(http, pool, store);
    }

    /**
     * FS-2 (FR-028, research R5): stops listening at once, discards every in-memory server object, waits
     * {@code downtime}, and starts a new listener with new objects on the same database file and port.
     * Everything the server knows about money afterwards comes from the database, as after a real crash.
     *
     * <p>Runs on its own thread, never on a worker of the listener it stops, and is single-flight: a
     * second crash requested during a restart only drops its own request. Requests already inside a
     * transaction either commit (then their client sees FS-3) or are rolled back by SQLite; both are safe.
     */
    public void crashAndRestart(Duration downtime) {
        if (!restarting.compareAndSet(false, true)) {
            return;
        }
        Thread restart = new Thread(() -> {
            try {
                Running old;
                synchronized (lifecycle) {
                    if (closed) {
                        return;
                    }
                    old = running;
                }
                LOG.info("FS-2 crash: server stopped, restarting in " + downtime.toMillis() + " ms");
                stopRunning(old);
                Thread.sleep(downtime.toMillis());
                synchronized (lifecycle) {
                    if (closed) {
                        return;
                    }
                    running = startRunning(port);
                    restarts.incrementAndGet();
                }
                LOG.info("FS-2 crash: server restarted on port " + port);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "FS-2 crash: restart failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                restarting.set(false);
            }
        }, "banking-crash-restart");
        restart.setDaemon(true);
        restart.start();
    }

    /** Number of completed FS-2 crash-restarts. */
    public int restarts() {
        return restarts.get();
    }

    private static void stopRunning(Running r) {
        try {
            r.http().stop(0);
        } catch (IllegalStateException alreadyStopped) {
            // stopped by an earlier crash; nothing to do
        }
        r.pool().shutdownNow();
        try {
            r.pool().awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    /** The listening port; the same before and after a crash-restart. */
    public int port() {
        return port;
    }

    public int threads() {
        return config.threads();
    }

    public Config config() {
        return config;
    }

    /** The fault injector; empty unless configured through /admin/faults (FR-030). */
    public FaultInjector faults() {
        return faults;
    }

    /** The store of the current run (a new object after each crash-restart, on the same file). */
    public LedgerStore store() {
        return running.store();
    }

    /** Stops listening immediately and stops the worker threads; no restart happens afterwards. */
    public void stop() {
        synchronized (lifecycle) {
            closed = true;
            stopRunning(running);
        }
    }

    @Override
    public void close() {
        stop();
    }
}
