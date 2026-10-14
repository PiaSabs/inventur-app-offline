package de.pia.inventur.backend.entity;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "artikel")
public class ArtikelEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(nullable = false, columnDefinition = "text")
    private String artikelnummer;

    @Column(nullable = false, columnDefinition = "text")
    private String bezeichnung;

    protected ArtikelEntity() {
        // JPA benoetigt einen Konstruktor ohne Argumente.
    }

    public ArtikelEntity(UUID id, String artikelnummer, String bezeichnung) {
        this.id = id;
        this.artikelnummer = artikelnummer;
        this.bezeichnung = bezeichnung;
    }

    public UUID getId() { return id; }
    public String getArtikelnummer() { return artikelnummer; }
    public String getBezeichnung() { return bezeichnung; }
}
