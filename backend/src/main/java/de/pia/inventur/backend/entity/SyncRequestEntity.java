package de.pia.inventur.backend.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "sync_request")
public class SyncRequestEntity {

    @Id
    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "urheber_id", nullable = false)
    private BenutzerEntity urheber;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, columnDefinition = "text")
    private SyncOperation operation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zaehlerliste_id", nullable = false)
    private ZaehlerlisteEntity zaehlerliste;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zaehlsitzung_id")
    private ZaehlsitzungEntity zaehlsitzung;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zaehlblatt_id")
    private ZaehlblattEntity zaehlblatt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zaehlposition_id")
    private ZaehlpositionEntity zaehlposition;

    @Column(name = "zuweisung_version", nullable = false)
    private long zuweisungVersion;

    @Column(name = "erwartete_ergebnisrevision")
    private Long erwarteteErgebnisrevision;

    @Column(name = "erwartete_blatt_status_version")
    private Long erwarteteBlattStatusVersion;

    @Column(name = "erwartete_sitzung_status_version")
    private Long erwarteteSitzungStatusVersion;

    @Column(name = "normalisierter_inhalt", nullable = false, columnDefinition = "text")
    private String normalisierterInhalt;

    @Column(name = "inhalt_fingerprint", nullable = false, columnDefinition = "text")
    private String inhaltFingerprint;

    @Column(name = "inhalt_format_version", nullable = false)
    private int inhaltFormatVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "text")
    private SyncRequestStatus status;

    @Column(name = "entscheidungscode", columnDefinition = "text")
    private String entscheidungscode;

    @Column(name = "antwort_http_status")
    private Integer antwortHttpStatus;

    @Column(name = "antwort_json", columnDefinition = "text")
    private String antwortJson;

    @Column(name = "eingegangen_am", nullable = false)
    private Instant eingegangenAm;

    @Column(name = "verarbeitet_am")
    private Instant verarbeitetAm;

    protected SyncRequestEntity() {
        // JPA benoetigt einen Konstruktor ohne Argumente.
    }

    public SyncRequestEntity(
            UUID requestId,
            BenutzerEntity urheber,
            SyncOperation operation,
            ZaehlerlisteEntity zaehlerliste,
            ZaehlsitzungEntity zaehlsitzung,
            ZaehlblattEntity zaehlblatt,
            ZaehlpositionEntity zaehlposition,
            long zuweisungVersion,
            Long erwarteteErgebnisrevision,
            Long erwarteteBlattStatusVersion,
            Long erwarteteSitzungStatusVersion,
            String normalisierterInhalt,
            String inhaltFingerprint,
            int inhaltFormatVersion,
            SyncRequestStatus status,
            String entscheidungscode,
            Integer antwortHttpStatus,
            String antwortJson,
            Instant eingegangenAm,
            Instant verarbeitetAm) {
        this.requestId = requestId;
        this.urheber = urheber;
        this.operation = operation;
        this.zaehlerliste = zaehlerliste;
        this.zaehlsitzung = zaehlsitzung;
        this.zaehlblatt = zaehlblatt;
        this.zaehlposition = zaehlposition;
        this.zuweisungVersion = zuweisungVersion;
        this.erwarteteErgebnisrevision = erwarteteErgebnisrevision;
        this.erwarteteBlattStatusVersion = erwarteteBlattStatusVersion;
        this.erwarteteSitzungStatusVersion = erwarteteSitzungStatusVersion;
        this.normalisierterInhalt = normalisierterInhalt;
        this.inhaltFingerprint = inhaltFingerprint;
        this.inhaltFormatVersion = inhaltFormatVersion;
        this.status = status;
        this.entscheidungscode = entscheidungscode;
        this.antwortHttpStatus = antwortHttpStatus;
        this.antwortJson = antwortJson;
        this.eingegangenAm = eingegangenAm;
        this.verarbeitetAm = verarbeitetAm;
    }

    public UUID getRequestId() { return requestId; }
    public BenutzerEntity getUrheber() { return urheber; }
    public SyncOperation getOperation() { return operation; }
    public ZaehlerlisteEntity getZaehlerliste() { return zaehlerliste; }
    public ZaehlsitzungEntity getZaehlsitzung() { return zaehlsitzung; }
    public ZaehlblattEntity getZaehlblatt() { return zaehlblatt; }
    public ZaehlpositionEntity getZaehlposition() { return zaehlposition; }
    public long getZuweisungVersion() { return zuweisungVersion; }
    public Long getErwarteteErgebnisrevision() { return erwarteteErgebnisrevision; }
    public Long getErwarteteBlattStatusVersion() { return erwarteteBlattStatusVersion; }
    public Long getErwarteteSitzungStatusVersion() { return erwarteteSitzungStatusVersion; }
    public String getNormalisierterInhalt() { return normalisierterInhalt; }
    public String getInhaltFingerprint() { return inhaltFingerprint; }
    public int getInhaltFormatVersion() { return inhaltFormatVersion; }
    public SyncRequestStatus getStatus() { return status; }
    public String getEntscheidungscode() { return entscheidungscode; }
    public Integer getAntwortHttpStatus() { return antwortHttpStatus; }
    public String getAntwortJson() { return antwortJson; }
    public Instant getEingegangenAm() { return eingegangenAm; }
    public Instant getVerarbeitetAm() { return verarbeitetAm; }
}
