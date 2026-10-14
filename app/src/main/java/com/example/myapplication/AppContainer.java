package com.example.myapplication;

import android.content.Context;

// Der Container kapselt, welche konkrete Datenquelle die App aktuell verwendet.
// Dadurch wird der Wechsel von Mock-Daten auf eine echte API spaeter an genau
// einer Stelle vorgenommen.
public final class AppContainer {

    private static final boolean API_MODUS_AKTIV = false;
    private static final InventurRemoteDataSource MOCK_REMOTE_DATA_SOURCE = new MockInventurRemoteDataSource();
    private static ArtikelRepository mockArtikelRepository;
    private static ArtikelRepository apiArtikelRepository;

    private AppContainer() {
    }

    public static boolean istApiModusAktiv() {
        return API_MODUS_AKTIV;
    }

    public static boolean istMockModusAktiv() {
        return !API_MODUS_AKTIV;
    }

    public static synchronized ArtikelRepository erstelleArtikelRepository(Context context) {
        Context appContext = context.getApplicationContext();
        if (mockArtikelRepository == null) {
            mockArtikelRepository = new ArtikelRepository(
                MOCK_REMOTE_DATA_SOURCE,
                new LokalerInventurSpeicher(appContext, "inventur_demo_offline.db")
            );
        }
        if (apiArtikelRepository == null) {
            LokalerInventurSpeicher lokalerInventurSpeicher = new LokalerInventurSpeicher(appContext, "inventur_demo_offline.db");
            apiArtikelRepository = new ArtikelRepository(
                    new ApiInventurRemoteDataSource(lokalerInventurSpeicher),
                    lokalerInventurSpeicher
            );
        }
        return API_MODUS_AKTIV ? apiArtikelRepository : mockArtikelRepository;
    }
}
