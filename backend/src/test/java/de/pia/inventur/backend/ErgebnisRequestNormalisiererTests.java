package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import de.pia.inventur.backend.entity.SyncOperation;
import de.pia.inventur.backend.service.sync.ErgebnisRequestNormalisierer;
import de.pia.inventur.backend.service.sync.ErgebnisSpeichernInhalt;
import de.pia.inventur.backend.service.sync.NormalisierterRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class ErgebnisRequestNormalisiererTests {

    private static final String KANONISCH = "{\"formatVersion\":1,\"operation\":\"ERGEBNIS_SPEICHERN\",\"zaehlerlisteId\":\"10000000-0000-4000-8000-000000000001\",\"zaehlsitzungId\":\"70000000-0000-4000-8000-000000000001\",\"zaehlblattId\":\"82000000-0000-4000-8000-000000000001\",\"zaehlpositionId\":\"84000000-0000-4000-8000-000000000001\",\"zuweisungVersion\":1,\"basisRevision\":0,\"lokaleRevision\":1,\"erwarteteBlattStatusVersion\":1,\"erwarteteSitzungStatusVersion\":1,\"zaehlmenge\":12,\"anmerkung\":\"Demo: 12 Stück\"}";
    private static final String HASH = "622483adf44787a849f018de58d5dd211782ee1dabf5cdfa51ab723f34ab7c49";
    private final ErgebnisRequestNormalisierer normalisierer = new ErgebnisRequestNormalisierer();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void exactCanonicalFormatAndHashAreStable() {
        var result = normalisierer.normalisiere(basis());
        assertEquals(KANONISCH, result.normalisierterInhalt());
        assertEquals(HASH, result.inhaltFingerprint());
        assertEquals(1, result.inhaltFormatVersion());
        assertEquals(64, result.inhaltFingerprint().length());
    }

    @Test
    void identicalContentAndRepeatedCalculationAreDeterministic() {
        var expected = normalisierer.normalisiere(basis());
        for (int i = 0; i < 20; i++) {
            assertEquals(expected, normalisierer.normalisiere(basis()));
        }
    }

    @Test
    void differentQuantityChangesFingerprint() {
        assertDifferent(basis(), mit("zaehlmenge", 13));
    }

    @Test
    void nullAndCountedZeroHaveDifferentFingerprints() {
        var uncounted = normalisierer.normalisiere(mit("zaehlmenge", null));
        var zero = normalisierer.normalisiere(mit("zaehlmenge", 0));
        assertNotEquals(uncounted.inhaltFingerprint(), zero.inhaltFingerprint());
        assertTrue(uncounted.normalisierterInhalt().contains("\"zaehlmenge\":null"));
        assertTrue(zero.normalisierterInhalt().contains("\"zaehlmenge\":0"));
    }

    @Test
    void changedNullEmptyAndWhitespaceNotesRemainDistinct() {
        for (Object note : new Object[] {null, "", " ", "Demo: 13 Stück", " Demo: 12 Stück "}) {
            assertDifferent(basis(), mit("anmerkung", note));
        }
        var none = normalisierer.normalisiere(mit("anmerkung", null));
        var empty = normalisierer.normalisiere(mit("anmerkung", ""));
        var blank = normalisierer.normalisiere(mit("anmerkung", " "));
        assertNotEquals(none.inhaltFingerprint(), empty.inhaltFingerprint());
        assertNotEquals(empty.inhaltFingerprint(), blank.inhaltFingerprint());
    }

    @Test
    void allFourTargetIdentitiesAffectFingerprint() {
        for (String field : List.of("zaehlerlisteId", "zaehlsitzungId", "zaehlblattId", "zaehlpositionId")) {
            assertDifferent(basis(), mit(field, UUID.fromString("90000000-0000-4000-8000-000000000001")));
        }
    }

    @Test
    void changedBasisRevisionChangesFingerprint() {
        assertDifferent(basis(), mit("basisRevision", 1L));
    }

    @Test
    void changedAssignmentVersionChangesFingerprint() {
        assertDifferent(basis(), mit("zuweisungVersion", 2L));
    }

    @Test
    void changedLocalRevisionChangesFingerprint() {
        assertDifferent(basis(), mit("lokaleRevision", 2L));
    }

    @Test
    void bothExpectedStatusVersionsAffectFingerprint() {
        assertDifferent(basis(), mit("erwarteteBlattStatusVersion", 2L));
        assertDifferent(basis(), mit("erwarteteSitzungStatusVersion", 2L));
    }

    @Test
    void specialCharactersAreEscapedAndUtf8IsUsedWithoutTrimming() {
        String note = "ÄÖÜ äöü ß 🎲 \"Zitat\" / \\ Pfad\nZeile\r\t\u0000";
        var input = mit("anmerkung", note);
        var first = normalisierer.normalisiere(input);
        assertEquals(first, normalisierer.normalisiere(input));
        assertEquals(note, mapper.readTree(first.normalisierterInhalt()).get("anmerkung").asString());
        assertTrue(first.normalisierterInhalt().contains("ÄÖÜ"));
        assertTrue(first.normalisierterInhalt().contains("\\n"));
        assertTrue(first.normalisierterInhalt().contains("\\u0000"));
        assertTrue(first.normalisierterInhalt().contains("/"));
        assertFalse(first.normalisierterInhalt().contains("\\/"));
        assertNotEquals(normalisierer.normalisiere(mit("anmerkung", "ü")).inhaltFingerprint(),
                normalisierer.normalisiere(mit("anmerkung", "u\u0308")).inhaltFingerprint());
        assertNotEquals(normalisierer.normalisiere(mit("anmerkung", "A\nB")).inhaltFingerprint(),
                normalisierer.normalisiere(mit("anmerkung", "A\r\nB")).inhaltFingerprint());
    }

    @Test
    void jsonPropertyOrderBeforeRecordCreationDoesNotAffectContent() {
        var fields = fields();
        var reversed = new LinkedHashMap<String, Object>();
        fields.sequencedEntrySet().reversed().forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        String differentlyOrderedJson = mapper.writeValueAsString(reversed);
        assertNotEquals(KANONISCH, differentlyOrderedJson);
        assertEquals(normalisierer.normalisiere(basis()),
                normalisierer.normalisiere(mapper.readValue(differentlyOrderedJson, ErgebnisSpeichernInhalt.class)));
    }

    @Test
    void uuidInputCaseDoesNotAffectCanonicalLowercaseRepresentation() {
        String lower = "abcdef01-abcd-4000-8000-abcdef012345";
        assertEquals(normalisierer.normalisiere(mit("zaehlpositionId", UUID.fromString(lower))),
                normalisierer.normalisiere(mit("zaehlpositionId", UUID.fromString(lower.toUpperCase(java.util.Locale.ROOT)))));
        assertTrue(normalisierer.normalisiere(mit("zaehlpositionId", UUID.fromString(lower)))
                .normalisierterInhalt().contains(lower));
    }

    @Test
    void formatVersionIsInHashedContentAndUnsupportedVersionsAreRejected() {
        var result = normalisierer.normalisiere(basis());
        assertTrue(result.normalisierterInhalt().startsWith("{\"formatVersion\":1,"));
        for (Object version : new Object[] {null, 0, 2}) {
            assertThrows(IllegalArgumentException.class, () -> normalisierer.normalisiere(mit("formatVersion", version)));
        }
    }

    @Test
    void incompleteMandatoryFieldsAreRejectedInsteadOfDefaultingToZero() {
        assertThrows(IllegalArgumentException.class, () -> normalisierer.normalisiere(null));
        for (String field : List.of("formatVersion", "operation", "zaehlerlisteId", "zaehlsitzungId",
                "zaehlblattId", "zaehlpositionId", "zuweisungVersion", "basisRevision", "lokaleRevision",
                "erwarteteBlattStatusVersion", "erwarteteSitzungStatusVersion")) {
            assertThrows(IllegalArgumentException.class, () -> normalisierer.normalisiere(mit(field, null)), field);
            var missing = fields();
            missing.remove(field);
            var request = mapper.convertValue(missing, ErgebnisSpeichernInhalt.class);
            assertThrows(IllegalArgumentException.class, () -> normalisierer.normalisiere(request), field);
        }
    }

    @Test
    void onlyResultSaveOperationIsSupported() {
        for (var operation : SyncOperation.values()) {
            if (operation != SyncOperation.ERGEBNIS_SPEICHERN) {
                assertThrows(IllegalArgumentException.class, () -> normalisierer.normalisiere(mit("operation", operation)));
            }
        }
        assertTrue(normalisierer.normalisiere(basis()).normalisierterInhalt()
                .contains("\"operation\":\"ERGEBNIS_SPEICHERN\""));
    }

    @Test
    void negativeQuantitiesAndInvalidVersionRangesAreRejected() {
        for (String field : List.of("zuweisungVersion", "basisRevision", "lokaleRevision", "zaehlmenge")) {
            assertThrows(IllegalArgumentException.class, () -> normalisierer.normalisiere(mit(field, -1)), field);
        }
        for (String field : List.of("erwarteteBlattStatusVersion", "erwarteteSitzungStatusVersion")) {
            assertThrows(IllegalArgumentException.class, () -> normalisierer.normalisiere(mit(field, 0)), field);
        }
    }

    @Test
    void largeIntegersStayExactDecimalValues() {
        var fields = fields();
        fields.put("basisRevision", Long.MAX_VALUE);
        fields.put("lokaleRevision", Long.MAX_VALUE);
        fields.put("zaehlmenge", Integer.MAX_VALUE);
        var result = normalisierer.normalisiere(mapper.convertValue(fields, ErgebnisSpeichernInhalt.class));
        assertTrue(result.normalisierterInhalt().contains("\"basisRevision\":9223372036854775807"));
        assertTrue(result.normalisierterInhalt().contains("\"zaehlmenge\":2147483647"));
        assertFalse(result.normalisierterInhalt().contains("E+"));
    }

    @Test
    void malformedUnicodeIsRejectedBeforeUtf8ReplacementCanCollapseDifferentInputs() {
        for (String note : List.of("\uD800", "\uDC00", "\uD800X")) {
            assertThrows(IllegalArgumentException.class, () -> normalisierer.normalisiere(mit("anmerkung", note)));
        }
    }

    @Test
    void requestIdentityUserAndTransportFieldsAreOutsideCanonicalContent() {
        var json = mapper.readTree(normalisierer.normalisiere(basis()).normalisierterInhalt());
        assertEquals(13, json.size());
        for (String field : List.of("requestId", "urheberId", "benutzerId", "token", "eingegangenAm",
                "verarbeitetAm", "httpStatus", "geraeteId")) {
            assertFalse(json.has(field), field);
        }
    }

    @Test
    void fingerprintAndFormatMatchExistingV7Constraints() {
        NormalisierterRequest result = normalisierer.normalisiere(basis());
        assertTrue(result.inhaltFingerprint().matches("^[0-9a-f]{64}$"));
        assertTrue(result.inhaltFormatVersion() >= 1);
        assertFalse(result.normalisierterInhalt().isBlank());
        assertArrayEquals(result.normalisierterInhalt().getBytes(StandardCharsets.UTF_8),
                KANONISCH.getBytes(StandardCharsets.UTF_8));
    }

    private void assertDifferent(ErgebnisSpeichernInhalt first, ErgebnisSpeichernInhalt second) {
        assertNotEquals(normalisierer.normalisiere(first).inhaltFingerprint(),
                normalisierer.normalisiere(second).inhaltFingerprint());
    }

    private ErgebnisSpeichernInhalt basis() {
        return mapper.readValue(KANONISCH, ErgebnisSpeichernInhalt.class);
    }

    private LinkedHashMap<String, Object> fields() {
        return mapper.readValue(KANONISCH, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private ErgebnisSpeichernInhalt mit(String field, Object value) {
        var fields = fields();
        fields.put(field, value);
        return mapper.convertValue(fields, ErgebnisSpeichernInhalt.class);
    }
}
