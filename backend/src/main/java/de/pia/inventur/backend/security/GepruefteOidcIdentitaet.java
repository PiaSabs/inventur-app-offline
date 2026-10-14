package de.pia.inventur.backend.security;

/**
 * Interner Nachweis-Traeger, spaeter ausschliesslich nach echter Tokenpruefung erzeugen.
 * Der Konstruktor validiert nur Pflichtwerte; er authentifiziert niemanden.
 * Niemals als HTTP-Eingabe verwenden oder aus ungeprueften Claims erzeugen.
 */
public record GepruefteOidcIdentitaet(String issuer, String subject) {
    public GepruefteOidcIdentitaet {
        if (leer(issuer) || leer(subject)) throw new IllegalArgumentException("Issuer und Subject sind erforderlich");
        // Keine Normalisierung: Issuer und Subject werden exakt verglichen.
    }

    private static boolean leer(String wert) {
        return wert == null || wert.codePoints().allMatch(zeichen ->
                Character.isWhitespace(zeichen) || Character.isSpaceChar(zeichen) || zeichen == 0x85);
    }
}
