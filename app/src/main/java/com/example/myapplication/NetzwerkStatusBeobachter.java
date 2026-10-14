package com.example.myapplication;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;

public class NetzwerkStatusBeobachter {

    public interface Listener {
        void onNetzwerkStatusGeaendert(boolean istVerbunden);
    }

    private final ConnectivityManager connectivityManager;
    private final Listener listener;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            listener.onNetzwerkStatusGeaendert(true);
        }

        @Override
        public void onLost(@NonNull Network network) {
            listener.onNetzwerkStatusGeaendert(istNetzwerkVerfuegbar());
        }

        @Override
        public void onUnavailable() {
            listener.onNetzwerkStatusGeaendert(false);
        }
    };
    private boolean registriert;

    public NetzwerkStatusBeobachter(Context context, Listener listener) {
        this.connectivityManager = context.getSystemService(ConnectivityManager.class);
        this.listener = listener;
    }

    public void starten() {
        if (connectivityManager == null) {
            listener.onNetzwerkStatusGeaendert(false);
            return;
        }
        if (!registriert) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
            registriert = true;
        }
        listener.onNetzwerkStatusGeaendert(istNetzwerkVerfuegbar());
    }

    public void stoppen() {
        if (connectivityManager == null || !registriert) {
            return;
        }
        connectivityManager.unregisterNetworkCallback(networkCallback);
        registriert = false;
    }

    public boolean istNetzwerkVerfuegbar() {
        if (connectivityManager == null) {
            return false;
        }
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
        if (capabilities == null) {
            return false;
        }
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
