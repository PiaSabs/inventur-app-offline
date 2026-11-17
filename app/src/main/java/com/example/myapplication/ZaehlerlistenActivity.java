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
    private boolean ansichtAktiv;
    private int ladeGeneration;
    private Listenuebersicht listenuebersicht;
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
        ansichtAktiv = true;
        if (netzwerkStatusBeobachter != null) {
            netzwerkStatusBeobachter.starten();
        }
        periodischerDatenbankPruefer.starten();
        ladeZaehlerlistenInDieAnsicht();
    }

    @Override
    protected void onPause() {
        ansichtAktiv = false;
        ladeGeneration++;
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


    private void ladeZaehlerlistenInDieAnsicht() {
        if (!ansichtAktiv || artikelRepository == null || executorService.isShutdown()) return;
        int generation = ++ladeGeneration;
        ListenLadestatusAnzeige.laedt(this, artikelRepository.holeLetztenErfolgreichenListenabrufMillis());
        executorService.execute(() -> {
            try {
                Listenuebersicht uebersicht = artikelRepository.aktualisiereListenuebersicht();
                List<Zaehlerliste> listen = uebersicht.filtereAuswahl(
                        ausgewaehlteZaehlerlistenId, ausgewaehlteZaehlerlistenBezeichnung);
                runOnUiThread(() -> {
                    if (!ansichtAktiv || generation != ladeGeneration || isDestroyed()) return;
                    listenuebersicht = uebersicht;
                    aktuellAngezeigteZaehlerlisten = new ArrayList<>(listen);
                    zaehlerlistenRecyclerView.setAdapter(new ZaehlerlistenAdapter(listen, this::starteArtikeluebersicht,
                            ZaehlerlistenAdapter.AnzeigeModus.BLATTNUMMER, uebersicht));
                    ListenLadestatusAnzeige.ergebnis(this, uebersicht);
                    aktualisiereSynchronisierungsstatus();
                });
            } catch (RuntimeException exception) {
                runOnUiThread(() -> {
                    if (!ansichtAktiv || generation != ladeGeneration || isDestroyed()) return;
                    ListenLadestatusAnzeige.fehler(this, artikelRepository.holeLetztenErfolgreichenListenabrufMillis());
                });
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
        if (listenuebersicht == null || !listenuebersicht.istLokalVerfuegbar(zaehlerliste.getId())) {
            if(artikelRepository.istSqlMengenabgleichVerfuegbar()) {
                starteArtikeluebersicht(zaehlerliste,false);
                return;
            }
            Toast.makeText(this, R.string.listenquelle_nur_blattkopf, Toast.LENGTH_LONG).show();
            return;
        }
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

        boolean hatOffeneSynchronisierung = artikelRepository.hatOffeneMengensynchronisierung(null);
        syncStatusContainer.setVisibility(View.VISIBLE);
        if (!hatOffeneSynchronisierung) {
            syncStatusTextView.setText(R.string.sync_status_erfolgreich);
            syncStatusTextView.setTextColor(Color.parseColor("#B6F0C2"));
            syncStatusInfoButton.setVisibility(View.VISIBLE);
        } else if (artikelRepository.istSqlMengenabgleichVerfuegbar()) {
            syncStatusTextView.setText(getString(R.string.sync_status_sql_mengen));
            syncStatusTextView.setTextColor(Color.parseColor("#FFE19A"));
            syncStatusInfoButton.setVisibility(View.VISIBLE);
        } else if (artikelRepository.istSqlSchreibenGesperrt()) {
            syncStatusTextView.setText(getString(R.string.sync_status_offen) + "\n"
                    + getString(R.string.sync_uebertragung_deaktiviert));
            syncStatusTextView.setTextColor(Color.parseColor("#FFE19A"));
            syncStatusInfoButton.setVisibility(View.VISIBLE);
        } else if (hatOffeneSynchronisierung && !netzwerkVerfuegbar) {
            syncStatusTextView.setText(R.string.sync_status_kein_netzwerk);
            syncStatusTextView.setTextColor(Color.parseColor("#FFE19A"));
            syncStatusInfoButton.setVisibility(View.VISIBLE);
        } else {
            syncStatusTextView.setText(R.string.sync_status_offen);
            syncStatusTextView.setTextColor(Color.parseColor(hatOffeneSynchronisierung ? "#FFE19A" : "#B6F0C2"));
            syncStatusInfoButton.setVisibility(hatOffeneSynchronisierung ? View.VISIBLE : View.GONE);
        }
        syncStatusInfoButton.setOnClickListener(v -> zeigeSynchronisierungsdetails());
    }

    private String holeSperrdiagnoseText() {
        if (artikelRepository == null) return Synchronisierungsdiagnose.nichtVerfuegbar().getDetails();
        StringBuilder text = new StringBuilder();
        if (ausgewaehlteZaehlerlistenId > 0) {
            text.append(getString(R.string.sync_diagnose_aktuelles_blatt)).append("\n")
                    .append(artikelRepository.holeSynchronisierungsdiagnose(ausgewaehlteZaehlerlistenId).getDetails())
                    .append("\n").append(artikelRepository.beschreibeOffeneEinzelfelder(ausgewaehlteZaehlerlistenId))
                    .append("\n\n");
        }
        text.append(getString(R.string.sync_diagnose_gesamt)).append("\n")
                .append(artikelRepository.holeSynchronisierungsdiagnose(null).getDetails());
        if (artikelRepository.istSqlMengenabgleichVerfuegbar()) {
            text.append("\n\nMengenabgleich in der Artikelansicht verfuegbar. Ungeklaerte Zuordnungen und unklare SQL-Versuche bleiben offen. Nur Mengen und ungeklaerte Mengenauftraege sperren. Notizen, Kopf und Abschluss bleiben lokal und blockieren nicht.");
        } else if (artikelRepository.istSqlSchreibenGesperrt()) {
            text.append("\n\n").append(getString(R.string.sync_uebertragung_deaktiviert))
                    .append("\n").append(getString(R.string.sync_freigabe_voraussetzungen));
        }
        return text.toString();
    }

    private void zeigeSynchronisierungsdetails() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sync_status_dialog_titel)
                .setMessage(holeSperrdiagnoseText())
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
        ladeZaehlerlistenInDieAnsicht();
    }

    private void pruefeDatenbankverbindungPeriodisch() {
        if (netzwerkVerfuegbar) ladeZaehlerlistenInDieAnsicht();
    }

    private void pruefeDatenbankverbindungManuell() {
        ladeZaehlerlistenInDieAnsicht();
    }

    private void versucheZumHauptmenueZurueckzukehren() {
        if (!AppContainer.istApiModusAktiv() || (artikelRepository != null
                && artikelRepository.istHauptmenueRueckkehrErlaubt())) {
            finish();
            return;
        }
        zeigeHauptmenueBlockiertDialog();
    }

    private void zeigeHauptmenueBlockiertDialog() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.sync_hauptmenue_blockiert_titel)
                .setMessage(holeSperrdiagnoseText())
                .setNegativeButton(R.string.sync_hauptmenue_dialog_bleiben, (fenster, which) -> fenster.dismiss());
        if (artikelRepository != null && !artikelRepository.istSqlSchreibenGesperrt()) {
            dialog.setPositiveButton(R.string.sync_hauptmenue_dialog_retry,
                    (fenster, which) -> versucheSynchronisierungVorRueckkehr());
        }
        dialog.show();
    }

    private void versucheSynchronisierungVorRueckkehr() {
        if (artikelRepository != null && artikelRepository.istHauptmenueRueckkehrErlaubt()) {
            finish();
            return;
        }
        if (artikelRepository != null && !artikelRepository.istSqlSchreibenGesperrt()) {
            ladeZaehlerlistenInDieAnsicht();
        }
        zeigeHauptmenueBlockiertDialog();
    }
}
