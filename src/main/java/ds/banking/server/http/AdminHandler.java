package ds.banking.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import ds.banking.protocol.InvalidRequestException;
import ds.banking.protocol.Json;
import ds.banking.server.fault.FaultConfig;
import ds.banking.server.fault.FaultInjector;
import ds.banking.server.store.LedgerStore;
import ds.banking.server.store.SeedResult;
import java.io.IOException;

/**
 * Administration endpoints for tests and demonstrations (contracts/http-api.md §4).
 *
 * <p>Implements: FR-029 and FR-030 ({@code /admin/faults} reads, replaces and clears the fault rules
 * at runtime), FR-031 ({@code POST /admin/accounts} seeds an account with a starting balance; the
 * spec assumes accounts exist before any client uses them). Never subject to faults. Stateless; safe
 * for concurrent use.
 */
public final class AdminHandler implements HttpHandler {

    /** Body of {@code POST /admin/accounts}. */
    record SeedRequest(String accountId, Long balance) {
    }

    private final LedgerStore store;
    private final FaultInjector faults;

    public AdminHandler(LedgerStore store, FaultInjector faults) {
        this.store = store;
        this.faults = faults;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        switch (path) {
            case "/admin/accounts" -> {
                if (method.equals("POST")) {
                    seed(exchange);
                } else {
                    HttpIo.sendMessage(exchange, 405, "use POST " + path);
                }
            }
            case "/admin/faults" -> faults(exchange, method);
            default -> HttpIo.sendMessage(exchange, 404, "no such endpoint: " + method + " " + path);
        }
    }

    /**
     * {@code GET} returns the rules, {@code PUT} replaces them as a whole (an invalid config is refused
     * and the previous one stays, EC-012), {@code DELETE} removes them all (FR-029, FR-030).
     */
    private void faults(HttpExchange exchange, String method) throws IOException {
        switch (method) {
            case "GET" -> HttpIo.sendJson(exchange, 200, Json.write(faults.config().toJson()));
            case "PUT" -> {
                FaultConfig config;
                try {
                    byte[] body = HttpIo.readBody(exchange);
                    if (body == null) {
                        throw new InvalidRequestException("request body too large");
                    }
                    config = FaultConfig.parse(body);
                } catch (InvalidRequestException e) {
                    HttpIo.sendOutcome(exchange, 400, "INVALID_REQUEST", e.getMessage());
                    return;
                }
                faults.configure(config);
                HttpIo.sendJson(exchange, 200, Json.write(config.toJson()));
            }
            case "DELETE" -> {
                faults.configure(FaultConfig.empty());
                HttpIo.sendJson(exchange, 200, Json.write(FaultConfig.empty().toJson()));
            }
            default -> HttpIo.sendMessage(exchange, 405, "use GET, PUT or DELETE /admin/faults");
        }
    }

    private void seed(HttpExchange exchange) throws IOException {
        SeedRequest request;
        try {
            byte[] body = HttpIo.readBody(exchange);
            if (body == null) {
                throw new InvalidRequestException("request body too large");
            }
            request = Json.parseStrict(body, SeedRequest.class);
        } catch (InvalidRequestException e) {
            HttpIo.sendOutcome(exchange, 400, "INVALID_REQUEST", e.getMessage());
            return;
        }
        SeedResult result;
        try {
            result = store.seedAccount(request.accountId(), request.balance());
        } catch (IllegalArgumentException e) {
            HttpIo.sendOutcome(exchange, 400, "INVALID_REQUEST", e.getMessage());
            return;
        }
        if (result == SeedResult.CONFLICT) {
            HttpIo.sendMessage(exchange, 409, "account " + request.accountId()
                    + " already exists with a different initial balance");
            return;
        }
        int status = result == SeedResult.CREATED ? 201 : 200;
        HttpIo.sendJson(exchange, status,
                Json.write(AccountQueryHandler.accountJson(store.readAccount(request.accountId()).orElseThrow())));
    }
}
