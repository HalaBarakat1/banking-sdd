package ds.banking.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A fake Banking Service that answers with scripted replies, for client tests (research R9). Replies
 * are used in order; the last one repeats once the script runs out. Thread-safe.
 */
public final class StubBankServer implements AutoCloseable {

    /** One scripted reply. {@code delayMs} is waited before answering. */
    public record Reply(int status, String body, long delayMs) {

        public static Reply json(int status, String body) {
            return new Reply(status, body, 0);
        }
    }

    /** One received request. */
    public record Received(String method, String path, String body, String contentType) {
    }

    private final HttpServer http;
    private final ConcurrentLinkedQueue<Reply> script = new ConcurrentLinkedQueue<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Reply last = Reply.json(500, "{\"outcome\":\"UNAVAILABLE\"}");

    private StubBankServer() {
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "stub-bank");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    new String(body, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("Content-Type")));
            Reply reply = script.poll();
            if (reply == null) {
                reply = last;
            } else {
                last = reply;
            }
            try {
                if (reply.delayMs() > 0) {
                    Thread.sleep(reply.delayMs());
                }
                byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // the client gave up (timeout); nothing to answer
            } finally {
                exchange.close();
            }
        });
        http.start();
    }

    public static StubBankServer start(Reply... replies) {
        StubBankServer stub = new StubBankServer();
        stub.script.addAll(List.of(replies));
        return stub;
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + http.getAddress().getPort());
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    @Override
    public void close() {
        http.stop(0);
    }
}
