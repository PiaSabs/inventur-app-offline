package de.pia.inventur.backend.service.sync;

/** NEU_REGISTRIERT gilt nur innerhalb der laufenden Transaktion, nicht als Mengenquittung. */
public record RequestRegistrierungsergebnis(RequestRegistrierungsstatus status,
        GespeicherteRequestEntscheidung entscheidung) {
    public RequestRegistrierungsergebnis {
        if (status == null || ((status == RequestRegistrierungsstatus.BEKANNTE_ENTSCHEIDUNG) != (entscheidung != null))) {
            throw new IllegalArgumentException("Nur bekannte Entscheidungen duerfen Antwortinformationen enthalten");
        }
    }

    public String fehlercode() {
        return status == RequestRegistrierungsstatus.REQUEST_ID_KONFLIKT ? "REQUEST_ID_REUSED" : null;
    }
}
