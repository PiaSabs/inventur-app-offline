package com.example.myapplication;

import android.content.Context;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

// Der Adapter verbindet die fachlichen Artikeldaten mit der RecyclerView.
// Er entscheidet also, wie ein einzelner Artikel auf dem Bildschirm befuellt wird.
public class ArtikelAdapter extends RecyclerView.Adapter<ArtikelAdapter.ArtikelViewHolder> {

    public interface OnArtikelInteraktionListener {
        void onArtikelStatusChanged();

        void onMengeBearbeiten(Artikel artikel);
    }

    private final List<Artikel> artikelListe;
    private final List<Artikel> sichtbareArtikel;
    private final OnArtikelInteraktionListener onArtikelInteraktionListener;
    private final boolean bearbeitungErlaubt;
    private String suchbegriff = "";
    private int filterModus = FILTER_ALLE;

    private static final int FILTER_ALLE = ArtikelFilter.FILTER_ALLE;
    private static final int FILTER_OFFEN = ArtikelFilter.FILTER_OFFEN;
    private static final int FILTER_GEZAEHLT = ArtikelFilter.FILTER_GEZAEHLT;
    private static final int FILTER_UNTER_MINDESTBESTAND = ArtikelFilter.FILTER_UNTER_MINDESTBESTAND;

    public ArtikelAdapter(
            List<Artikel> artikelListe,
            boolean bearbeitungErlaubt,
            OnArtikelInteraktionListener onArtikelInteraktionListener
    ) {
        this.artikelListe = artikelListe;
        this.sichtbareArtikel = new ArrayList<>(artikelListe);
        this.bearbeitungErlaubt = bearbeitungErlaubt;
        this.onArtikelInteraktionListener = onArtikelInteraktionListener;
    }

    @NonNull
    @Override
    public ArtikelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Hier wird das Layout fuer genau eine Listenzeile erzeugt.
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_artikel, parent, false);
        return new ArtikelViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ArtikelViewHolder holder, int position) {
        // Hier werden die Daten des Artikels an der aktuellen Position in die View geschrieben.
        holder.bind(sichtbareArtikel.get(position), bearbeitungErlaubt, onArtikelInteraktionListener);
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
        this.suchbegriff = ArtikelFilter.normalisiereSuchbegriff(suchbegriff);
        aktualisiereSichtbareArtikel();
    }

    private void aktualisiereSichtbareArtikel() {
        sichtbareArtikel.clear();
        for (Artikel artikel : artikelListe) {
            if (ArtikelFilter.passtZumFilter(artikel, filterModus)
                    && ArtikelFilter.passtZumSuchbegriff(artikel, suchbegriff)) {
                sichtbareArtikel.add(artikel);
            }
        }
        notifyDataSetChanged();
    }

    static class ArtikelViewHolder extends RecyclerView.ViewHolder {

        private final MaterialCardView artikelCardView;
        private final TextView positionsnummerTextView;
        private final TextView artikelnummerTextView;
        private final TextView anmerkungButton;
        private final TextView letzteZaehlmengeTextView;
        private final TextView bezeichnungTextView;
        private final TextView lagerortTextView;
        private final TextView lagerortDetailTextView;
        private final TextView istMengeTextView;
        private final TextView mindestbestandTextView;
        private final TextView zaehlstatusTextView;
        private final EditText zaehlMengeEditText;

        ArtikelViewHolder(@NonNull View itemView) {
            super(itemView);
            // Der ViewHolder merkt sich die Unterelemente einer Listenzeile,
            // damit sie beim Scrollen effizient wiederverwendet werden koennen.
            artikelCardView = itemView.findViewById(R.id.artikelCardView);
            positionsnummerTextView = itemView.findViewById(R.id.positionsnummerTextView);
            artikelnummerTextView = itemView.findViewById(R.id.artikelnummerTextView);
            anmerkungButton = itemView.findViewById(R.id.anmerkungButton);
            letzteZaehlmengeTextView = itemView.findViewById(R.id.letzteZaehlmengeTextView);
            bezeichnungTextView = itemView.findViewById(R.id.bezeichnungTextView);
            lagerortTextView = itemView.findViewById(R.id.lagerortTextView);
            lagerortDetailTextView = itemView.findViewById(R.id.lagerortDetailTextView);
            istMengeTextView = itemView.findViewById(R.id.istMengeTextView);
            mindestbestandTextView = itemView.findViewById(R.id.mindestbestandTextView);
            zaehlstatusTextView = itemView.findViewById(R.id.zaehlstatusTextView);
            zaehlMengeEditText = itemView.findViewById(R.id.zaehlMengeEditText);
        }

        void bind(Artikel artikel, boolean bearbeitungErlaubt, OnArtikelInteraktionListener onArtikelInteraktionListener) {
            // Diese Methode uebertraegt die Werte aus dem Modell in die sichtbaren Textfelder.
            positionsnummerTextView.setText(String.valueOf(artikel.getPositionsNummer()));
            artikelnummerTextView.setText(artikel.getArtikelnummer());
            bezeichnungTextView.setText(artikel.getBezeichnung());
            // In der Original-Artikelzeile gibt es den Lagerort aktuell nur im Detailbereich.
            // Falls ein Layout lagerortTextView nicht enthaelt, darf die Originalansicht nicht abstuerzen.
            if (lagerortTextView != null) {
                lagerortTextView.setText(artikel.getLagerort());
            }
            lagerortDetailTextView.setText(artikel.getLagerort());
            letzteZaehlmengeTextView.setText(formatiereMenge(artikel.getLetzteZaehlmenge()));
            istMengeTextView.setText(String.valueOf(artikel.getIstMenge()));
            mindestbestandTextView.setText(String.valueOf(artikel.getMindestbestand()));
            aktualisiereAnmerkungButton(artikel);

            Integer zaehlMenge = artikel.getZaehlMenge();
            zaehlMengeEditText.setText(zaehlMenge == null ? "" : String.valueOf(zaehlMenge));
            zaehlMengeEditText.setEnabled(bearbeitungErlaubt);
            zaehlMengeEditText.setFocusable(false);
            zaehlMengeEditText.setFocusableInTouchMode(false);
            zaehlMengeEditText.setAlpha(bearbeitungErlaubt ? 1f : 0.55f);
            zaehlMengeEditText.setOnClickListener(v -> onArtikelInteraktionListener.onMengeBearbeiten(artikel));
            anmerkungButton.setEnabled(bearbeitungErlaubt);
            anmerkungButton.setAlpha(bearbeitungErlaubt ? 1f : 0.55f);
            anmerkungButton.setOnClickListener(v -> {
                if (bearbeitungErlaubt) {
                    oeffneAnmerkungsDialog(artikel, onArtikelInteraktionListener);
                }
            });

            aktualisiereKartenrahmen(artikel);
        }

        private String formatiereMenge(Integer menge) {
            return menge == null ? itemView.getContext().getString(R.string.kein_mengenwert) : String.valueOf(menge);
        }

        private void oeffneAnmerkungsDialog(Artikel artikel, OnArtikelInteraktionListener onArtikelInteraktionListener) {
            Context context = itemView.getContext();
            EditText dialogEditText = new EditText(context);
            dialogEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            dialogEditText.setMinLines(3);
            dialogEditText.setMaxLines(5);
            dialogEditText.setHint(R.string.anmerkung_hint);
            dialogEditText.setText(artikel.getAnmerkung());
            dialogEditText.setSelection(dialogEditText.getText().length());

            new AlertDialog.Builder(context)
                    .setTitle(R.string.anmerkung_dialog_titel)
                    .setView(dialogEditText)
                    .setPositiveButton(R.string.anmerkung_dialog_speichern, (dialog, which) -> {
                        artikel.setAnmerkung(dialogEditText.getText() == null ? "" : dialogEditText.getText().toString().trim());
                        aktualisiereAnmerkungButton(artikel);
                        onArtikelInteraktionListener.onArtikelStatusChanged();
                    })
                    .setNegativeButton(R.string.anmerkung_dialog_abbrechen, (dialog, which) -> dialog.dismiss())
                    .show();
        }

        private void aktualisiereAnmerkungButton(Artikel artikel) {
            boolean hatAnmerkung = artikel.getAnmerkung() != null && !artikel.getAnmerkung().trim().isEmpty();
            anmerkungButton.setText(hatAnmerkung ? R.string.anmerkung_button_gesetzt : R.string.anmerkung_button);
            anmerkungButton.setTextColor(ContextCompat.getColor(
                    itemView.getContext(),
                    hatAnmerkung ? R.color.card_border_ok : android.R.color.white
            ));
        }

        private void aktualisiereKartenrahmen(Artikel artikel) {
            // Noch nicht erfasste Werte bleiben neutral. Sobald eine Zaehlmenge gesetzt ist,
            // zeigt der Rahmen direkt, ob der Mindestbestand unterschritten wurde.
            int strokeColor = ContextCompat.getColor(itemView.getContext(), R.color.card_border_neutral);
            if (artikel.getZaehlMenge() != null) {
                strokeColor = artikel.istUnterMindestbestand()
                        ? ContextCompat.getColor(itemView.getContext(), R.color.card_border_warning)
                        : ContextCompat.getColor(itemView.getContext(), R.color.card_border_ok);
            }

            artikelCardView.setStrokeWidth(6);
            artikelCardView.setStrokeColor(strokeColor);
            zaehlstatusTextView.setText(artikel.istGezaehlt()
                    ? R.string.zaehlstatus_gezaehlt
                    : R.string.zaehlstatus_offen);
        }
    }
}
