package ds.banking.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import ds.banking.protocol.AccountIds;
import ds.banking.protocol.Answer;
import ds.banking.protocol.DebitRequest;
import ds.banking.protocol.InvalidRequestException;
import ds.banking.protocol.Json;
import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import ds.banking.server.decision.Decider;
import ds.banking.server.log.LogDecision;
import ds.banking.server.log.RequestLog;
import ds.banking.server.store.StoreException;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code POST /accounts/{id}/debits}: a thin HTTP adapter around the {@link Decider}; it holds no
 * business rules (plan.md, request flow).
 *
 * <p>Implements: FR-001 to FR-004 (contract, outcomes, status per operation and outcome), FR-012
 * (strict parsing before any database access; invalid input consumes no key), FR-013 (the answer is
 * written only after the decider returned, which is after {@code COMMIT}), NFR-004 (exactly one
 * request-log line per request). A lock wait beyond {@code busy_timeout} is answered {@code 503} and
 * any other unexpected failure {@code 500}, both with outcome {@code UNAVAILABLE}, so the client
 * retries (research R4).
 *
 * <p>Thread safety: no mutable fields; the decider is thread-safe.
 */
public final class OperationHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(OperationHandler.class.getName());

    private final Decider decider;

    public OperationHandler(Decider decider) {
        this.decider = decider;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String[] parts = exchange.getRequestURI().getPath().split("/");
        // parts: "", "accounts", id, "debits"
        if (parts.length != 4 || !parts[3].equals("debits")) {
            HttpIo.sendMessage(exchange, 404, "no such endpoint: POST " + exchange.getRequestURI().getPath());
            return;
        }
        debit(exchange, parts[2]);
    }

    private void debit(HttpExchange exchange, String accountId) throws IOException {
        long started = System.nanoTime();
        DebitRequest request;
        try {
            byte[] body = HttpIo.readBody(exchange);
            if (body == null) {
                throw new InvalidRequestException("request body too large");
            }
            request = Json.parseDebit(body);
        } catch (InvalidRequestException e) {
            Answer invalid = new Answer(accountId, null, Outcome.INVALID_REQUEST, null, null, null, e.getMessage());
            respond(exchange, StatusMapper.statusFor(Operation.DEBIT, Outcome.INVALID_REQUEST), invalid, null, started);
            return;
        }

        Answer answer;
        int status;
        if (!AccountIds.isValid(accountId)) {
            answer = new Answer(accountId, request.sequenceKey(), Outcome.UNKNOWN_ACCOUNT, false, null, null,
                    "no account " + accountId);
            status = StatusMapper.statusFor(Operation.DEBIT, answer.outcome());
        } else {
            try {
                answer = decider.debit(accountId, request);
                status = StatusMapper.statusFor(Operation.DEBIT, answer.outcome());
            } catch (StoreException e) {
                boolean busy = e.isBusy();
                if (!busy) {
                    LOG.log(Level.WARNING, "database failure for " + accountId, e);
                }
                answer = unavailable(accountId, request, busy ? "database busy, retry" : "database failure, retry");
                status = busy ? 503 : 500;
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "unexpected failure for " + accountId, e);
                answer = unavailable(accountId, request, "unexpected server error, retry");
                status = 500;
            }
        }
        respond(exchange, status, answer, request, started);
    }

    private static Answer unavailable(String accountId, DebitRequest request, String message) {
        return new Answer(accountId, request.sequenceKey(), Outcome.UNAVAILABLE, false, null, null, message);
    }

    private static void respond(HttpExchange exchange, int status, Answer answer, DebitRequest request, long started)
            throws IOException {
        HttpIo.sendJson(exchange, status, Json.write(answer));
        RequestLog.log(new RequestLog.Entry(
                Operation.DEBIT,
                answer.accountId(),
                request == null ? null : request.sequenceKey(),
                request == null ? null : request.isRetry(),
                LogDecision.of(answer.outcome(), Boolean.TRUE.equals(answer.duplicate())),
                answer.outcome(),
                null,
                (System.nanoTime() - started) / 1_000_000));
    }
}
