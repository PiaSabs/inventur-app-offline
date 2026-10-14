package de.pia.inventur.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import de.pia.inventur.backend.entity.ZaehlergebnisEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZaehlergebnisseRepository extends JpaRepository<ZaehlergebnisEntity, UUID> {

    Optional<ZaehlergebnisEntity> findByZaehlposition_Id(UUID positionsId);

    List<ZaehlergebnisEntity> findAllByZaehlposition_Zaehlblatt_Id(UUID blattId);
}
