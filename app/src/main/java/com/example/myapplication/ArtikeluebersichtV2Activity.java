package com.example.myapplication;

import android.graphics.Color;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

// V2 verwendet dieselbe Speicherung und Navigation mit einer eigenen Mengeneingabe.
public class ArtikeluebersichtV2Activity extends ArtikeluebersichtActivity {
    private BottomSheetDialog artikelDetailDialog;

    @Override
    protected int artikelLayout() {
        return R.layout.activity_artikeluebersicht_v2;
    }

    @Override
    protected RecyclerView.Adapter<?> erstelleArtikelAdapter(List<Artikel> artikel) {
        return new ArtikelAdapterV2(artikel, this::oeffneArtikelDetailBottomSheet);
    }

    @Override
    protected void setzeSuchbegriff(String suchbegriff) {
        ((ArtikelAdapterV2) artikelAdapter).setSuchbegriff(suchbegriff);
    }

    @Override
    protected void setzeFilter(int modus) {
        ArtikelAdapterV2 adapter = (ArtikelAdapterV2) artikelAdapter;
        switch (modus) {
            case FILTER_OFFEN: adapter.zeigeNurUngezaehlteArtikel(); break;
            case FILTER_GEZAEHLT: adapter.zeigeNurGezaehlteArtikel(); break;
            case FILTER_UNTER_MINDESTBESTAND: adapter.zeigeNurUnterMindestbestandArtikel(); break;
            default: adapter.zeigeAlleArtikel();
        }
    }

    @Override
    protected void schliesseMengeneingabe() {
        if (artikelDetailDialog != null) {
            EingabeDiagnose.ereignis("v2: Activity beendet");
            artikelDetailDialog.dismiss();
        }
    }

    private void oeffneArtikelDetailBottomSheet(Artikel artikel) {
        if (artikelDetailDialog != null) {
            EingabeDiagnose.ereignis("v2: mehrfaches Oeffnen verhindert");
            return;
        }
        if (istBearbeitungGesperrt()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.artikel_bearbeitung_gesperrt_titel)
                    .setMessage(R.string.artikel_bearbeitung_gesperrt_text)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                    .show();
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        artikelDetailDialog = dialog;
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> EingabeDiagnose.ereignis("v2: abgebrochen"));
        dialog.setOnDismissListener(d -> {
            EingabeDiagnose.ereignis("v2: geschlossen");
            artikelDetailDialog = null;
        });
        dialog.setOnShowListener(d -> {
            dialog.getBehavior().setSkipCollapsed(true);
            dialog.getBehavior().setHideable(false);
            dialog.getBehavior().setDraggable(false);
            dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            EingabeDiagnose.ereignis("v2: geoeffnet");
        });
        View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_artikel_v2, null);
        dialog.setContentView(sheetView);
        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            bottomSheet.setBackgroundColor(Color.TRANSPARENT);
        }

        TextView titleTextView = sheetView.findViewById(R.id.detailTitleTextView);
        TextView subtitleTextView = sheetView.findViewById(R.id.detailSubtitleTextView);
        TextView lagerortTextView = sheetView.findViewById(R.id.detailLagerortTextView);
        TextView istMengeTextView = sheetView.findViewById(R.id.detailIstMengeTextView);
        TextView mindestbestandTextView = sheetView.findViewById(R.id.detailMindestbestandTextView);
        TextView statusTextView = sheetView.findViewById(R.id.detailStatusTextView);
        TextView letzteZaehlungTextView = sheetView.findViewById(R.id.detailLetzteZaehlungTextView);
        EditText zaehlMengeEditText = sheetView.findViewById(R.id.detailZaehlMengeEditText);
        EditText anmerkungEditText = sheetView.findViewById(R.id.detailAnmerkungEditText);
        View minusButton = sheetView.findViewById(R.id.detailMinusButton);
        View plusButton = sheetView.findViewById(R.id.detailPlusButton);
        View speichernButton = sheetView.findViewById(R.id.detailSpeichernButton);
        sheetView.findViewById(R.id.detailAbbrechenButton).setOnClickListener(v -> dialog.cancel());

        titleTextView.setText(artikel.getBezeichnung());
        subtitleTextView.setText(getString(
                R.string.artikel_detail_untertitel,
                artikel.getPositionsNummer(),
                artikel.getArtikelnummer()
        ));
        lagerortTextView.setText(artikel.getLagerort());
        istMengeTextView.setText(String.valueOf(artikel.getIstMenge()));
        mindestbestandTextView.setText(String.valueOf(artikel.getMindestbestand()));
        statusTextView.setText(artikel.istGezaehlt() ? R.string.zaehlstatus_gezaehlt : R.string.zaehlstatus_offen);
        letzteZaehlungTextView.setText(formatiereMenge(artikel.getLetzteZaehlmenge()));
        zaehlMengeEditText.setText(artikel.getZaehlMenge() == null ? "" : String.valueOf(artikel.getZaehlMenge()));
        anmerkungEditText.setText(artikel.getAnmerkung());
        zaehlMengeEditText.setOnFocusChangeListener((v, fokus) ->
                EingabeDiagnose.ereignis(fokus ? "v2: Mengenfeld fokussiert" : "v2: Mengenfeld Fokus verloren"));
        EingabeDiagnose.beobachteTastatur(sheetView, "v2");

        minusButton.setOnClickListener(v -> {
            int aktuellerWert = parseZaehlmenge(zaehlMengeEditText);
            int neuerWert = Math.max(0, aktuellerWert - 1);
            zaehlMengeEditText.setText(String.valueOf(neuerWert));
            zaehlMengeEditText.setSelection(zaehlMengeEditText.getText().length());
        });

        plusButton.setOnClickListener(v -> {
            int aktuellerWert = parseZaehlmenge(zaehlMengeEditText);
            int neuerWert = aktuellerWert == Integer.MAX_VALUE ? aktuellerWert : aktuellerWert + 1;
            zaehlMengeEditText.setText(String.valueOf(neuerWert));
            zaehlMengeEditText.setSelection(zaehlMengeEditText.getText().length());
        });

        speichernButton.setOnClickListener(v -> {
            String mengeText = zaehlMengeEditText.getText() == null ? "" : zaehlMengeEditText.getText().toString().trim();
            Integer zaehlMenge = leseGueltigeZaehlmenge(zaehlMengeEditText, mengeText);
            if (zaehlMenge == null && !mengeText.isEmpty()) {
                return;
            }
            EingabeDiagnose.ereignis("v2: bestaetigt");
            artikel.setZaehlMenge(zaehlMenge);
            artikel.setAnmerkung(anmerkungEditText.getText() == null ? "" : anmerkungEditText.getText().toString().trim());
            artikelAdapter.notifyDataSetChanged();
            verarbeiteArtikelAenderung();
            aktualisiereLeereAnsicht();
            dialog.dismiss();
        });

        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }

    private Integer leseGueltigeZaehlmenge(EditText editText, String wert) {
        if (wert == null || wert.trim().isEmpty()) {
            return null;
        }
        try {
            int zaehlMenge = Integer.parseInt(wert.trim());
            if (zaehlMenge < 0) {
                editText.setError(getString(R.string.zaehlmenge_ungueltig));
                return null;
            }
            return zaehlMenge;
        } catch (NumberFormatException exception) {
            editText.setError(getString(R.string.zaehlmenge_ungueltig));
            return null;
        }
    }

    private String formatiereMenge(Integer menge) {
        return menge == null ? getString(R.string.kein_mengenwert) : String.valueOf(menge);
    }

    private int parseZaehlmenge(EditText editText) {
        String wert = editText.getText() == null ? "" : editText.getText().toString().trim();
        Integer zaehlMenge = leseGueltigeZaehlmenge(editText, wert);
        return zaehlMenge == null ? 0 : zaehlMenge;
    }

}
