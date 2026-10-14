package de.pia.inventur.backend.service.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import de.pia.inventur.backend.entity.SyncOperation;
import org.springframework.stereotype.Component;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** SHA-256 vergleicht Inhalte; es authentifiziert keinen Benutzer und verarbeitet keine Requests. */
@Component
public final class ErgebnisRequestNormalisierer {

    public static final int FORMAT_VERSION = 1;

    // Eigene feste Konfiguration: unabhaengig von der HTTP-JSON-Konfiguration.
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .disable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES)
            .disable(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS)
            .enable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
            .build();

    public NormalisierterRequest normalisiere(ErgebnisSpeichernInhalt inhalt) {
        erforderlich(inhalt, "inhalt");
        erforderlich(inhalt.formatVersion(), "formatVersion");
        if (inhalt.formatVersion() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Nur Inhaltsformatversion 1 wird unterstuetzt");
        }
        if (inhalt.operation() != SyncOperation.ERGEBNIS_SPEICHERN) {
            throw new IllegalArgumentException("Nur ERGEBNIS_SPEICHERN wird unterstuetzt");
        }
        erforderlich(inhalt.zaehlerlisteId(), "zaehlerlisteId");
        erforderlich(inhalt.zaehlsitzungId(), "zaehlsitzungId");
        erforderlich(inhalt.zaehlblattId(), "zaehlblattId");
        erforderlich(inhalt.zaehlpositionId(), "zaehlpositionId");
        mindestens(inhalt.zuweisungVersion(), 0, "zuweisungVersion");
        mindestens(inhalt.basisRevision(), 0, "basisRevision");
        mindestens(inhalt.lokaleRevision(), 0, "lokaleRevision");
        mindestens(inhalt.erwarteteBlattStatusVersion(), 1, "erwarteteBlattStatusVersion");
        mindestens(inhalt.erwarteteSitzungStatusVersion(), 1, "erwarteteSitzungStatusVersion");
        if (inhalt.zaehlmenge() != null && inhalt.zaehlmenge() < 0) {
            throw new IllegalArgumentException("zaehlmenge darf nicht negativ sein");
        }
        pruefeUnicode(inhalt.anmerkung());

        // Diese Reihenfolge ist Bestandteil von Formatversion 1.
        var felder = new LinkedHashMap<String, Object>();
        felder.put("formatVersion", FORMAT_VERSION);
        felder.put("operation", inhalt.operation().name());
        felder.put("zaehlerlisteId", inhalt.zaehlerlisteId().toString());
        felder.put("zaehlsitzungId", inhalt.zaehlsitzungId().toString());
        felder.put("zaehlblattId", inhalt.zaehlblattId().toString());
        felder.put("zaehlpositionId", inhalt.zaehlpositionId().toString());
        felder.put("zuweisungVersion", inhalt.zuweisungVersion());
        felder.put("basisRevision", inhalt.basisRevision());
        felder.put("lokaleRevision", inhalt.lokaleRevision());
        felder.put("erwarteteBlattStatusVersion", inhalt.erwarteteBlattStatusVersion());
        felder.put("erwarteteSitzungStatusVersion", inhalt.erwarteteSitzungStatusVersion());
        felder.put("zaehlmenge", inhalt.zaehlmenge());
        felder.put("anmerkung", inhalt.anmerkung());
        String kanonisch = mapper.writeValueAsString(felder);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(kanonisch.getBytes(StandardCharsets.UTF_8));
            return new NormalisierterRequest(FORMAT_VERSION, kanonisch, HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 ist in dieser Java-Laufzeit nicht verfuegbar", exception);
        }
    }

    private static void erforderlich(Object wert, String feld) {
        if (wert == null) {
            throw new IllegalArgumentException(feld + " ist erforderlich");
        }
    }

    private static void mindestens(Long wert, long minimum, String feld) {
        erforderlich(wert, feld);
        if (wert < minimum) {
            throw new IllegalArgumentException(feld + " muss mindestens " + minimum + " sein");
        }
    }

    private static void pruefeUnicode(String text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            char zeichen = text.charAt(i);
            if (Character.isHighSurrogate(zeichen)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new IllegalArgumentException("anmerkung enthaelt ungueltiges Unicode");
                }
                i++;
            } else if (Character.isLowSurrogate(zeichen)) {
                throw new IllegalArgumentException("anmerkung enthaelt ungueltiges Unicode");
            }
        }
    }
}
