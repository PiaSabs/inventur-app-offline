package de.pia.inventur.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import de.pia.inventur.backend.entity.ZaehlblattEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZaehlblaetterRepository extends JpaRepository<ZaehlblattEntity, UUID> {

    Optional<ZaehlblattEntity> findByIdAndZaehlsitzung_Id(UUID blattId, UUID sitzungId);

    // Textsortierung; eine fachliche Nummernsortierung wird spaeter festgelegt.
    List<ZaehlblattEntity> findAllByZaehlsitzung_IdOrderByBlattnummerAsc(UUID sitzungId);

    Optional<ZaehlblattEntity> findByIdAndZaehlsitzung_IdAndZaehlsitzung_Zaehlerliste_AktuellerZaehler_Id(
            UUID blattId, UUID sitzungId, UUID zaehlerId);

    List<ZaehlblattEntity> findAllByZaehlsitzung_IdAndZaehlsitzung_Zaehlerliste_AktuellerZaehler_IdOrderByBlattnummerAsc(
            UUID sitzungId, UUID zaehlerId);
}
