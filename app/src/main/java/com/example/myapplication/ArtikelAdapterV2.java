package com.example.myapplication;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import android.content.res.ColorStateList;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

public class ArtikelAdapterV2 extends RecyclerView.Adapter<ArtikelAdapterV2.ArtikelViewHolder> {

    public interface OnArtikelAusgewaehltListener {
        void onArtikelAusgewaehlt(Artikel artikel);
    }

    private final List<Artikel> artikelListe;
    private final List<Artikel> sichtbareArtikel;
    private final OnArtikelAusgewaehltListener onArtikelAusgewaehltListener;
    private String suchbegriff = "";
    private int filterModus = FILTER_ALLE;

    private static final int FILTER_ALLE = 1;
    private static final int FILTER_OFFEN = 2;
    private static final int FILTER_GEZAEHLT = 3;
    private static final int FILTER_UNTER_MINDESTBESTAND = 4;

    public ArtikelAdapterV2(List<Artikel> artikelListe, OnArtikelAusgewaehltListener onArtikelAusgewaehltListener) {
        this.artikelListe = artikelListe;
        this.sichtbareArtikel = new ArrayList<>(artikelListe);
        this.onArtikelAusgewaehltListener = onArtikelAusgewaehltListener;
    }

    @NonNull
    @Override
    public ArtikelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_artikel_v2, parent, false);
        return new ArtikelViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ArtikelViewHolder holder, int position) {
        holder.bind(sichtbareArtikel.get(position), onArtikelAusgewaehltListener);
    }

    @Override
    public int getItemCount() {
        return sichtbareArtikel.size();
    }

    public void zeigeAlleArtikel() {
        filterModus = FILTER_ALLE;
        aktualisiereSichtbareArtikel();
    }

    public void zeigeNurGezaehlteArtikel() {
        filterModus = FILTER_GEZAEHLT;
        aktualisiereSichtbareArtikel();
    }

    public void zeigeNurUngezaehlteArtikel() {
        filterModus = FILTER_OFFEN;
        aktualisiereSichtbareArtikel();
    }

    public void zeigeNurUnterMindestbestandArtikel() {
        filterModus = FILTER_UNTER_MINDESTBESTAND;
        aktualisiereSichtbareArtikel();
    }

    public void setSuchbegriff(String suchbegriff) {
        this.suchbegriff = suchbegriff == null ? "" : suchbegriff.trim().toLowerCase();
        aktualisiereSichtbareArtikel();
    }

    private void aktualisiereSichtbareArtikel() {
        sichtbareArtikel.clear();
        for (Artikel artikel : artikelListe) {
            if (passtZumFilter(artikel) && passtZumSuchbegriff(artikel)) {
                sichtbareArtikel.add(artikel);
            }
        }
        notifyDataSetChanged();
    }

    private boolean passtZumFilter(Artikel artikel) {
        if (filterModus == FILTER_OFFEN) {
            return !artikel.istGezaehlt();
        }
        if (filterModus == FILTER_GEZAEHLT) {
            return artikel.istGezaehlt();
        }
        if (filterModus == FILTER_UNTER_MINDESTBESTAND) {
            return artikel.istUnterMindestbestand();
        }
        return true;
    }

    private boolean passtZumSuchbegriff(Artikel artikel) {
        if (suchbegriff.isEmpty()) {
            return true;
        }
        return enthaeltSuchbegriff(artikel.getArtikelnummer())
                || enthaeltSuchbegriff(artikel.getBezeichnung())
                || enthaeltSuchbegriff(artikel.getLagerort())
                || String.valueOf(artikel.getPositionsNummer()).contains(suchbegriff);
    }

    private boolean enthaeltSuchbegriff(String wert) {
        return wert != null && wert.toLowerCase().contains(suchbegriff);
    }

    static class ArtikelViewHolder extends RecyclerView.ViewHolder {

        private final MaterialCardView artikelCardView;
        private final TextView positionsnummerTextView;
        private final TextView bezeichnungTextView;
        private final TextView identTextView;
        private final TextView lagerortTextView;
        private final TextView letzteZaehlmengeTextView;
        private final TextView statusTextView;
        private final TextView mengeBadgeTextView;

        ArtikelViewHolder(@NonNull View itemView) {
            super(itemView);
            artikelCardView = itemView.findViewById(R.id.artikelCardView);
            positionsnummerTextView = itemView.findViewById(R.id.positionsnummerTextView);
            bezeichnungTextView = itemView.findViewById(R.id.bezeichnungTextView);
            identTextView = itemView.findViewById(R.id.identTextView);
            lagerortTextView = itemView.findViewById(R.id.lagerortTextView);
            letzteZaehlmengeTextView = itemView.findViewById(R.id.letzteZaehlmengeTextView);
            statusTextView = itemView.findViewById(R.id.statusTextView);
            mengeBadgeTextView = itemView.findViewById(R.id.mengeBadgeTextView);
        }

        void bind(Artikel artikel, OnArtikelAusgewaehltListener listener) {
            positionsnummerTextView.setText(String.valueOf(artikel.getPositionsNummer()));
            bezeichnungTextView.setText(artikel.getBezeichnung());
            identTextView.setText(artikel.getArtikelnummer());
            lagerortTextView.setText(itemView.getContext().getString(R.string.artikel_v2_lagerort_wert, artikel.getLagerort()));
            letzteZaehlmengeTextView.setText(itemView.getContext().getString(
                    R.string.artikel_v2_letzte_zaehlmenge_wert,
                    formatiereMenge(artikel.getLetzteZaehlmenge())
            ));

            boolean gezaehlt = artikel.istGezaehlt();
            statusTextView.setText(gezaehlt ? R.string.zaehlstatus_gezaehlt : R.string.zaehlstatus_offen);
            mengeBadgeTextView.setText(gezaehlt
                    ? String.valueOf(artikel.getZaehlMenge())
                    : itemView.getContext().getString(R.string.artikel_v2_offen_badge));

            int strokeColor = ContextCompat.getColor(itemView.getContext(), R.color.card_border_neutral);
            int badgeColor = ContextCompat.getColor(itemView.getContext(), R.color.card_border_neutral);
            if (gezaehlt) {
                strokeColor = artikel.istUnterMindestbestand()
                        ? ContextCompat.getColor(itemView.getContext(), R.color.card_border_warning)
                        : ContextCompat.getColor(itemView.getContext(), R.color.card_border_ok);
                badgeColor = strokeColor;
            }
            artikelCardView.setStrokeColor(strokeColor);
            ViewCompat.setBackgroundTintList(mengeBadgeTextView, ColorStateList.valueOf(badgeColor));

            artikelCardView.setOnClickListener(v -> listener.onArtikelAusgewaehlt(artikel));
        }

        private String formatiereMenge(Integer menge) {
            return menge == null ? itemView.getContext().getString(R.string.kein_mengenwert) : String.valueOf(menge);
        }
    }
}
