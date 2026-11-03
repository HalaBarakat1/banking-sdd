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
import ds.banking.server.fault.FaultInjector;
import ds.banking.server.fault.FaultRule;
import ds.banking.server.fault.FaultScenario;
import ds.banking.server.log.LogDecision;
import ds.banking.server.log.RequestLog;
import ds.banking.server.store.StoreException;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
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
 * <p>Fault hooks (FR-028, research R5): after parsing and validation the injector chooses at most one
 * scenario. Before the decision: FS-1 closes the connection without deciding or answering, FS-2 does
 * the same and asks the server to crash-restart, FS-4a waits. After the commit: FS-3 closes the
 * connection without answering, FS-4b waits before answering. The decider never sees the injector
 * (NFR-005).
 *
 * <p>Thread safety: no mutable fields; the decider and the injector are thread-safe.
 */
public final class OperationHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(OperationHandler.class.getName());

    private final Decider decider;
    private final FaultInjector faults;
    private final Consumer<Duration> crash;

    /**
     * @param decider the decision rules
     * @param faults  the fault injector (empty by default)
     * @param crash   called for FS-2 with the configured downtime; the server stops and restarts
     */
    public OperationHandler(Decider decider, FaultInjector faults, Consumer<Duration> crash) {
        this.decider = decider;
        this.faults = faults;
        this.crash = crash;
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
            respond(exchange, StatusMapper.statusFor(Operation.DEBIT, Outcome.INVALID_REQUEST), invalid, null, null,
                    started);
            return;
        }
        if (!AccountIds.isValid(accountId)) {
            Answer unknown = new Answer(accountId, request.sequenceKey(), Outcome.UNKNOWN_ACCOUNT, false, null, null,
                    "no account " + accountId);
            respond(exchange, StatusMapper.statusFor(Operation.DEBIT, unknown.outcome()), unknown, request, null,
                    started);
            return;
        }

        Optional<FaultRule> fault = faults.choose(accountId, Operation.DEBIT);
        if (fault.isPresent() && fault.get().scenario().beforeDecision()) {
            FaultRule rule = fault.get();
            switch (rule.scenario()) {
                case DROP_REQUEST -> {
                    dropped(accountId, request, rule, started);
                    return;
                }
                case CRASH_BEFORE_EXECUTE -> {
                    dropped(accountId, request, rule, started);
                    crash.accept(Duration.ofMillis(rule.downtimeMs()));
                    return;
                }
                case DELAY_BEFORE_EXECUTE -> {
                    if (!pause(rule)) {
                        return;
                    }
                }
                default -> throw new IllegalStateException("not a before-decision scenario: " + rule.scenario());
            }
        }

        Answer answer;
        int status;
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

        FaultRule after = fault.filter(r -> !r.scenario().beforeDecision()).orElse(null);
        if (after != null && after.scenario() == FaultScenario.DROP_RESPONSE) {
            // FS-3: the decision is committed; close the connection without an answer.
            log(answer, request, fault.get(), started);
            return;
        }
        if (after != null && after.scenario() == FaultScenario.DELAY_AFTER_EXECUTE && !pause(after)) {
            return;
        }
        respond(exchange, status, answer, request, fault.orElse(null), started);
    }

    /** Waits for a delay scenario; false if the server is shutting down (the request is then dropped). */
    private static boolean pause(FaultRule rule) {
        try {
            Thread.sleep(rule.delayMs());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Answer unavailable(String accountId, DebitRequest request, String message) {
        return new Answer(accountId, request.sequenceKey(), Outcome.UNAVAILABLE, false, null, null, message);
    }

    private static void respond(HttpExchange exchange, int status, Answer answer, DebitRequest request,
            FaultRule fault, long started) throws IOException {
        HttpIo.sendJson(exchange, status, Json.write(answer));
        log(answer, request, fault, started);
    }

    private static void log(Answer answer, DebitRequest request, FaultRule fault, long started) {
        RequestLog.log(new RequestLog.Entry(
                Operation.DEBIT,
                answer.accountId(),
                request == null ? null : request.sequenceKey(),
                request == null ? null : request.isRetry(),
                LogDecision.of(answer.outcome(), Boolean.TRUE.equals(answer.duplicate())),
                answer.outcome(),
                fault == null ? null : fault.scenario().name(),
                elapsedMs(started)));
    }

    /** FS-1 / FS-2: no decision was made; the exchange is closed without a response by the caller. */
    private static void dropped(String accountId, DebitRequest request, FaultRule fault, long started) {
        RequestLog.log(new RequestLog.Entry(Operation.DEBIT, accountId, request.sequenceKey(), request.isRetry(),
                LogDecision.DROPPED, null, fault.scenario().name(), elapsedMs(started)));
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
