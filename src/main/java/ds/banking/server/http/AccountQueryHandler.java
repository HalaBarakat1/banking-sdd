package ds.banking.server.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import ds.banking.protocol.AccountIds;
import ds.banking.protocol.Json;
import ds.banking.server.store.AccountRow;
import ds.banking.server.store.LedgerStore;
import ds.banking.server.store.RecordRow;
import java.io.IOException;
import java.util.Optional;

/**
 * {@code GET /accounts/{id}} and {@code GET /accounts/{id}/transactions} (contracts/http-api.md §3).
 *
 * <p>Implements: FR-031 (inspection for tests and demonstrations). Read-only and never subject to
 * faults. Stateless; safe for concurrent use.
 */
public final class AccountQueryHandler implements HttpHandler {

    private final LedgerStore store;

    public AccountQueryHandler(LedgerStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String[] parts = exchange.getRequestURI().getPath().split("/");
        // parts: "", "accounts", id [, "transactions"]
        boolean account = parts.length == 3;
        boolean transactions = parts.length == 4 && parts[3].equals("transactions");
        if (!account && !transactions) {
            HttpIo.sendMessage(exchange, 404, "no such endpoint: GET " + exchange.getRequestURI().getPath());
            return;
        }
        String accountId = parts[2];
        Optional<AccountRow> row = AccountIds.isValid(accountId) ? store.readAccount(accountId) : Optional.empty();
        if (row.isEmpty()) {
            HttpIo.sendOutcome(exchange, 404, "UNKNOWN_ACCOUNT", "no account " + accountId);
            return;
        }
        if (account) {
            HttpIo.sendJson(exchange, 200, Json.write(accountJson(row.get())));
        } else {
            ArrayNode list = Json.mapper().createArrayNode();
            for (RecordRow r : store.readRecords(accountId)) {
                list.add(recordJson(r));
            }
            HttpIo.sendJson(exchange, 200, Json.write(list));
        }
    }

    /** The account body shared with the seeding endpoint. */
    static ObjectNode accountJson(AccountRow a) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("account_id", a.accountId());
        node.put("initial_balance", a.initialBalance());
        node.put("balance", a.balance());
        node.put("last_sequence_key", a.lastSequenceKey());
        return node;
    }

    private static ObjectNode recordJson(RecordRow r) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("sequence_key", r.sequenceKey());
        node.put("operation", r.operation().name());
        if (r.amount() != null) {
            node.put("amount", r.amount());
        }
        node.put("outcome", r.outcome().name());
        node.put("balance_after", r.balanceAfter());
        node.put("decided_at", r.decidedAt());
        return node;
    }
}
