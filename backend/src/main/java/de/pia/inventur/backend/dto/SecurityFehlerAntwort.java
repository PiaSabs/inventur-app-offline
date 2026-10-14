package de.pia.inventur.backend.dto;

/** Oeffentliches, bewusst knappes Fehlerformat ohne interne Ursachen oder Requestinhalte. */
public record SecurityFehlerAntwort(int status, String code, String detail) {
}
