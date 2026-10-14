package de.pia.inventur.backend.entity;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "zaehlposition")
public class ZaehlpositionEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zaehlblatt_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_zaehlposition_zaehlblatt"))
    private ZaehlblattEntity zaehlblatt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "artikel_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_zaehlposition_artikel"))
    private ArtikelEntity artikel;

    @Column(name = "positionsnummer", nullable = false)
    private int positionsnummer;

    @Column(columnDefinition = "text")
    private String lagerort;

    @Column(name = "ist_menge")
    private Integer istMenge;

    @Column(name = "mindestbestand")
    private Integer mindestbestand;

    protected ZaehlpositionEntity() {
        // JPA benoetigt einen Konstruktor ohne Argumente.
    }

    public ZaehlpositionEntity(UUID id, ZaehlblattEntity zaehlblatt, ArtikelEntity artikel,
            int positionsnummer, String lagerort, Integer istMenge, Integer mindestbestand) {
        this.id = id;
        this.zaehlblatt = zaehlblatt;
        this.artikel = artikel;
        this.positionsnummer = positionsnummer;
        this.lagerort = lagerort;
        this.istMenge = istMenge;
        this.mindestbestand = mindestbestand;
    }

    public UUID getId() { return id; }
    public ZaehlblattEntity getZaehlblatt() { return zaehlblatt; }
    public ArtikelEntity getArtikel() { return artikel; }
    public int getPositionsnummer() { return positionsnummer; }
    public String getLagerort() { return lagerort; }
    public Integer getIstMenge() { return istMenge; }
    public Integer getMindestbestand() { return mindestbestand; }
}
