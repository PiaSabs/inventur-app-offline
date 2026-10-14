package com.example.myapplication;

import android.os.Handler;
import android.os.Looper;

// Dieser Helfer startet einen einfachen Vordergrund-Takt fuer wiederkehrende
// Datenbank- oder Synchronisierungspruefungen, ohne die Activities aufzublaehen.
public class PeriodischerDatenbankPruefer {

    public interface Listener {
        void onPruefungFaellig();
    }

    private final long intervallMillis;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pruefRunnable = new Runnable() {
        @Override
        public void run() {
            listener.onPruefungFaellig();
            if (gestartet) {
                handler.postDelayed(this, intervallMillis);
            }
        }
    };
    private boolean gestartet;

    public PeriodischerDatenbankPruefer(long intervallMillis, Listener listener) {
        this.intervallMillis = intervallMillis;
        this.listener = listener;
    }

    public void starten() {
        if (gestartet) {
            return;
        }
        gestartet = true;
        handler.postDelayed(pruefRunnable, intervallMillis);
    }

    public void stoppen() {
        if (!gestartet) {
            return;
        }
        gestartet = false;
        handler.removeCallbacks(pruefRunnable);
    }
}
