package de.pia.inventur.backend.repository;

import java.util.Optional;
import java.util.UUID;
import de.pia.inventur.backend.entity.BenutzerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import de.pia.inventur.backend.entity.BenutzerRolle;

public interface BenutzerRepository extends JpaRepository<BenutzerEntity, UUID> {
    Optional<BenutzerEntity> findByOidcIssuerAndOidcSubject(String oidcIssuer, String oidcSubject);

    interface LeseStatus {
        boolean getAktiv();
        BenutzerRolle getRolle();
    }

    // Skalarprojektion: ein bereits geladener Entity-Cache darf den aktuellen Status nicht verdecken.
    @Query("select b.aktiv as aktiv, b.rolle as rolle from BenutzerEntity b where b.id = :id")
    Optional<LeseStatus> findeLeseStatus(@Param("id") UUID id);
}
