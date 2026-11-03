package ds.banking.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import ds.banking.protocol.InvalidRequestException;
import ds.banking.protocol.Json;
import ds.banking.server.store.LedgerStore;
import ds.banking.server.store.SeedResult;
import java.io.IOException;

/**
 * Administration endpoints for tests and demonstrations (contracts/http-api.md §4).
 *
 * <p>Implements: FR-031 ({@code POST /admin/accounts} seeds an account with a starting balance; the
 * spec assumes accounts exist before any client uses them). Never subject to faults. Stateless; safe
 * for concurrent use.
 */
public final class AdminHandler implements HttpHandler {

    /** Body of {@code POST /admin/accounts}. */
    record SeedRequest(String accountId, Long balance) {
    }

    private final LedgerStore store;

    public AdminHandler(LedgerStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/admin/accounts")) {
            HttpIo.sendMessage(exchange, 404, "no such endpoint: " + exchange.getRequestMethod() + " " + path);
            return;
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            HttpIo.sendMessage(exchange, 405, "use POST " + path);
            return;
        }
        seed(exchange);
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
