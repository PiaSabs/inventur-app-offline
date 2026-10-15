package de.pia.inventur.backend.repository;

import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import de.pia.inventur.backend.entity.SyncRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncRequestsRepository extends JpaRepository<SyncRequestEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select request from SyncRequestEntity request where request.requestId = :requestId")
    Optional<SyncRequestEntity> findeZurWiederholungspruefung(@Param("requestId") UUID requestId);

    @Modifying
    @Query(value = """
            INSERT INTO sync_request (request_id, urheber_id, operation, zaehlerliste_id,
                zaehlsitzung_id, zaehlblatt_id, zaehlposition_id, zuweisung_version,
                erwartete_ergebnisrevision, erwartete_blatt_status_version, erwartete_sitzung_status_version,
                normalisierter_inhalt, inhalt_fingerprint, inhalt_format_version, status, eingegangen_am)
            VALUES (:requestId, :urheberId, :operation, :listeId, :sitzungId, :blattId, :positionId,
                :zuweisungVersion, :basisRevision, :blattVersion, :sitzungVersion,
                :inhalt, :fingerprint, :formatVersion, 'EINGEGANGEN', statement_timestamp())
            ON CONFLICT (request_id) DO NOTHING
            """, nativeQuery = true)
    int registriereFallsUnbekannt(@Param("requestId") UUID requestId, @Param("urheberId") UUID urheberId,
            @Param("operation") String operation, @Param("listeId") UUID listeId,
            @Param("sitzungId") UUID sitzungId, @Param("blattId") UUID blattId,
            @Param("positionId") UUID positionId, @Param("zuweisungVersion") long zuweisungVersion,
            @Param("basisRevision") Long basisRevision, @Param("blattVersion") long blattVersion,
            @Param("sitzungVersion") long sitzungVersion, @Param("inhalt") String inhalt,
            @Param("fingerprint") String fingerprint, @Param("formatVersion") int formatVersion);

    // Native Abfrage: auch SQL-Aenderungen werden ohne veralteten JPA-Entity-Cache gelesen.
    @Query(value = "SELECT status FROM sync_request WHERE request_id = :requestId", nativeQuery = true)
    Optional<String> findeRegistrierungsstatus(@Param("requestId") UUID requestId);
}
