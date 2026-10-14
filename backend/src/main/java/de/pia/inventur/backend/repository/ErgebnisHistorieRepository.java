package de.pia.inventur.backend.repository;

import java.util.List;
import java.util.UUID;
import de.pia.inventur.backend.entity.ErgebnisHistorieEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErgebnisHistorieRepository extends JpaRepository<ErgebnisHistorieEntity, UUID> {

    List<ErgebnisHistorieEntity> findAllByZaehlposition_IdOrderByRevisionAsc(UUID positionsId);
}
