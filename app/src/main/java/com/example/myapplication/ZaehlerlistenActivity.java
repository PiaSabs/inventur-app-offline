package com.example.myapplication;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Diese Activity zeigt zuerst die verfuegbaren Zaehlerlisten an.
// Erst nach Auswahl einer Liste wird in die konkrete Artikelansicht gewechselt.
public class ZaehlerlistenActivity extends AppCompatActivity {

    public static final String EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_ID = "ausgewaehlte_zaehlerlisten_id";
    public static final String EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_NUMMER = "ausgewaehlte_zaehlerlisten_nummer";
    public static final String EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_BEZEICHNUNG = "ausgewaehlte_zaehlerlisten_bezeichnung";
    public static final String EXTRA_ARTIKELUEBERSICHT_VERSION = "artikeluebersicht_version";
    public static final int ARTIKELUEBERSICHT_VERSION_1 = 1;
    public static final int ARTIKELUEBERSICHT_VERSION_2 = 2;
    private static final long PERIODISCHE_DATENBANKPRUEFUNG_MS = 30_000L;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final PeriodischerDatenbankPruefer periodischerDatenbankPruefer =
            new PeriodischerDatenbankPruefer(
                    PERIODISCHE_DATENBANKPRUEFUNG_MS,
                    this::pruefeDatenbankverbindungPeriodisch
            );
    private RecyclerView zaehlerlistenRecyclerView;
    private ArtikelRepository artikelRepository;
    private int ausgewaehlteZaehlerlistenId;
    private String ausgewaehlteZaehlerlistenNummer;
    private String ausgewaehlteZaehlerlistenBezeichnung;
    private int ausgewaehlteArtikeluebersichtVersion = ARTIKELUEBERSICHT_VERSION_2;
    private NetzwerkStatusBeobachter netzwerkStatusBeobachter;
    private boolean netzwerkVerfuegbar;
    private boolean netzwerkstatusInitialisiert;
    private List<Zaehlerliste> aktuellAngezeigteZaehlerlisten = Collections.emptyList();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_zaehlerlisten);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.zaehlerlistenRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        leseAuswahlAusIntent();
        setzeKopfbereich();
        registriereNavigation();

        zaehlerlistenRecyclerView = findViewById(R.id.zaehlerlistenRecyclerView);
        zaehlerlistenRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        artikelRepository = AppContainer.erstelleArtikelRepository(this);
        netzwerkStatusBeobachter = new NetzwerkStatusBeobachter(this, this::verarbeiteNetzwerkStatusAenderung);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                versucheZumHauptmenueZurueckzukehren();
            }
        });
    }


    @Override
    protected void onResume() {
        super.onResume();
        if (netzwerkStatusBeobachter != null) {
            netzwerkStatusBeobachter.starten();
        }
        periodischerDatenbankPruefer.starten();
        ladeZaehlerlistenInDieAnsicht();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (netzwerkStatusBeobachter != null) {
            netzwerkStatusBeobachter.stoppen();
        }
        periodischerDatenbankPruefer.stoppen();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdownNow();
    }


    // Laedt die Blaetter jedes Mal neu.
    // Wichtig nach Rueckkehr aus der Artikeluebersicht: Wenn ein Blatt dort als
    // abgeschlossen markiert wurde, soll die Liste sofort den neuen Status zeigen.
    private void ladeZaehlerlistenInDieAnsicht() {
        if (artikelRepository == null || zaehlerlistenRecyclerView == null || executorService.isShutdown()) {
            return;
        }

        executorService.execute(() -> {
            try {
                List<Zaehlerliste> zaehlerlisten = filtereBlaetterFuerAuswahl(artikelRepository.ladeZaehlerlisten());
                runOnUiThread(() -> {
                    aktuellAngezeigteZaehlerlisten = new ArrayList<>(zaehlerlisten);
                    ZaehlerlistenAdapter adapter = new ZaehlerlistenAdapter(
                            zaehlerlisten,
                            this::starteArtikeluebersicht,
                            ZaehlerlistenAdapter.AnzeigeModus.BLATTNUMMER
                    );
                    zaehlerlistenRecyclerView.setAdapter(adapter);
                    aktualisiereSynchronisierungsstatus();
                });
            } catch (RuntimeException exception) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Zaehlerlisten konnten nicht geladen werden.",
                        Toast.LENGTH_SHORT
                ).show());
            }
        });
    }

    private void leseAuswahlAusIntent() {
        Intent intent = getIntent();
        ausgewaehlteZaehlerlistenId = intent.getIntExtra(EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_ID, -1);
        ausgewaehlteZaehlerlistenNummer = intent.getStringExtra(EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_NUMMER);
        ausgewaehlteZaehlerlistenBezeichnung = intent.getStringExtra(EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_BEZEICHNUNG);
        ausgewaehlteArtikeluebersichtVersion = intent.getIntExtra(
                EXTRA_ARTIKELUEBERSICHT_VERSION,
                ARTIKELUEBERSICHT_VERSION_2
        );
    }

    private void starteArtikeluebersicht(Zaehlerliste zaehlerliste) {
        if (zaehlerliste.istAbgeschlossen()) {
            frageNachBearbeitungAbgeschlossenerListe(zaehlerliste);
            return;
        }
        starteArtikeluebersicht(zaehlerliste, false);
    }

    private void frageNachBearbeitungAbgeschlossenerListe(Zaehlerliste zaehlerliste) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.abgeschlossene_liste_dialog_titel)
                .setMessage(getString(R.string.abgeschlossene_liste_dialog_text, zaehlerliste.getBlattnummer()))
                .setPositiveButton(R.string.abgeschlossene_liste_dialog_bearbeiten, (dialog, which) -> oeffneAbgeschlosseneListeErneut(zaehlerliste))
                .setNegativeButton(R.string.abgeschlossene_liste_dialog_nicht_bearbeiten, (dialog, which) -> starteArtikeluebersicht(zaehlerliste, false))
                .setNeutralButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void oeffneAbgeschlosseneListeErneut(Zaehlerliste zaehlerliste) {
        executorService.execute(() -> {
            try {
                artikelRepository.markiereZaehlerlisteAlsOffen(zaehlerliste.getId());
                runOnUiThread(() -> starteArtikeluebersicht(zaehlerliste, true));
            } catch (RuntimeException exception) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        R.string.abgeschlossene_liste_oeffnen_fehler,
                        Toast.LENGTH_SHORT
                ).show());
            }
        });
    }

    private void starteArtikeluebersicht(Zaehlerliste zaehlerliste, boolean bearbeitungAbgeschlossenerListeErlaubt) {
        Class<?> zielActivity = ausgewaehlteArtikeluebersichtVersion == ARTIKELUEBERSICHT_VERSION_1
                ? ArtikeluebersichtActivity.class
                : ArtikeluebersichtV2Activity.class;
        Intent intent = new Intent(this, zielActivity);
        intent.putExtra(ArtikeluebersichtActivity.EXTRA_ZAEHLERLISTEN_ID, zaehlerliste.getId());
        intent.putExtra(ArtikeluebersichtActivity.EXTRA_ZAEHLERLISTEN_BEZEICHNUNG, ausgewaehlteZaehlerlistenBezeichnung);
        intent.putExtra(ArtikeluebersichtActivity.EXTRA_ABGESCHLOSSENE_BEARBEITUNG_ERLAUBT, bearbeitungAbgeschlossenerListeErlaubt);
        startActivity(intent);
    }

    private void setzeKopfbereich() {
        TextView titleTextView = findViewById(R.id.zaehlerlistenTitleTextView);
        TextView subtitleTextView = findViewById(R.id.zaehlerlistenSubtitleTextView);

        String zaehlerlistenNummer = ausgewaehlteZaehlerlistenNummer == null
                || ausgewaehlteZaehlerlistenNummer.trim().isEmpty()
                ? String.valueOf(ausgewaehlteZaehlerlistenId)
                : ausgewaehlteZaehlerlistenNummer.trim();

        titleTextView.setText(getString(R.string.zaehlerlisten_detail_titel, zaehlerlistenNummer));
        subtitleTextView.setText(R.string.zaehlerlisten_detail_untertitel);
    }

    private void registriereNavigation() {
        ImageButton zurueckButton = findViewById(R.id.zaehlerlistenZurueckButton);
        zurueckButton.setOnClickListener(v -> versucheZumHauptmenueZurueckzukehren());

        ImageButton aktualisierenButton = findViewById(R.id.zaehlerlistenAktualisierenButton);
        aktualisierenButton.setOnClickListener(v -> pruefeDatenbankverbindungManuell());
    }

    private void aktualisiereSynchronisierungsstatus() {
        View syncStatusContainer = findViewById(R.id.syncStatusContainer);
        TextView syncStatusTextView = findViewById(R.id.syncStatusTextView);
        View syncStatusInfoButton = findViewById(R.id.syncStatusInfoButton);
        if (!AppContainer.istApiModusAktiv()) {
            syncStatusContainer.setVisibility(View.GONE);
            return;
        }

        boolean hatOffeneSynchronisierung = hatOffeneSynchronisierungInAktuellerAuswahl();
        syncStatusContainer.setVisibility(View.VISIBLE);
        if (hatOffeneSynchronisierung && !netzwerkVerfuegbar) {
            syncStatusTextView.setText(R.string.sync_status_kein_netzwerk);
            syncStatusTextView.setTextColor(Color.parseColor("#FFE19A"));
            syncStatusInfoButton.setVisibility(View.VISIBLE);
        } else {
            syncStatusTextView.setText(hatOffeneSynchronisierung
                    ? R.string.sync_status_offen
                    : R.string.sync_status_erfolgreich);
            syncStatusTextView.setTextColor(Color.parseColor(hatOffeneSynchronisierung ? "#FFE19A" : "#B6F0C2"));
            syncStatusInfoButton.setVisibility(hatOffeneSynchronisierung ? View.VISIBLE : View.GONE);
        }
        syncStatusInfoButton.setOnClickListener(v -> zeigeSynchronisierungsdetails());
    }

    private void zeigeSynchronisierungsdetails() {
        boolean hatOffeneSynchronisierung = hatOffeneSynchronisierungInAktuellerAuswahl();
        if (!netzwerkVerfuegbar && hatOffeneSynchronisierung) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.sync_status_dialog_titel)
                    .setMessage(R.string.sync_status_kein_netzwerk_detail)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                    .show();
            return;
        }
        if (!hatOffeneSynchronisierung) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.sync_status_dialog_titel)
                    .setMessage(R.string.sync_status_kein_offener_sync_detail)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.sync_status_dialog_titel)
                .setMessage(artikelRepository.holeLetzteSynchronisierungsFehlermeldung())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void verarbeiteNetzwerkStatusAenderung(boolean istVerbunden) {
        runOnUiThread(() -> {
            boolean statusHatSichGeaendert = netzwerkstatusInitialisiert && netzwerkVerfuegbar != istVerbunden;
            netzwerkVerfuegbar = istVerbunden;
            netzwerkstatusInitialisiert = true;
            aktualisiereSynchronisierungsstatus();
            if (statusHatSichGeaendert && istVerbunden && artikelRepository != null) {
                pruefeSynchronisierungNachNetzwechsel();
            }
        });
    }

    private void pruefeSynchronisierungNachNetzwechsel() {
        executorService.execute(() -> {
            artikelRepository.verarbeiteNetzwerkWiederVerfuegbar();
            runOnUiThread(this::ladeZaehlerlistenInDieAnsicht);
        });
    }

    private void pruefeDatenbankverbindungPeriodisch() {
        if (!AppContainer.istApiModusAktiv() || artikelRepository == null || !netzwerkVerfuegbar) {
            return;
        }
        executorService.execute(() -> {
            artikelRepository.pruefeDatenbankverbindungUndSynchronisiereBeiBedarf();
            runOnUiThread(this::ladeZaehlerlistenInDieAnsicht);
        });
    }

    private void pruefeDatenbankverbindungManuell() {
        Toast.makeText(this, R.string.datenbank_pruefung_startet, Toast.LENGTH_SHORT).show();
        executorService.execute(() -> {
            artikelRepository.pruefeDatenbankverbindungUndSynchronisiereSofort();
            runOnUiThread(() -> {
                ladeZaehlerlistenInDieAnsicht();
                if (hatOffeneSynchronisierungInAktuellerAuswahl()) {
                    Toast.makeText(this, R.string.sync_status_offen, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.datenbank_pruefung_erfolgreich, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void versucheZumHauptmenueZurueckzukehren() {
        if (!AppContainer.istApiModusAktiv() || artikelRepository == null || !hatOffeneSynchronisierungInAktuellerAuswahl()) {
            finish();
            return;
        }
        zeigeHauptmenueBlockiertDialog();
    }

    private boolean hatOffeneSynchronisierungInAktuellerAuswahl() {
        if (artikelRepository == null) {
            return false;
        }
        if (aktuellAngezeigteZaehlerlisten == null || aktuellAngezeigteZaehlerlisten.isEmpty()) {
            return artikelRepository.hatOffeneSynchronisierung();
        }
        for (Zaehlerliste zaehlerliste : aktuellAngezeigteZaehlerlisten) {
            if (zaehlerliste != null && artikelRepository.hatOffeneSynchronisierungFuerZaehlerliste(zaehlerliste.getId())) {
                return true;
            }
        }
        return false;
    }

    private void zeigeHauptmenueBlockiertDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sync_hauptmenue_blockiert_titel)
                .setMessage(getString(netzwerkVerfuegbar
                        ? R.string.sync_hauptmenue_blockiert_offen_text
                        : R.string.sync_hauptmenue_blockiert_offline_text))
                .setPositiveButton(R.string.sync_hauptmenue_dialog_retry, (dialog, which) -> versucheSynchronisierungVorRueckkehr())
                .setNegativeButton(R.string.sync_hauptmenue_dialog_bleiben, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void versucheSynchronisierungVorRueckkehr() {
        Toast.makeText(this, R.string.datenbank_pruefung_startet, Toast.LENGTH_SHORT).show();
        executorService.execute(() -> {
            artikelRepository.pruefeDatenbankverbindungUndSynchronisiereSofort();
            runOnUiThread(() -> {
                ladeZaehlerlistenInDieAnsicht();
                if (hatOffeneSynchronisierungInAktuellerAuswahl()) {
                    Toast.makeText(this, R.string.sync_hauptmenue_retry_offen, Toast.LENGTH_SHORT).show();
                    zeigeHauptmenueBlockiertDialog();
                    return;
                }
                Toast.makeText(this, R.string.sync_hauptmenue_retry_erfolgreich, Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    private List<Zaehlerliste> filtereBlaetterFuerAuswahl(List<Zaehlerliste> alleZaehlerlisten) {
        if (alleZaehlerlisten == null || alleZaehlerlisten.isEmpty()) {
            return Collections.emptyList();
        }

        List<Zaehlerliste> gefilterteZaehlerlisten = new ArrayList<>();
        String bezeichnungFilter = normalisiereText(ausgewaehlteZaehlerlistenBezeichnung);

        for (Zaehlerliste zaehlerliste : alleZaehlerlisten) {
            if (!bezeichnungFilter.isEmpty() && bezeichnungFilter.equals(normalisiereText(zaehlerliste.getBezeichnung()))) {
                gefilterteZaehlerlisten.add(zaehlerliste);
            }
        }

        if (!gefilterteZaehlerlisten.isEmpty()) {
            return gefilterteZaehlerlisten;
        }

        for (Zaehlerliste zaehlerliste : alleZaehlerlisten) {
            if (zaehlerliste.getId() == ausgewaehlteZaehlerlistenId) {
                gefilterteZaehlerlisten.add(zaehlerliste);
                break;
            }
        }
        return gefilterteZaehlerlisten;
    }

    private String normalisiereText(String wert) {
        return wert == null ? "" : wert.trim().toLowerCase(Locale.GERMANY);
    }
}
