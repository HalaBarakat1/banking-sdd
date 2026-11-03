package ds.banking.server.http;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import ds.banking.protocol.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Small helpers shared by the HTTP handlers: read a bounded body, write a JSON response
 * (contracts/http-api.md: every body is {@code application/json; charset=utf-8}).
 */
public final class HttpIo {

    /** Largest request body accepted; contract bodies are a few dozen bytes. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private HttpIo() {
    }

    /** Reads the request body, or returns {@code null} if it is larger than {@link #MAX_BODY_BYTES}. */
    public static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            return body.length > MAX_BODY_BYTES ? null : body;
        }
    }

    /** Sends a JSON response and closes the exchange. */
    public static void sendJson(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** Sends {@code {"message": ...}} with the given status. */
    public static void sendMessage(HttpExchange exchange, int status, String message) throws IOException {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("message", message);
        sendJson(exchange, status, Json.write(node));
    }

    /** Sends {@code {"outcome": ..., "message": ...}} with the given status. */
    public static void sendOutcome(HttpExchange exchange, int status, String outcome, String message)
            throws IOException {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("outcome", outcome);
        node.put("message", message);
        sendJson(exchange, status, Json.write(node));
    }
}
