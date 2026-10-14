package de.pia.inventur.backend.service;

import java.util.List;
import java.util.UUID;

import de.pia.inventur.backend.dto.ZaehlsitzungResponse;
import de.pia.inventur.backend.repository.ZaehlerlistenRepository;
import de.pia.inventur.backend.repository.ZaehlsitzungenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import de.pia.inventur.backend.security.LeseBerechtigung;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ZaehlsitzungenService {

    private final ZaehlerlistenRepository zaehlerlistenRepository;
    private final ZaehlsitzungenRepository zaehlsitzungenRepository;
    private final LeseBerechtigung berechtigung;

    public ZaehlsitzungenService(ZaehlerlistenRepository zaehlerlistenRepository,
            ZaehlsitzungenRepository zaehlsitzungenRepository, LeseBerechtigung berechtigung) {
        this.zaehlerlistenRepository = zaehlerlistenRepository;
        this.zaehlsitzungenRepository = zaehlsitzungenRepository;
        this.berechtigung = berechtigung;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ZaehlsitzungResponse> findeFuerListe(UUID listeId) {
        var zaehlerId = berechtigung.aktuellerZaehler();
        if (!zaehlerlistenRepository.existsByIdAndAktuellerZaehler_Id(listeId, zaehlerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ressource nicht gefunden");
        }
        return zaehlsitzungenRepository.findAllByZaehlerliste_IdAndZaehlerliste_AktuellerZaehler_IdOrderByZaehltagAscRundeNrAsc(listeId, zaehlerId)
                .stream()
                .map(entity -> new ZaehlsitzungResponse(entity.getId(), entity.getZaehlerliste().getId(),
                        entity.getZaehltag(), entity.getRundeNr(), entity.getStatus().name(),
                        entity.getStatusVersion(), entity.getGestartetAm(), entity.getAbgeschlossenAm()))
                .toList();
    }
}
