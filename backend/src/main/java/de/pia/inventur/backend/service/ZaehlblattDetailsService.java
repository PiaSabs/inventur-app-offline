package de.pia.inventur.backend.service;

import java.util.UUID;
import java.util.HashMap;
import de.pia.inventur.backend.dto.ArtikelResponse;
import de.pia.inventur.backend.dto.ZaehlblattDetailResponse;
import de.pia.inventur.backend.dto.ZaehlpositionResponse;
import de.pia.inventur.backend.dto.ZaehlergebnisResponse;
import de.pia.inventur.backend.repository.ZaehlblaetterRepository;
import de.pia.inventur.backend.repository.ZaehlpositionenRepository;
import de.pia.inventur.backend.repository.ZaehlergebnisseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import de.pia.inventur.backend.security.LeseBerechtigung;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ZaehlblattDetailsService {

    private final ZaehlblaetterRepository blaetter;
    private final ZaehlpositionenRepository positionen;
    private final ZaehlergebnisseRepository ergebnisse;
    private final LeseBerechtigung berechtigung;

    public ZaehlblattDetailsService(ZaehlblaetterRepository blaetter, ZaehlpositionenRepository positionen,
            ZaehlergebnisseRepository ergebnisse, LeseBerechtigung berechtigung) {
        this.blaetter = blaetter;
        this.positionen = positionen;
        this.ergebnisse = ergebnisse;
        this.berechtigung = berechtigung;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ZaehlblattDetailResponse findeBlatt(UUID sitzungId, UUID blattId) {
        var zaehlerId = berechtigung.aktuellerZaehler();
        var blatt = blaetter.findByIdAndZaehlsitzung_IdAndZaehlsitzung_Zaehlerliste_AktuellerZaehler_Id(blattId, sitzungId, zaehlerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ressource nicht gefunden"));
        var geladenePositionen = positionen.findAllByZaehlblatt_IdOrderByPositionsnummerAsc(blattId);
        var ergebnisDtos = new HashMap<UUID, ZaehlergebnisResponse>();
        if (!geladenePositionen.isEmpty()) {
            for (var ergebnis : ergebnisse.findAllByZaehlposition_Zaehlblatt_Id(blattId)) {
                ergebnisDtos.put(ergebnis.getZaehlposition().getId(), new ZaehlergebnisResponse(
                        ergebnis.getZaehlmenge(), ergebnis.getAnmerkung(), ergebnis.getRevision()));
            }
        }
        var ausgangsstand = new ZaehlergebnisResponse(null, null, 0);
        var positionsDtos = geladenePositionen.stream()
                .map(position -> {
                    var artikel = position.getArtikel();
                    return new ZaehlpositionResponse(position.getId(), position.getPositionsnummer(),
                            position.getLagerort(), position.getIstMenge(), position.getMindestbestand(),
                            new ArtikelResponse(artikel.getId(), artikel.getArtikelnummer(), artikel.getBezeichnung()),
                            ergebnisDtos.getOrDefault(position.getId(), ausgangsstand));
                }).toList();
        return new ZaehlblattDetailResponse(blatt.getId(), blatt.getZaehlsitzung().getId(),
                blatt.getBlattnummer(), blatt.getStatus().name(), blatt.getStatusVersion(),
                blatt.getAbgeschlossenAm(), positionsDtos);
    }
}
