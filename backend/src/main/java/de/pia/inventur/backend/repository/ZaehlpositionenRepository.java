package de.pia.inventur.backend.repository;

import java.util.List;
import java.util.UUID;
import de.pia.inventur.backend.entity.ZaehlpositionEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZaehlpositionenRepository extends JpaRepository<ZaehlpositionEntity, UUID> {

    @EntityGraph(attributePaths = "artikel")
    List<ZaehlpositionEntity> findAllByZaehlblatt_IdOrderByPositionsnummerAsc(UUID blattId);
}
