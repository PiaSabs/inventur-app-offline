package de.pia.inventur.backend.entity;

import java.time.Instant;
import java.time.LocalDate;
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
@Table(name = "zaehlsitzung")
public class ZaehlsitzungEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zaehlerliste_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_zaehlsitzung_zaehlerliste"))
    private ZaehlerlisteEntity zaehlerliste;

    @Column(nullable = false)
    private LocalDate zaehltag;

    @Column(name = "runde_nr", nullable = false)
    private int rundeNr;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private ZaehlsitzungStatus status;

    @Column(name = "status_version", nullable = false)
    private long statusVersion;

    @Column(name = "gestartet_am", nullable = false)
    private Instant gestartetAm;

    @Column(name = "abgeschlossen_am")
    private Instant abgeschlossenAm;

    protected ZaehlsitzungEntity() {
        // JPA benoetigt einen Konstruktor ohne Argumente.
    }

    public ZaehlsitzungEntity(UUID id, ZaehlerlisteEntity zaehlerliste, LocalDate zaehltag,
            int rundeNr, ZaehlsitzungStatus status, long statusVersion,
            Instant gestartetAm, Instant abgeschlossenAm) {
        this.id = id;
        this.zaehlerliste = zaehlerliste;
        this.zaehltag = zaehltag;
        this.rundeNr = rundeNr;
        this.status = status;
        this.statusVersion = statusVersion;
        this.gestartetAm = gestartetAm;
        this.abgeschlossenAm = abgeschlossenAm;
    }

    public UUID getId() { return id; }
    public ZaehlerlisteEntity getZaehlerliste() { return zaehlerliste; }
    public LocalDate getZaehltag() { return zaehltag; }
    public int getRundeNr() { return rundeNr; }
    public ZaehlsitzungStatus getStatus() { return status; }
    public long getStatusVersion() { return statusVersion; }
    public Instant getGestartetAm() { return gestartetAm; }
    public Instant getAbgeschlossenAm() { return abgeschlossenAm; }
}
