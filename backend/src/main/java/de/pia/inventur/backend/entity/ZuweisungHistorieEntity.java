package de.pia.inventur.backend.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

/** Unidirektionale Archivdaten; Originalfelder sind ueber JPA nicht aktualisierbar. */
@Entity
@Table(name = "zuweisung_historie")
public class ZuweisungHistorieEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zaehlerliste_id", nullable = false, updatable = false)
    private ZaehlerlisteEntity zaehlerliste;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vorheriger_zaehler_id", updatable = false)
    private BenutzerEntity vorherigerZaehler;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "neuer_zaehler_id", updatable = false)
    private BenutzerEntity neuerZaehler;

    @Column(name = "version_vorher", nullable = false, updatable = false)
    private Long versionVorher;

    @Column(name = "version_nachher", nullable = false, updatable = false)
    private Long versionNachher;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "verantwortlicher_benutzer_id", nullable = false, updatable = false)
    private BenutzerEntity verantwortlicherBenutzer;

    @Column(name = "geaendert_am", nullable = false, insertable = false, updatable = false)
    private Instant geaendertAm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_request_id", updatable = false)
    private SyncRequestEntity syncRequest;

    protected ZuweisungHistorieEntity() { /* JPA */ }

    public ZuweisungHistorieEntity(
            UUID id,
            ZaehlerlisteEntity zaehlerliste,
            BenutzerEntity vorherigerZaehler,
            BenutzerEntity neuerZaehler,
            Long versionVorher,
            Long versionNachher,
            BenutzerEntity verantwortlicherBenutzer,
            SyncRequestEntity syncRequest) {
        this.id = id;
        this.zaehlerliste = zaehlerliste;
        this.vorherigerZaehler = vorherigerZaehler;
        this.neuerZaehler = neuerZaehler;
        this.versionVorher = versionVorher;
        this.versionNachher = versionNachher;
        this.verantwortlicherBenutzer = verantwortlicherBenutzer;
        this.syncRequest = syncRequest;
    }

    public UUID getId() { return id; }
    public ZaehlerlisteEntity getZaehlerliste() { return zaehlerliste; }
    public BenutzerEntity getVorherigerZaehler() { return vorherigerZaehler; }
    public BenutzerEntity getNeuerZaehler() { return neuerZaehler; }
    public Long getVersionVorher() { return versionVorher; }
    public Long getVersionNachher() { return versionNachher; }
    public BenutzerEntity getVerantwortlicherBenutzer() { return verantwortlicherBenutzer; }
    public Instant getGeaendertAm() { return geaendertAm; }
    public SyncRequestEntity getSyncRequest() { return syncRequest; }
}

