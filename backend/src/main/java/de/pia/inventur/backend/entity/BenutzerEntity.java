package de.pia.inventur.backend.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "benutzer")
public class BenutzerEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(nullable = false, columnDefinition = "text")
    private String anzeigename;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private BenutzerRolle rolle;

    @Column(nullable = false)
    private boolean aktiv;

    @Column(name = "oidc_issuer", columnDefinition = "text")
    private String oidcIssuer;

    @Column(name = "oidc_subject", columnDefinition = "text")
    private String oidcSubject;

    protected BenutzerEntity() {
        // JPA benoetigt einen Konstruktor ohne Argumente.
    }

    public BenutzerEntity(UUID id, String anzeigename, BenutzerRolle rolle, boolean aktiv) {
        this(id, anzeigename, rolle, aktiv, null, null);
    }

    public BenutzerEntity(UUID id, String anzeigename, BenutzerRolle rolle, boolean aktiv,
            String oidcIssuer, String oidcSubject) {
        this.id = id;
        this.anzeigename = anzeigename;
        this.rolle = rolle;
        this.aktiv = aktiv;
        this.oidcIssuer = oidcIssuer;
        this.oidcSubject = oidcSubject;
    }

    public UUID getId() {
        return id;
    }

    public String getAnzeigename() {
        return anzeigename;
    }

    public BenutzerRolle getRolle() {
        return rolle;
    }

    public boolean isAktiv() {
        return aktiv;
    }

    public String getOidcIssuer() { return oidcIssuer; }

    public String getOidcSubject() { return oidcSubject; }
}
