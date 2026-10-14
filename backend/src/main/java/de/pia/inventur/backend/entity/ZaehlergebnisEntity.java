package de.pia.inventur.backend.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "zaehlergebnis")
public class ZaehlergebnisEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zaehlposition_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_zaehlergebnis_position"))
    private ZaehlpositionEntity zaehlposition;

    @Column(name = "zaehlmenge")
    private Integer zaehlmenge;

    @Column(columnDefinition = "text")
    private String anmerkung;

    @Column(nullable = false)
    private long revision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "geaendert_von",
            foreignKey = @ForeignKey(name = "fk_zaehlergebnis_geaendert_von"))
    private BenutzerEntity geaendertVon;

    @Column(name = "geaendert_am")
    private Instant geaendertAm;

    protected ZaehlergebnisEntity() {
        // JPA benoetigt einen Konstruktor ohne Argumente.
    }

    public ZaehlergebnisEntity(UUID id, ZaehlpositionEntity zaehlposition, Integer zaehlmenge,
            String anmerkung, long revision, BenutzerEntity geaendertVon, Instant geaendertAm) {
        this.id = id;
        this.zaehlposition = zaehlposition;
        this.zaehlmenge = zaehlmenge;
        this.anmerkung = anmerkung;
        this.revision = revision;
        this.geaendertVon = geaendertVon;
        this.geaendertAm = geaendertAm;
    }

    public UUID getId() { return id; }
    public ZaehlpositionEntity getZaehlposition() { return zaehlposition; }
    public Integer getZaehlmenge() { return zaehlmenge; }
    public String getAnmerkung() { return anmerkung; }
    public long getRevision() { return revision; }
    public BenutzerEntity getGeaendertVon() { return geaendertVon; }
    public Instant getGeaendertAm() { return geaendertAm; }
}
