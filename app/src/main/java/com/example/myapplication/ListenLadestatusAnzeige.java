package com.example.myapplication;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class ListenLadestatusAnzeige {
    private ListenLadestatusAnzeige() { }

    static void laedt(Activity activity, Long letzterAbruf) {
        text(activity).setText(R.string.listenquelle_laden);
        activity.findViewById(R.id.listenLadeanzeige).setVisibility(View.VISIBLE);
        zeigeLetztenAbruf(activity, letzterAbruf);
    }

    static void ergebnis(Activity activity, Listenuebersicht stand) {
        activity.findViewById(R.id.listenLadeanzeige).setVisibility(View.GONE);
        Long zeit = stand.getLetzterErfolgreicherAbrufMillis();
        if (stand.istServerstand() && zeit != null) {
            text(activity).setText(activity.getString(R.string.listenquelle_server, formatiereUhrzeit(zeit)));
        } else {
            text(activity).setText(R.string.listenquelle_lokal);
        }
        zeigeLetztenAbruf(activity, zeit);
    }

    static void fehler(Activity activity, Long letzterAbruf) {
        activity.findViewById(R.id.listenLadeanzeige).setVisibility(View.GONE);
        text(activity).setText(R.string.listenquelle_fehler);
        zeigeLetztenAbruf(activity, letzterAbruf);
    }

    private static TextView text(Activity activity) {
        return activity.findViewById(R.id.listenLadestatusTextView);
    }

    private static void zeigeLetztenAbruf(Activity activity, Long zeit) {
        TextView letzterAbruf = activity.findViewById(R.id.listenLetzterAbrufTextView);
        letzterAbruf.setText(zeit == null ? activity.getString(R.string.listenquelle_noch_kein_abruf)
                : activity.getString(R.string.listenquelle_letzter_abruf, formatiereUhrzeit(zeit)));
    }

    private static String formatiereUhrzeit(long zeit) {
        return formatiereUhrzeit(zeit, TimeZone.getDefault());
    }

    static String formatiereUhrzeit(long zeit, TimeZone zeitzone) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.GERMANY);
        format.setTimeZone(zeitzone);
        return format.format(new Date(zeit));
    }
}
