package com.example.myapplication;

import android.util.Log;
import android.view.View;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

// Nur feste UI-Ereignisse im Debug-Build, niemals Mengen, Artikel, Namen oder Notizen.
final class EingabeDiagnose {
    private EingabeDiagnose() { }

    static void beobachteTastatur(View view, String ansicht) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        final boolean[] sichtbar = {false};
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            boolean aktuell = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (aktuell != sichtbar[0]) {
                sichtbar[0] = aktuell;
                ereignis(ansicht + (aktuell ? ": Tastatur sichtbar" : ": Tastatur verborgen"));
            }
            return insets;
        });
    }

    static void ereignis(String ereignis) {
        if (BuildConfig.DEBUG) {
            Log.d("InventurEingabe", ereignis);
        }
    }
}
