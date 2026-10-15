package de.pia.inventur.backend.controller;

import de.pia.inventur.backend.dto.SecurityFehlerAntwort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ErgebnisController.class, ZaehlblattAbschlussController.class})
public class ErgebnisHttpFehlerHandler {
    @ExceptionHandler({ResponseStatusException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<SecurityFehlerAntwort> eingabeFehler(HttpServletRequest request) {
        String detail = request.getRequestURI().endsWith("/abschluss")
                ? "Abschlussrequest ist ungueltig." : "Ergebnisrequest ist ungueltig.";
        return ResponseEntity.badRequest().body(new SecurityFehlerAntwort(400, "UNGUELTIGER_REQUEST", detail));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class, IllegalStateException.class, ArithmeticException.class})
    ResponseEntity<SecurityFehlerAntwort> technischerFehler() {
        // Kein Logging von SQL-/Exceptiondetails und keine vorgetaeuschte dauerhafte Entscheidung.
        return ResponseEntity.status(503).header("Retry-After", "1").body(new SecurityFehlerAntwort(
                503, "SPEICHERUNG_VORUEBERGEHEND_NICHT_MOEGLICH", "Speicherung derzeit nicht moeglich. Identischen Request erneut versuchen."));
    }
}
