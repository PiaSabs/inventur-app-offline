package com.example.myapplication;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.List;

// Dieser Adapter zeigt die verfuegbaren Zaehlerlisten im Auswahlbildschirm an.
// Ein Klick auf einen Eintrag oeffnet anschliessend die passende Artikeluebersicht.
public class ZaehlerlistenAdapter extends RecyclerView.Adapter<ZaehlerlistenAdapter.ZaehlerlisteViewHolder> {

    public enum AnzeigeModus {
        STANDARD,
        BLATTNUMMER
    }

    public interface OnZaehlerlisteClickListener {
        void onZaehlerlisteClick(Zaehlerliste zaehlerliste);
    }

    private final List<Zaehlerliste> zaehlerlisten;
    private final OnZaehlerlisteClickListener onZaehlerlisteClickListener;
    private final AnzeigeModus anzeigeModus;

    public ZaehlerlistenAdapter(List<Zaehlerliste> zaehlerlisten, OnZaehlerlisteClickListener onZaehlerlisteClickListener) {
        this(zaehlerlisten, onZaehlerlisteClickListener, AnzeigeModus.STANDARD);
    }

    public ZaehlerlistenAdapter(
            List<Zaehlerliste> zaehlerlisten,
            OnZaehlerlisteClickListener onZaehlerlisteClickListener,
            AnzeigeModus anzeigeModus
    ) {
        this.zaehlerlisten = zaehlerlisten;
        this.onZaehlerlisteClickListener = onZaehlerlisteClickListener;
        this.anzeigeModus = anzeigeModus;
    }

    @NonNull
    @Override
    public ZaehlerlisteViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_zaehlerliste, parent, false);
        return new ZaehlerlisteViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ZaehlerlisteViewHolder holder, int position) {
        holder.bind(zaehlerlisten.get(position), onZaehlerlisteClickListener, anzeigeModus);
    }

    @Override
    public int getItemCount() {
        return zaehlerlisten.size();
    }

    static class ZaehlerlisteViewHolder extends RecyclerView.ViewHolder {

        private final MaterialCardView zaehlerlisteCardView;
        private final TextView bezeichnungTextView;
        private final TextView bereichTextView;
        private final TextView letztesZaehldatumTextView;
        private final TextView statusTextView;

        ZaehlerlisteViewHolder(@NonNull View itemView) {
            super(itemView);
            zaehlerlisteCardView = itemView.findViewById(R.id.zaehlerlisteCardView);
            bezeichnungTextView = itemView.findViewById(R.id.zaehlerlisteBezeichnungTextView);
            bereichTextView = itemView.findViewById(R.id.zaehlerlisteBereichTextView);
            letztesZaehldatumTextView = itemView.findViewById(R.id.zaehlerlisteLetztesDatumTextView);
            statusTextView = itemView.findViewById(R.id.zaehlerlisteStatusTextView);
        }

        void bind(Zaehlerliste zaehlerliste, OnZaehlerlisteClickListener listener, AnzeigeModus anzeigeModus) {
            if (anzeigeModus == AnzeigeModus.BLATTNUMMER) {
                bezeichnungTextView.setText(itemView.getContext().getString(R.string.zaehlerlisten_blattnummer_titel, zaehlerliste.getBlattnummer()));
                bereichTextView.setText(itemView.getContext().getString(R.string.zaehlerlisten_detail_liste_prefix, zaehlerliste.getBezeichnung()));
                letztesZaehldatumTextView.setText(itemView.getContext().getString(
                        R.string.artikeluebersicht_letzte_zaehlung_prefix,
                        zaehlerliste.getLetztesZaehldatum()
                ));
            } else {
                bezeichnungTextView.setText(zaehlerliste.getBezeichnung());
                bereichTextView.setText(zaehlerliste.getBereich());
                letztesZaehldatumTextView.setText(zaehlerliste.getLetztesZaehldatum());
            }
            if (zaehlerliste.istAbgeschlossen()) {
                statusTextView.setText(R.string.zaehlerliste_status_abgeschlossen);
                zaehlerlisteCardView.setStrokeColor(ContextCompat.getColor(itemView.getContext(), R.color.card_border_ok));
            } else if (zaehlerliste.istFaellig()) {
                statusTextView.setText(R.string.zaehlerliste_status_faellig);
                zaehlerlisteCardView.setStrokeColor(ContextCompat.getColor(itemView.getContext(), R.color.card_border_warning));
            } else {
                statusTextView.setText(R.string.zaehlerliste_status_nicht_faellig);
                zaehlerlisteCardView.setStrokeColor(ContextCompat.getColor(itemView.getContext(), R.color.card_border_muted));
            }
            zaehlerlisteCardView.setStrokeWidth(5);
            View.OnClickListener onClickListener = v -> listener.onZaehlerlisteClick(zaehlerliste);
            itemView.setOnClickListener(onClickListener);
            zaehlerlisteCardView.setOnClickListener(onClickListener);
        }
    }
}
