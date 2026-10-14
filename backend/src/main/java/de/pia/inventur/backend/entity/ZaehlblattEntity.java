package de.pia.inventur.backend.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "zaehlblatt")
public class ZaehlblattEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zaehlsitzung_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_zaehlblatt_zaehlsitzung"))
    private ZaehlsitzungEntity zaehlsitzung;

    @Column(nullable = false, columnDefinition = "text")
    private String blattnummer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private ZaehlblattStatus status;

    @Column(name = "status_version", nullable = false)
    private long statusVersion;

    @Column(name = "abgeschlossen_am")
    private Instant abgeschlossenAm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "geschlossen_von",
            foreignKey = @ForeignKey(name = "fk_zaehlblatt_geschlossen_von"))
    private BenutzerEntity geschlossenVon;

    // Spaeter durch eine eigene Migration mit sync_request verknuepfen.
    @Column(name = "letzter_status_request_id")
    private UUID letzterStatusRequestId;

    protected ZaehlblattEntity() {
        // JPA benoetigt einen Konstruktor ohne Argumente.
    }

    public ZaehlblattEntity(UUID id, ZaehlsitzungEntity zaehlsitzung, String blattnummer,
            ZaehlblattStatus status, long statusVersion, Instant abgeschlossenAm,
            BenutzerEntity geschlossenVon, UUID letzterStatusRequestId) {
        this.id = id;
        this.zaehlsitzung = zaehlsitzung;
        this.blattnummer = blattnummer;
        this.status = status;
        this.statusVersion = statusVersion;
        this.abgeschlossenAm = abgeschlossenAm;
        this.geschlossenVon = geschlossenVon;
        this.letzterStatusRequestId = letzterStatusRequestId;
    }

    public UUID getId() { return id; }
    public ZaehlsitzungEntity getZaehlsitzung() { return zaehlsitzung; }
    public String getBlattnummer() { return blattnummer; }
    public ZaehlblattStatus getStatus() { return status; }
    public long getStatusVersion() { return statusVersion; }
    public Instant getAbgeschlossenAm() { return abgeschlossenAm; }
    public BenutzerEntity getGeschlossenVon() { return geschlossenVon; }
    public UUID getLetzterStatusRequestId() { return letzterStatusRequestId; }
}
