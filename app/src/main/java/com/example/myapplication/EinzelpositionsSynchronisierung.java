package com.example.myapplication;

import java.io.IOException;
import java.util.Objects;

// Gemeinsamer Mengenablauf fuer Journaltests und einmalige JDBC-Uebertragungen.
public final class EinzelpositionsSynchronisierung {
    public enum Status { NICHTS_ZU_TUN, BESTAETIGT, AUSSTEHEND, KONFLIKT, ZUORDNUNG_UNGEKLAERT }

    public static final class Stand {
        public final int blatt, position;
        public final long positionsId, ergebnisId;
        public final String artikel, durchgang, token;
        public final Integer menge;

        public Stand(int blatt, int position, long positionsId, long ergebnisId, String artikel,
                String durchgang, String token, Integer menge) {
            if (blatt <= 0 || position <= 0 || positionsId <= 0 || ergebnisId <= 0
                    || leer(artikel) || leer(durchgang) || leer(token)) {
                throw new IllegalArgumentException("Eindeutiger bestaetigter Ergebnisstand fehlt.");
            }
            this.blatt = blatt; this.position = position; this.positionsId = positionsId;
            this.ergebnisId = ergebnisId; this.artikel = artikel; this.durchgang = durchgang;
            this.token = token; this.menge = menge;
        }

        public boolean gleicheZuordnung(Stand s) {
            return s != null && blatt == s.blatt && position == s.position && positionsId == s.positionsId
                    && ergebnisId == s.ergebnisId && artikel.equals(s.artikel) && durchgang.equals(s.durchgang);
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Stand)) return false;
            Stand s = (Stand) o;
            return gleicheZuordnung(s) && token.equals(s.token) && Objects.equals(menge, s.menge);
        }
        @Override public int hashCode() { return Objects.hash(blatt, position, positionsId, ergebnisId, artikel, durchgang, token, menge); }
    }

    public static final class Auftrag {
        public final String id;
        public final Stand erwartet;
        public final long lokaleRevision;
        public final Integer menge;

        public Auftrag(String id, Stand erwartet, long lokaleRevision, Integer menge) {
            if (leer(id) || erwartet == null || lokaleRevision <= 0) throw new IllegalArgumentException("Auftrag unvollstaendig.");
            this.id = id; this.erwartet = erwartet; this.lokaleRevision = lokaleRevision; this.menge = menge;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Auftrag)) return false;
            Auftrag a = (Auftrag) o;
            return id.equals(a.id) && erwartet.equals(a.erwartet) && lokaleRevision == a.lokaleRevision
                    && Objects.equals(menge, a.menge);
        }
        @Override public int hashCode() { return Objects.hash(id, erwartet, lokaleRevision, menge); }
    }

    public static final class Quittung {
        public final Auftrag auftrag;
        public final Stand gespeichert;
        public final boolean konflikt;
        public Quittung(Auftrag auftrag, Stand gespeichert, boolean konflikt) {
            this.auftrag = auftrag; this.gespeichert = gespeichert; this.konflikt = konflikt;
        }
    }

    public interface Lokal {
        Auftrag bereiteMengenauftragVor(int blatt, int position);
        void merkeMengenversuch(Auftrag auftrag);
        void quittiereMengenauftrag(Auftrag auftrag, Stand gespeichert);
        void merkeMengenkonflikt(Auftrag auftrag);
        default Status pruefeEinmalversuch(Auftrag auftrag) { return Status.AUSSTEHEND; }
    }

    public interface Server {
        boolean istIsolierterTestbetrieb();
        default boolean istFreigegebenerEinmalbetrieb() { return false; }
        default boolean hatDauerhafteAuftragsquittung() { return true; }
        // null bedeutet verlaesslich nicht vorhanden; Erreichbarkeitsfehler muessen IOException liefern.
        Quittung sucheAuftrag(String id) throws IOException;
        // Vergleich, Aenderung und dauerhafte Auftragsquittung muessen eine Transaktion bilden.
        Quittung verarbeite(Auftrag auftrag) throws IOException;
    }

    private final Lokal lokal;
    private final Server server;

    public EinzelpositionsSynchronisierung(Lokal lokal, Server server) {
        if (lokal == null || server == null || (!server.istIsolierterTestbetrieb() && !server.istFreigegebenerEinmalbetrieb())) {
            throw new IllegalArgumentException("Synchronisierungsweg nicht freigegeben.");
        }
        this.lokal = lokal; this.server = server;
    }

    public Status synchronisiere(int blatt, int position) {
        try {
            Auftrag a = lokal.bereiteMengenauftragVor(blatt, position);
            if (a == null) return Status.NICHTS_ZU_TUN;
            boolean einmal = !server.hatDauerhafteAuftragsquittung();
            if (einmal) {
                Status freigabe = lokal.pruefeEinmalversuch(a);
                if (freigabe != Status.NICHTS_ZU_TUN) return freigabe;
            }
            // Ohne Serverjournal wird ein bereits versuchter Auftrag niemals erneut gesendet.
            Quittung q = einmal ? null : server.sucheAuftrag(a.id);
            if (q == null) {
                lokal.merkeMengenversuch(a);
                q = server.verarbeite(a);
            }
            if (q == null || !a.equals(q.auftrag)) return Status.AUSSTEHEND;
            if (q.konflikt) {
                lokal.merkeMengenkonflikt(a);
                return Status.KONFLIKT;
            }
            Stand s = q.gespeichert;
            boolean bereitsGleich = einmal && Objects.equals(a.erwartet.menge, a.menge);
            if (!a.erwartet.gleicheZuordnung(s) || (a.erwartet.token.equals(s.token) && !bereitsGleich)
                    || !Objects.equals(a.menge, s.menge)) return Status.AUSSTEHEND;
            lokal.quittiereMengenauftrag(a, s);
            return Status.BESTAETIGT;
        } catch (IOException exception) {
            return Status.AUSSTEHEND;
        } catch (IllegalArgumentException exception) {
            return Status.ZUORDNUNG_UNGEKLAERT;
        } catch (RuntimeException exception) {
            // Auch ein lokaler Quittierungsfehler laesst den dauerhaften Auftrag offen.
            return Status.AUSSTEHEND;
        }
    }

    private static boolean leer(String text) { return text == null || text.trim().isEmpty(); }
}
