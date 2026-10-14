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
@Table(name = "zaehlerliste")
public class ZaehlerlisteEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(nullable = false, unique = true, columnDefinition = "text")
    private String code;

    @Column(nullable = false, columnDefinition = "text")
    private String bezeichnung;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aktueller_zaehler_id",
            foreignKey = @ForeignKey(name = "fk_zaehlerliste_aktueller_zaehler"))
    private BenutzerEntity aktuellerZaehler;

    @Column(name = "zuweisung_version", nullable = false)
    private long zuweisungVersion;

    protected ZaehlerlisteEntity() {
        // JPA benoetigt einen Konstruktor ohne Argumente.
    }

    public ZaehlerlisteEntity(UUID id, String code, String bezeichnung) {
        this.id = id;
        this.code = code;
        this.bezeichnung = bezeichnung;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getBezeichnung() {
        return bezeichnung;
    }

    public BenutzerEntity getAktuellerZaehler() {
        return aktuellerZaehler;
    }

    public long getZuweisungVersion() {
        return zuweisungVersion;
    }
}
