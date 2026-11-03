package ds.banking.protocol;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;

/**
 * JSON reading and writing for the wire contract (contracts/http-api.md).
 *
 * <p>Implements: FR-001, FR-002, FR-015 (snake_case fields), FR-012 and EC-004 (strict parsing).
 * Jackson's lenient defaults are switched off (research R8): {@code 2.5} is never turned into
 * {@code 2}, {@code "250"} is never turned into a number, and a missing or {@code null} field is
 * never defaulted. The mapper and readers are immutable after construction, so they are safe to share
 * between threads (constitution Principle III).
 */
public final class Json {

    private static final ObjectMapper MAPPER = buildMapper();

    /** Reader for request bodies: every field must be present and non-null. */
    private static final ObjectReader STRICT = MAPPER.reader()
            .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

    private Json() {
    }

    private static ObjectMapper buildMapper() {
        JsonMapper mapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
        mapper.coercionConfigDefaults()
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        return mapper;
    }

    /** Shared mapper, for code that builds or reads JSON trees (inspection and admin bodies). */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Parses and validates a debit body (FR-001); anything else is an {@link InvalidRequestException}. */
    public static DebitRequest parseDebit(byte[] body) throws InvalidRequestException {
        DebitRequest request = parseStrict(body, DebitRequest.class);
        requirePositive("sequence_key", request.sequenceKey());
        requirePositive("amount", request.amount());
        return request;
    }

    /** Parses and validates a cancel body (FR-015). */
    public static CancelRequest parseCancel(byte[] body) throws InvalidRequestException {
        CancelRequest request = parseStrict(body, CancelRequest.class);
        requirePositive("sequence_key", request.sequenceKey());
        return request;
    }

    /** Parses a body into {@code type} with the strict rules: no unknown, missing or null fields. */
    public static <T> T parseStrict(byte[] body, Class<T> type) throws InvalidRequestException {
        return read(STRICT, body, type);
    }

    /**
     * Parses a body whose fields are optional (fault rules, contracts/http-api.md §4): missing fields
     * become {@code null}, but unknown fields, floats for integers and strings for numbers are still
     * refused. Validation in the target's constructor is reported as an {@link InvalidRequestException}.
     */
    public static <T> T parse(byte[] body, Class<T> type) throws InvalidRequestException {
        return read(MAPPER.reader(), body, type);
    }

    private static <T> T read(ObjectReader reader, byte[] body, Class<T> type) throws InvalidRequestException {
        if (body == null || body.length == 0) {
            throw new InvalidRequestException("request body is empty");
        }
        try {
            return reader.forType(type).readValue(body);
        } catch (ValueInstantiationException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new InvalidRequestException("invalid request body: " + cause.getMessage(), e);
        } catch (MismatchedInputException e) {
            throw new InvalidRequestException(describe(e), e);
        } catch (JacksonException e) {
            throw new InvalidRequestException("request body is not valid JSON: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new InvalidRequestException("request body cannot be read: " + e.getMessage(), e);
        }
    }

    /**
     * Reads an answer written by {@link #write(Answer)}. Absent {@code amount}, {@code balance_after}
     * and {@code message} become {@code null}.
     */
    public static Answer parseAnswer(byte[] body) throws IOException {
        return MAPPER.readValue(body, Answer.class);
    }

    /** Writes an answer, leaving out fields that are {@code null} (FR-002). */
    public static byte[] write(Answer answer) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("account_id", answer.accountId());
        putIfPresent(node, "sequence_key", answer.sequenceKey());
        node.put("outcome", answer.outcome().name());
        if (answer.duplicate() != null) {
            node.put("duplicate", answer.duplicate());
        }
        putIfPresent(node, "amount", answer.amount());
        putIfPresent(node, "balance_after", answer.balanceAfter());
        if (answer.message() != null) {
            node.put("message", answer.message());
        }
        return write(node);
    }

    /** Writes any JSON tree. */
    public static byte[] write(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (JacksonException e) {
            throw new IllegalStateException("cannot serialize JSON tree", e);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, Long value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static void requirePositive(String field, Long value) throws InvalidRequestException {
        if (value <= 0) {
            throw new InvalidRequestException(field + " must be a positive whole number, got " + value);
        }
    }

    private static String describe(MismatchedInputException e) {
        String field = e.getPath().isEmpty() ? null : e.getPath().get(e.getPath().size() - 1).getFieldName();
        String reason = e.getOriginalMessage();
        return field == null ? "invalid request body: " + reason : "invalid field " + field + ": " + reason;
    }
}
