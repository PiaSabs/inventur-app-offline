package de.pia.inventur.backend.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

/** Unidirektionale Archivdaten; Originalfelder sind ueber JPA nicht aktualisierbar. */
@Entity
@Table(name = "sync_konflikt")
public class SyncKonfliktEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "urspruengliche_request_id", nullable = false, updatable = false)
    private UUID urspruenglicheRequestId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "urheber_id", nullable = false, updatable = false)
    private BenutzerEntity urheber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zaehlerliste_id", updatable = false)
    private ZaehlerlisteEntity zaehlerliste;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zaehlsitzung_id", updatable = false)
    private ZaehlsitzungEntity zaehlsitzung;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zaehlblatt_id", updatable = false)
    private ZaehlblattEntity zaehlblatt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zaehlposition_id", updatable = false)
    private ZaehlpositionEntity zaehlposition;

    @Enumerated(EnumType.STRING)
    @Column(name = "konfliktcode", nullable = false, columnDefinition = "text", updatable = false)
    private SyncKonfliktCode konfliktcode;

    @Column(name = "normalisierter_inhalt", nullable = false, updatable = false, columnDefinition = "text")
    private String normalisierterInhalt;

    @Column(name = "inhalt_format_version", nullable = false, updatable = false)
    private int inhaltFormatVersion;

    @Column(name = "inhalt_fingerprint", nullable = false, updatable = false, columnDefinition = "text")
    private String inhaltFingerprint;

    @Column(name = "basis_revision", nullable = false, updatable = false)
    private Long basisRevision;

    @Column(name = "lokale_revision", nullable = false, updatable = false)
    private Long lokaleRevision;

    @Column(name = "zuweisung_version", nullable = false, updatable = false)
    private Long zuweisungVersion;

    @Column(name = "erwartete_blatt_status_version", nullable = false, updatable = false)
    private Long erwarteteBlattStatusVersion;

    @Column(name = "erwartete_sitzung_status_version", nullable = false, updatable = false)
    private Long erwarteteSitzungStatusVersion;

    @Column(name = "zaehlmenge", updatable = false)
    private Integer zaehlmenge;

    @Column(name = "anmerkung", updatable = false, columnDefinition = "text")
    private String anmerkung;

    @Column(name = "erfasst_am", nullable = false, insertable = false, updatable = false)
    private Instant erfasstAm;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "text")
    private SyncKonfliktStatus status;

    protected SyncKonfliktEntity() { /* JPA */ }

    public SyncKonfliktEntity(
            UUID id,
            UUID urspruenglicheRequestId,
            BenutzerEntity urheber,
            ZaehlerlisteEntity zaehlerliste,
            ZaehlsitzungEntity zaehlsitzung,
            ZaehlblattEntity zaehlblatt,
            ZaehlpositionEntity zaehlposition,
            SyncKonfliktCode konfliktcode,
            String normalisierterInhalt,
            int inhaltFormatVersion,
            String inhaltFingerprint,
            Long basisRevision,
            Long lokaleRevision,
            Long zuweisungVersion,
            Long erwarteteBlattStatusVersion,
            Long erwarteteSitzungStatusVersion,
            Integer zaehlmenge,
            String anmerkung,
            SyncKonfliktStatus status) {
        this.id = id;
        this.urspruenglicheRequestId = urspruenglicheRequestId;
        this.urheber = urheber;
        this.zaehlerliste = zaehlerliste;
        this.zaehlsitzung = zaehlsitzung;
        this.zaehlblatt = zaehlblatt;
        this.zaehlposition = zaehlposition;
        this.konfliktcode = konfliktcode;
        this.normalisierterInhalt = normalisierterInhalt;
        this.inhaltFormatVersion = inhaltFormatVersion;
        this.inhaltFingerprint = inhaltFingerprint;
        this.basisRevision = basisRevision;
        this.lokaleRevision = lokaleRevision;
        this.zuweisungVersion = zuweisungVersion;
        this.erwarteteBlattStatusVersion = erwarteteBlattStatusVersion;
        this.erwarteteSitzungStatusVersion = erwarteteSitzungStatusVersion;
        this.zaehlmenge = zaehlmenge;
        this.anmerkung = anmerkung;
        this.status = status;
    }

    public UUID getId() { return id; }
    public UUID getUrspruenglicheRequestId() { return urspruenglicheRequestId; }
    public BenutzerEntity getUrheber() { return urheber; }
    public ZaehlerlisteEntity getZaehlerliste() { return zaehlerliste; }
    public ZaehlsitzungEntity getZaehlsitzung() { return zaehlsitzung; }
    public ZaehlblattEntity getZaehlblatt() { return zaehlblatt; }
    public ZaehlpositionEntity getZaehlposition() { return zaehlposition; }
    public SyncKonfliktCode getKonfliktcode() { return konfliktcode; }
    public String getNormalisierterInhalt() { return normalisierterInhalt; }
    public int getInhaltFormatVersion() { return inhaltFormatVersion; }
    public String getInhaltFingerprint() { return inhaltFingerprint; }
    public Long getBasisRevision() { return basisRevision; }
    public Long getLokaleRevision() { return lokaleRevision; }
    public Long getZuweisungVersion() { return zuweisungVersion; }
    public Long getErwarteteBlattStatusVersion() { return erwarteteBlattStatusVersion; }
    public Long getErwarteteSitzungStatusVersion() { return erwarteteSitzungStatusVersion; }
    public Integer getZaehlmenge() { return zaehlmenge; }
    public String getAnmerkung() { return anmerkung; }
    public Instant getErfasstAm() { return erfasstAm; }
    public SyncKonfliktStatus getStatus() { return status; }
}

