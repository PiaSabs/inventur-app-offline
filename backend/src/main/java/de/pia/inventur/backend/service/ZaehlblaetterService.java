package de.pia.inventur.backend.service;

import java.util.List;
import java.util.UUID;

import de.pia.inventur.backend.dto.ZaehlblattResponse;
import de.pia.inventur.backend.repository.ZaehlblaetterRepository;
import de.pia.inventur.backend.repository.ZaehlsitzungenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import de.pia.inventur.backend.security.LeseBerechtigung;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ZaehlblaetterService {

    private final ZaehlsitzungenRepository zaehlsitzungenRepository;
    private final ZaehlblaetterRepository zaehlblaetterRepository;
    private final LeseBerechtigung berechtigung;

    public ZaehlblaetterService(ZaehlsitzungenRepository zaehlsitzungenRepository,
            ZaehlblaetterRepository zaehlblaetterRepository, LeseBerechtigung berechtigung) {
        this.zaehlsitzungenRepository = zaehlsitzungenRepository;
        this.zaehlblaetterRepository = zaehlblaetterRepository;
        this.berechtigung = berechtigung;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ZaehlblattResponse> findeFuerSitzung(UUID sitzungId) {
        var zaehlerId = berechtigung.aktuellerZaehler();
        if (!zaehlsitzungenRepository.existsByIdAndZaehlerliste_AktuellerZaehler_Id(sitzungId, zaehlerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ressource nicht gefunden");
        }
        return zaehlblaetterRepository.findAllByZaehlsitzung_IdAndZaehlsitzung_Zaehlerliste_AktuellerZaehler_IdOrderByBlattnummerAsc(sitzungId, zaehlerId)
                .stream()
                .map(entity -> new ZaehlblattResponse(entity.getId(), entity.getZaehlsitzung().getId(),
                        entity.getBlattnummer(), entity.getStatus().name(), entity.getStatusVersion(),
                        entity.getAbgeschlossenAm()))
                .toList();
    }
}
