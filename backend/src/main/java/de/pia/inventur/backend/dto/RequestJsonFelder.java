package de.pia.inventur.backend.dto;

import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Gemeinsame strikte Syntaxpruefung der Schreibrequests, keine fachliche Autorisierung. */
public final class RequestJsonFelder {
    private RequestJsonFelder() {}
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public static JsonNode leseObjekt(byte[] json, int feldanzahl) {
        var root = JSON.readTree(json);
        if (root == null || !root.isObject() || root.size() != feldanzahl) throw ungueltig();
        return root;
    }

    public static JsonNode feld(JsonNode root, String name) {
        var value = root.get(name);
        if (value == null) throw ungueltig();
        return value;
    }

    public static long zahl(JsonNode root, String name, long minimum) {
        var value = feld(root, name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum) throw ungueltig();
        return value.longValue();
    }

    public static boolean bestaetigung(JsonNode root, String name) {
        var value = feld(root, name);
        if (!value.isBoolean()) throw ungueltig();
        return value.booleanValue();
    }

    public static UUID uuid(JsonNode root, String name) {
        var value = feld(root, name);
        if (!value.isString()) throw ungueltig();
        return pfadUuid(value.asString());
    }

    public static UUID pfadUuid(String value) {
        try {
            var id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw ungueltig();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw ungueltig();
        }
    }

    private static ResponseStatusException ungueltig() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungueltiger Schreibrequest");
    }
}
