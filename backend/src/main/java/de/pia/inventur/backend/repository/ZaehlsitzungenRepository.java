package de.pia.inventur.backend.repository;

import java.util.List;
import java.util.UUID;

import de.pia.inventur.backend.entity.ZaehlsitzungEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZaehlsitzungenRepository extends JpaRepository<ZaehlsitzungEntity, UUID> {

    List<ZaehlsitzungEntity> findAllByZaehlerliste_IdOrderByZaehltagAscRundeNrAsc(UUID zaehlerlisteId);

    boolean existsByIdAndZaehlerliste_AktuellerZaehler_Id(UUID id, UUID zaehlerId);

    List<ZaehlsitzungEntity> findAllByZaehlerliste_IdAndZaehlerliste_AktuellerZaehler_IdOrderByZaehltagAscRundeNrAsc(
            UUID zaehlerlisteId, UUID zaehlerId);
}
