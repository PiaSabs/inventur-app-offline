package de.pia.inventur.backend.dto;

import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Vollstaendiger PUT-Inhalt: alle zehn Felder erforderlich, Menge und Notiz duerfen NULL sein. */
public record ErgebnisSpeichernRequest(UUID requestId, UUID zaehlerlisteId, UUID zaehlblattId,
        long zuweisungVersion, long basisRevision, long lokaleRevision,
        long erwarteteBlattStatusVersion, long erwarteteSitzungStatusVersion, Integer zaehlmenge, String anmerkung) {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public static ErgebnisSpeichernRequest lese(byte[] json) {
        try {
            var root = JSON.readTree(json);
            if (root == null || !root.isObject() || root.size() != 10) throw ungueltig();
            var menge = feld(root, "zaehlmenge");
            Integer quantity = null;
            if (!menge.isNull()) {
                if (!menge.isIntegralNumber() || !menge.canConvertToInt() || menge.intValue() < 0) throw ungueltig();
                quantity = menge.intValue();
            }
            var notiz = feld(root, "anmerkung");
            if (!notiz.isNull() && (!notiz.isString() || notiz.asString().indexOf('\0') >= 0)) throw ungueltig();
            return new ErgebnisSpeichernRequest(uuid(root, "requestId"), uuid(root, "zaehlerlisteId"), uuid(root, "zaehlblattId"),
                    zahl(root, "zuweisungVersion", 0), zahl(root, "basisRevision", 0), zahl(root, "lokaleRevision", 0),
                    zahl(root, "erwarteteBlattStatusVersion", 1), zahl(root, "erwarteteSitzungStatusVersion", 1),
                    quantity, notiz.isNull() ? null : notiz.asString());
        } catch (RuntimeException invalid) {
            // Weder Jackson-Ursachen noch Ausschnitte aus dem Request weitergeben.
            throw ungueltig();
        }
    }

    private static JsonNode feld(JsonNode root, String name) {
        var value = root.get(name);
        if (value == null) throw ungueltig();
        return value;
    }
    private static long zahl(JsonNode root, String name, long minimum) {
        var value = feld(root, name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum) throw ungueltig();
        return value.longValue();
    }
    private static UUID uuid(JsonNode root, String name) {
        var value = feld(root, name);
        if (!value.isString()) throw ungueltig();
        var uuid = UUID.fromString(value.asString());
        if (!uuid.toString().equalsIgnoreCase(value.asString())) throw ungueltig();
        return uuid;
    }
    private static ResponseStatusException ungueltig() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungueltiger Ergebnisrequest");
    }
}
