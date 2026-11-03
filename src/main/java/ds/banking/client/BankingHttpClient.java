package ds.banking.client;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ds.banking.protocol.Answer;
import ds.banking.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;

/**
 * Sends one attempt to the Banking Service and classifies the result (research R4).
 *
 * <p>Implements: FR-004 ({@code 2xx} and {@code 4xx} are final, a {@code 4xx} even when its body cannot
 * be parsed; any {@code 5xx}, a timeout, a connection failure or an unparseable {@code 2xx} is
 * retryable), FR-022 (every attempt has a connect and a response time limit). Retrying is not done
 * here; see {@link PaymentClient}.
 *
 * <p>Thread safety: {@link HttpClient} is thread-safe; the other fields are immutable.
 */
public final class BankingHttpClient {

    private final URI baseUri;
    private final HttpClient http;

    public BankingHttpClient(URI baseUri, RetryPolicy policy) {
        this.baseUri = baseUri;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(policy.connectTimeout())
                .build();
    }

    /** One debit attempt with the given response timeout. */
    public AttemptResult debit(String accountId, long sequenceKey, long amount, boolean isRetry, Duration timeout) {
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("sequence_key", sequenceKey);
        body.put("amount", amount);
        body.put("is_retry", isRetry);
        return post("/accounts/" + accountId + "/debits", body, timeout);
    }

    /** One cancel attempt with the given response timeout (FR-015). */
    public AttemptResult cancel(String accountId, long sequenceKey, boolean isRetry, Duration timeout) {
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("sequence_key", sequenceKey);
        body.put("is_retry", isRetry);
        return post("/accounts/" + accountId + "/cancellations", body, timeout);
    }

    private AttemptResult post(String path, ObjectNode body, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.write(body)))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            return new AttemptResult.Retryable("timed out: " + e.getMessage());
        } catch (IOException e) {
            return new AttemptResult.Retryable("no response: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AttemptResult.Retryable("interrupted");
        }
        return classify(response.statusCode(), response.body());
    }

    private static AttemptResult classify(int status, byte[] body) {
        if (status >= 500) {
            return new AttemptResult.Retryable("HTTP " + status);
        }
        Answer answer = parse(body);
        if (status >= 400 && status < 500) {
            return new AttemptResult.Final(status, answer);
        }
        if (status >= 200 && status < 300 && answer != null) {
            return new AttemptResult.Final(status, answer);
        }
        return new AttemptResult.Retryable("HTTP " + status + " without a usable answer");
    }

    private static Answer parse(byte[] body) {
        try {
            Answer answer = Json.parseAnswer(body);
            return answer.outcome() == null ? null : answer;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public HttpClient.Version version() {
        return http.version();
    }

    public Optional<Duration> connectTimeout() {
        return http.connectTimeout();
    }
}
