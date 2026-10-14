package de.pia.inventur.backend.service;

import java.util.List;

import de.pia.inventur.backend.dto.ZaehlerlisteResponse;
import de.pia.inventur.backend.repository.ZaehlerlistenRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import de.pia.inventur.backend.security.LeseBerechtigung;

@Service
public class ZaehlerlistenService {

    private final ZaehlerlistenRepository zaehlerlistenRepository;
    private final LeseBerechtigung berechtigung;

    public ZaehlerlistenService(ZaehlerlistenRepository zaehlerlistenRepository, LeseBerechtigung berechtigung) {
        this.zaehlerlistenRepository = zaehlerlistenRepository;
        this.berechtigung = berechtigung;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ZaehlerlisteResponse> findeAlle() {
        var zaehlerId = berechtigung.aktuellerZaehler();
        return zaehlerlistenRepository.findAllByAktuellerZaehler_IdOrderByCodeAsc(zaehlerId).stream()
                .map(entity -> new ZaehlerlisteResponse(
                        entity.getId(), entity.getCode(), entity.getBezeichnung()))
                .toList();
    }
}
