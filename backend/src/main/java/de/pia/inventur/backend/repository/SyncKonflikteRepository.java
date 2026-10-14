package de.pia.inventur.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import de.pia.inventur.backend.entity.SyncKonfliktEntity;
import de.pia.inventur.backend.entity.SyncKonfliktStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncKonflikteRepository extends JpaRepository<SyncKonfliktEntity, UUID> {
    List<SyncKonfliktEntity> findAllByZaehlerliste_IdAndStatusOrderByErfasstAmAscIdAsc(UUID listenId, SyncKonfliktStatus status);
    Optional<SyncKonfliktEntity> findByUrheber_IdAndUrspruenglicheRequestIdAndInhaltFormatVersionAndInhaltFingerprint(
            UUID urheberId, UUID requestId, int formatVersion, String fingerprint);
}

