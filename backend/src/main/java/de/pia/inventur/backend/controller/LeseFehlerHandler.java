package de.pia.inventur.backend.controller;

import de.pia.inventur.backend.dto.SecurityFehlerAntwort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

/** Direkte JSON-Antwort verhindert Error-Redispatch und verraet keine fremden Ressourcen. */
@RestControllerAdvice
public class LeseFehlerHandler {
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<SecurityFehlerAntwort> medientypFehler() {
        return ResponseEntity.status(415).body(new SecurityFehlerAntwort(415, "UNTERSTUETZTER_MEDIENTYP_ERFORDERLICH",
                "Der Endpunkt benoetigt einen unterstuetzten Content-Type."));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<SecurityFehlerAntwort> ressourcenFehler(ResponseStatusException error) {
        int status = error.getStatusCode().value();
        return ResponseEntity.status(status).body(new SecurityFehlerAntwort(status,
                status == 404 ? "RESSOURCE_NICHT_GEFUNDEN" : "ANFRAGE_ABGELEHNT",
                status == 404 ? "Ressource nicht gefunden." : "Anfrage konnte nicht verarbeitet werden."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<SecurityFehlerAntwort> ungueltigerParameter() {
        return ResponseEntity.badRequest().body(new SecurityFehlerAntwort(400, "UNGUELTIGER_PARAMETER",
                "Ein Anfrageparameter ist ungueltig."));
    }
}
