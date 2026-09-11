package tcc.aves.repository.dynamodb.support;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Codifica/decodifica o cursor opaco de paginação de avistamentos.
 *
 * <p>O cursor é relativo à combinação de filtros da consulta original
 * ({@code speciesId}/{@code from}/{@code to}) — reenviar o mesmo cursor com
 * filtros diferentes produz resultado indefinido e deve ser tratado como
 * entrada inválida pelo chamador.
 */
@Component
public class SightingCursorCodec {

    private final ObjectMapper objectMapper;

    public SightingCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Cursor usado pela consulta por espécie: só precisa da última chave nativa do DynamoDB. */
    public record SimpleKeyCursor(Map<String, String> lastEvaluatedKey) {
    }

    /** Cursor usado pela consulta por timeline: também guarda o bucket mensal atual. */
    public record TimelineCursor(String monthBucket, Map<String, String> lastEvaluatedKey) {
    }

    public record TimelineCursorState(String monthBucket, Map<String, AttributeValue> lastEvaluatedKey) {
    }

    public String encodeSimpleKey(Map<String, AttributeValue> lastEvaluatedKey) {
        return encode(new SimpleKeyCursor(toStringMap(lastEvaluatedKey)));
    }

    public Map<String, AttributeValue> decodeSimpleKey(String cursor) {
        if (isBlank(cursor)) {
            return null;
        }
        SimpleKeyCursor decoded = decode(cursor, SimpleKeyCursor.class);
        return fromStringMap(decoded.lastEvaluatedKey());
    }

    public String encodeTimelineCursor(String monthBucket, Map<String, AttributeValue> lastEvaluatedKey) {
        return encode(new TimelineCursor(monthBucket, toStringMap(lastEvaluatedKey)));
    }

    public TimelineCursorState decodeTimelineCursor(String cursor) {
        if (isBlank(cursor)) {
            return null;
        }
        TimelineCursor decoded = decode(cursor, TimelineCursor.class);
        return new TimelineCursorState(decoded.monthBucket(), fromStringMap(decoded.lastEvaluatedKey()));
    }

    private <T> String encode(T value) {
        byte[] json = objectMapper.writeValueAsBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    private <T> T decode(String cursor, Class<T> type) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cursor de paginação inválido");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Map<String, String> toStringMap(Map<String, AttributeValue> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, v.s()));
        return out;
    }

    private static Map<String, AttributeValue> fromStringMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Map<String, AttributeValue> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, AttributeValue.builder().s(v).build()));
        return out;
    }
}
