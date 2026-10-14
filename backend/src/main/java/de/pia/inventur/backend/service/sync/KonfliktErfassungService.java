package de.pia.inventur.backend.service.sync;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import de.pia.inventur.backend.dto.SecurityFehlerAntwort;
import de.pia.inventur.backend.entity.*;
import de.pia.inventur.backend.repository.ErgebnisSpeicherRepository;
import de.pia.inventur.backend.repository.SyncKonflikteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Archivierung nach verifiziertem Kontext und neuer Registrierung in derselben Transaktion. */
@Service
public class KonfliktErfassungService {
    private final SyncKonflikteRepository konflikte;
    private final ErgebnisRequestNormalisierer normalisierer;
    private final ErgebnisSpeicherRepository daten;
    private final JsonMapper mapper = JsonMapper.builder().build();
    @PersistenceContext private EntityManager em;

    public KonfliktErfassungService(SyncKonflikteRepository konflikte, ErgebnisRequestNormalisierer normalisierer,
            ErgebnisSpeicherRepository daten) {
        this.konflikte = konflikte; this.normalisierer = normalisierer; this.daten = daten;
    }

    public static Optional<SyncKonfliktCode> konfliktcode(ErgebnisPruefcode code) {
        return switch (code) {
            case ERGEBNISREVISION_VERALTET -> Optional.of(SyncKonfliktCode.ERGEBNISREVISION_VERALTET);
            case ZUWEISUNG_GEAENDERT -> Optional.of(SyncKonfliktCode.ZUWEISUNG_GEAENDERT);
            case SITZUNG_ABGESCHLOSSEN -> Optional.of(SyncKonfliktCode.SITZUNG_ABGESCHLOSSEN);
            case BLATT_ABGESCHLOSSEN -> Optional.of(SyncKonfliktCode.BLATT_ABGESCHLOSSEN);
            case STATUSVERSION_VERALTET -> Optional.of(SyncKonfliktCode.STATUSVERSION_VERALTET);
            default -> Optional.empty();
        };
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public GespeicherteRequestEntscheidung erfasse(UUID requestId, GepruefterUrheber urheber,
            ErgebnisSpeichernInhalt inhalt, ErgebnisPruefcode pruefcode) {
        var code = konfliktcode(pruefcode).orElseThrow(() -> new IllegalStateException("Kein archivierungsfaehiger Konflikt"));
        var original = normalisierer.normalisiere(inhalt);
        konflikte.saveAndFlush(new SyncKonfliktEntity(UUID.randomUUID(), requestId,
                em.getReference(BenutzerEntity.class, urheber.benutzerId()),
                em.getReference(ZaehlerlisteEntity.class, inhalt.zaehlerlisteId()),
                em.getReference(ZaehlsitzungEntity.class, inhalt.zaehlsitzungId()),
                em.getReference(ZaehlblattEntity.class, inhalt.zaehlblattId()),
                em.getReference(ZaehlpositionEntity.class, inhalt.zaehlpositionId()),
                code, original.normalisierterInhalt(), original.inhaltFormatVersion(), original.inhaltFingerprint(),
                inhalt.basisRevision(), inhalt.lokaleRevision(), inhalt.zuweisungVersion(),
                inhalt.erwarteteBlattStatusVersion(), inhalt.erwarteteSitzungStatusVersion(),
                inhalt.zaehlmenge(), inhalt.anmerkung(), SyncKonfliktStatus.OFFEN));
        String json = mapper.writeValueAsString(new SecurityFehlerAntwort(409, "KONFLIKT_ARCHIVIERT_" + code.name(),
                "Offline-Aenderung wurde als Konflikt archiviert. Zaehlergebnis wurde nicht geaendert."));
        var zeit = daten.serverzeit();
        daten.lehneArchiviertenRequestAb(requestId, code.name(), json, zeit);
        return new GespeicherteRequestEntscheidung(SyncRequestStatus.ABGELEHNT, code.name(), 409, json, zeit);
    }
}

