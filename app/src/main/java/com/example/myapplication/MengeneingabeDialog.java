package com.example.myapplication;

import android.content.Context;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

// Der Entwurf bleibt vom Artikel getrennt, bis Speichern ausdruecklich bestaetigt wird.
final class MengeneingabeDialog {
    private AlertDialog dialog;

    void zeige(Context context, Artikel artikel, Runnable nachSpeichern) {
        if (dialog != null) {
            EingabeDiagnose.ereignis("original: mehrfaches Oeffnen verhindert");
            return;
        }
        EditText eingabe = new EditText(context);
        eingabe.setInputType(InputType.TYPE_CLASS_NUMBER);
        eingabe.setSingleLine(true);
        eingabe.setHint(R.string.zaehl_menge_label);
        eingabe.setText(artikel.getZaehlMenge() == null ? "" : artikel.getZaehlMenge().toString());
        eingabe.setSelectAllOnFocus(true);
        eingabe.setOnFocusChangeListener((v, fokus) ->
                EingabeDiagnose.ereignis(fokus ? "original: Mengenfeld fokussiert" : "original: Mengenfeld Fokus verloren"));
        EingabeDiagnose.beobachteTastatur(eingabe, "original");
        dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.zaehl_menge_label)
                .setView(eingabe)
                .setPositiveButton(R.string.anmerkung_dialog_speichern, null)
                .setNegativeButton(R.string.anmerkung_dialog_abbrechen, (d, w) -> d.cancel())
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> EingabeDiagnose.ereignis("original: abgebrochen"));
        dialog.setOnDismissListener(d -> {
            EingabeDiagnose.ereignis("original: geschlossen");
            dialog = null;
        });
        dialog.setOnShowListener(d -> {
            EingabeDiagnose.ereignis("original: geoeffnet");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String text = eingabe.getText().toString().trim();
                Integer menge = null;
                if (!text.isEmpty()) {
                    try {
                        menge = Integer.parseInt(text);
                        if (menge < 0) {
                            throw new NumberFormatException();
                        }
                    } catch (NumberFormatException e) {
                        eingabe.setError(context.getString(R.string.zaehlmenge_ungueltig));
                        return;
                    }
                }
                artikel.setZaehlMenge(menge);
                EingabeDiagnose.ereignis("original: bestaetigt");
                nachSpeichern.run();
                dialog.dismiss();
            });
            eingabe.requestFocus();
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }

    void schliesse() {
        if (dialog != null) {
            EingabeDiagnose.ereignis("original: Activity beendet");
            dialog.dismiss();
        }
    }
}
