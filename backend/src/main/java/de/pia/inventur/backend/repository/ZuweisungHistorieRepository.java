package de.pia.inventur.backend.repository;

import java.util.List;
import java.util.UUID;
import de.pia.inventur.backend.entity.ZuweisungHistorieEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZuweisungHistorieRepository extends JpaRepository<ZuweisungHistorieEntity, UUID> {
    List<ZuweisungHistorieEntity> findAllByZaehlerliste_IdOrderByVersionNachherAsc(UUID listenId);
}

