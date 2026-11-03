package ds.banking.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.client.AttemptResult;
import ds.banking.client.BankingHttpClient;
import ds.banking.client.RetryPolicy;
import ds.banking.protocol.Answer;
import ds.banking.server.store.LedgerStore;
import ds.banking.server.store.RecordRow;
import ds.banking.support.Invariants;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real server process is killed under load and restarted on the same database (T045, verification
 * test; SC-004, NFR-001, EC-011). Requests are sent raw, so only {@link Invariants#check} applies.
 */
@Tag("process")
class ServerProcessCrashIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @TempDir
    Path dir;

    private Process server;

    @AfterEach
    void killServer() throws InterruptedException {
        if (server != null && server.isAlive()) {
            server.destroyForcibly().waitFor();
        }
    }

    /**
     * A jar with only a manifest: Main-Class and the test class path as file URLs. Launching it with
     * {@code java -jar} keeps every command-line argument ASCII; on Windows the Java launcher may
     * mangle non-ASCII characters (such as the Arabic letters in this project's path) in arguments.
     */
    private Path launcherJar() throws IOException {
        List<String> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                urls.add(Path.of(entry).toUri().toString());
            }
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "ds.banking.Main");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, String.join(" ", urls));
        Path jar = dir.resolve("launcher.jar");
        try (OutputStream out = Files.newOutputStream(jar)) {
            new JarOutputStream(out, manifest).close(); // the manifest is all we need
        }
        return jar;
    }

    private Process startServer(Path jar, Path db, int port) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-jar", jar.toString(), "server",
                "--port", String.valueOf(port), "--db", db.toString(), "--seed", "A=1000000");
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("server.log").toFile()));
        Process p = builder.start();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (!p.isAlive()) {
                throw new AssertionError("server process exited: " + Files.readString(dir.resolve("server.log")));
            }
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/accounts/A")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return p;
                }
            } catch (IOException notYet) {
                // still starting
            }
            Thread.sleep(100);
        }
        throw new AssertionError("server did not become ready: " + Files.readString(dir.resolve("server.log")));
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    @DisplayName("SC-004 / NFR-001 / EC-011 / FR-013: after kill -9 under load and a restart, every answered decision is still there and replays unchanged")
    void answeredDecisionsSurviveKill() throws Exception {
        Path jar = launcherJar();
        Path db = dir.resolve("bank.db");
        int port = freePort();
        server = startServer(jar, db, port);
        BankingHttpClient raw = new BankingHttpClient(URI.create("http://127.0.0.1:" + port), RetryPolicy.defaults());

        Map<Long, Answer> answered = new ConcurrentHashMap<>();
        AtomicBoolean stop = new AtomicBoolean();
        Thread load = new Thread(() -> {
            for (long key = 1; !stop.get(); key++) {
                AttemptResult r = raw.debit("A", key, 10, false, TIMEOUT);
                if (!(r instanceof AttemptResult.Final done)) {
                    return; // the server is gone; this key's fate is unknown, which is allowed
                }
                answered.put(key, done.answer());
            }
        }, "load");
        load.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (answered.size() < 40 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        server.destroyForcibly().waitFor();
        stop.set(true);
        load.join(10_000);
        assertTrue(answered.size() >= 40, "only " + answered.size() + " answers before the kill");

        server = startServer(jar, db, port);

        // Through the restarted server: every answered key must replay its original answer.
        for (Map.Entry<Long, Answer> e : answered.entrySet()) {
            AttemptResult.Final replay = assertInstanceOf(AttemptResult.Final.class,
                    raw.debit("A", e.getKey(), 10, true, TIMEOUT));
            assertTrue(replay.answer().duplicate(), "answered key " + e.getKey() + " was lost by the crash: " + replay);
            assertEquals(e.getValue().outcome(), replay.answer().outcome());
            assertEquals(e.getValue().balanceAfter(), replay.answer().balanceAfter());
        }

        // In the database file: the records and the money invariant.
        LedgerStore store = new LedgerStore(db);
        Invariants.check(store, "A");
        Map<Long, RecordRow> records = new ConcurrentHashMap<>();
        store.readRecords("A").forEach(r -> records.put(r.sequenceKey(), r));
        for (Map.Entry<Long, Answer> e : answered.entrySet()) {
            RecordRow record = Optional.ofNullable(records.get(e.getKey()))
                    .orElseThrow(() -> new AssertionError("answered key " + e.getKey() + " has no record"));
            assertEquals(e.getValue().outcome(), record.outcome());
            assertEquals(e.getValue().balanceAfter(), record.balanceAfter());
        }
        assertEquals(1_000_000 - 10L * records.size(), store.readAccount("A").orElseThrow().balance());
    }
}
