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
@Table(name = "ergebnis_historie")
public class ErgebnisHistorieEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zaehlposition_id", nullable = false)
    private ZaehlpositionEntity zaehlposition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private SyncRequestEntity request;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "zaehlmenge")
    private Integer zaehlmenge;

    @Column(name = "anmerkung", columnDefinition = "text")
    private String anmerkung;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "benutzer_id", nullable = false)
    private BenutzerEntity benutzer;

    @Column(name = "geaendert_am", nullable = false)
    private Instant geaendertAm;

    protected ErgebnisHistorieEntity() {
        // JPA benoetigt einen Konstruktor ohne Argumente.
    }

    public ErgebnisHistorieEntity(
            UUID id,
            ZaehlpositionEntity zaehlposition,
            SyncRequestEntity request,
            long revision,
            Integer zaehlmenge,
            String anmerkung,
            BenutzerEntity benutzer,
            Instant geaendertAm) {
        this.id = id;
        this.zaehlposition = zaehlposition;
        this.request = request;
        this.revision = revision;
        this.zaehlmenge = zaehlmenge;
        this.anmerkung = anmerkung;
        this.benutzer = benutzer;
        this.geaendertAm = geaendertAm;
    }

    public UUID getId() { return id; }
    public ZaehlpositionEntity getZaehlposition() { return zaehlposition; }
    public SyncRequestEntity getRequest() { return request; }
    public long getRevision() { return revision; }
    public Integer getZaehlmenge() { return zaehlmenge; }
    public String getAnmerkung() { return anmerkung; }
    public BenutzerEntity getBenutzer() { return benutzer; }
    public Instant getGeaendertAm() { return geaendertAm; }
}
