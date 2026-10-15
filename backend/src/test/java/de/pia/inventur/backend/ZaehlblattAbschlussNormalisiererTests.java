package de.pia.inventur.backend;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.stream.Stream;
import de.pia.inventur.backend.service.sync.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class ZaehlblattAbschlussNormalisiererTests {
    private final ErgebnisRequestNormalisierer normalisierer = new ErgebnisRequestNormalisierer();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private ZaehlblattAbschlussRequest basis() {
        return new ZaehlblattAbschlussRequest(UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001"), 1,
                UUID.fromString("92000000-0000-4000-8000-000000000001"),
                UUID.fromString("93000000-0000-4000-8000-000000000001"),
                UUID.fromString("94000000-0000-4000-8000-000000000001"), 1L, 2L, 3L, true, false);
    }
    @Test void kanonischesFormatV1UndUnabhaengigBerechneterSha256SindFestgeschrieben() {
        var n=normalisierer.normalisiere(basis());
        assertEquals(1,n.inhaltFormatVersion());
        assertEquals("{\"formatVersion\":1,\"operation\":\"BLATT_ABSCHLIESSEN\",\"requestId\":\"aaaaaaaa-0000-4000-8000-000000000001\",\"zaehlerlisteId\":\"92000000-0000-4000-8000-000000000001\",\"zaehlsitzungId\":\"93000000-0000-4000-8000-000000000001\",\"zaehlblattId\":\"94000000-0000-4000-8000-000000000001\",\"zuweisungVersion\":1,\"erwarteteSitzungStatusVersion\":2,\"erwarteteBlattStatusVersion\":3,\"abschlussBestaetigt\":true,\"unvollstaendigBestaetigt\":false}", n.normalisierterInhalt());
        assertEquals("15843edeb7281b0a4c31ae4ec10ad31965a406664f14b4d5e78abeefc3ce62bf",n.inhaltFingerprint());
        assertEquals(n,normalisierer.normalisiere(basis()));
    }
    static Stream<Arguments> aenderungen() {
        return Stream.of(Arguments.of("requestId",UUID.randomUUID()),Arguments.of("zaehlerlisteId",UUID.randomUUID()),
                Arguments.of("zaehlsitzungId",UUID.randomUUID()),Arguments.of("zaehlblattId",UUID.randomUUID()),
                Arguments.of("zuweisungVersion",2L),Arguments.of("erwarteteSitzungStatusVersion",3L),
                Arguments.of("erwarteteBlattStatusVersion",4L),Arguments.of("abschlussBestaetigt",false),
                Arguments.of("unvollstaendigBestaetigt",true));
    }
    @ParameterizedTest @MethodSource("aenderungen")
    void jedesFachlicheFeldIstTeilDesFingerabdrucks(String feld,Object wert) {
        var fields=mapper.convertValue(basis(),new TypeReference<LinkedHashMap<String,Object>>() {});
        fields.put(feld,wert);
        var changed=normalisierer.normalisiere(mapper.convertValue(fields,ZaehlblattAbschlussRequest.class));
        var original=normalisierer.normalisiere(basis());
        assertNotEquals(original.normalisierterInhalt(),changed.normalisierterInhalt());
        assertNotEquals(original.inhaltFingerprint(),changed.inhaltFingerprint());
    }
}
