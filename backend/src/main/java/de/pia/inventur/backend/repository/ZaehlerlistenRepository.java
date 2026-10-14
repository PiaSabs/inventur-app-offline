package de.pia.inventur.backend.repository;

import java.util.List;
import java.util.UUID;

import de.pia.inventur.backend.entity.ZaehlerlisteEntity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ZaehlerlistenRepository extends JpaRepository<ZaehlerlisteEntity, UUID> {

    List<ZaehlerlisteEntity> findAllByOrderByCodeAsc();

    List<ZaehlerlisteEntity> findAllByAktuellerZaehler_IdOrderByCodeAsc(UUID zaehlerId);

    boolean existsByIdAndAktuellerZaehler_Id(UUID id, UUID zaehlerId);
}
