package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;

public class Benutzersitzung {

    private static final String PREFS_NAME = "inventur_session";
    private static final String KEY_ZAEHLER_NAME = "zaehler_name";

    private final SharedPreferences sharedPreferences;

    public Benutzersitzung(Context context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void setzeAngemeldetenZaehler(String zaehlerName) {
        sharedPreferences.edit().putString(KEY_ZAEHLER_NAME, zaehlerName).apply();
    }

    public String holeAngemeldetenZaehler() {
        return sharedPreferences.getString(KEY_ZAEHLER_NAME, "");
    }

    public boolean istZaehlerAngemeldet() {
        String zaehlerName = holeAngemeldetenZaehler();
        return zaehlerName != null && !zaehlerName.trim().isEmpty();
    }

    public void abmelden() {
        sharedPreferences.edit().remove(KEY_ZAEHLER_NAME).apply();
    }
}
