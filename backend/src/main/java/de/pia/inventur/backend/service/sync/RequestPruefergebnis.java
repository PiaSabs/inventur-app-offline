package de.pia.inventur.backend.service.sync;

public record RequestPruefergebnis(RequestPruefstatus status, GespeicherteRequestEntscheidung entscheidung) {
    public RequestPruefergebnis {
        if (status == null || ((status == RequestPruefstatus.BEKANNTE_ENTSCHEIDUNG) != (entscheidung != null))) {
            throw new IllegalArgumentException("Nur eine bekannte Entscheidung darf Antwortinformationen enthalten");
        }
    }

    public String fehlercode() {
        return status == RequestPruefstatus.REQUEST_ID_KONFLIKT ? "REQUEST_ID_REUSED" : null;
    }
}
