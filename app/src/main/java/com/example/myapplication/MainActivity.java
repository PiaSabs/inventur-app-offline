package com.example.myapplication;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final long PERIODISCHE_DATENBANKPRUEFUNG_MS = 30_000L;
    private static final int MENU_HILFE = 1;
    private static final int MENU_FEHLERMELDUNG = 2;
    private static final int MENU_DATENBANK = 3;

    private Benutzersitzung benutzersitzung;
    private ArtikelRepository artikelRepository;
    private RecyclerView faelligeRecyclerView;
    private RecyclerView nichtFaelligeRecyclerView;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final PeriodischerDatenbankPruefer periodischerDatenbankPruefer =
            new PeriodischerDatenbankPruefer(
                    PERIODISCHE_DATENBANKPRUEFUNG_MS,
                    this::pruefeDatenbankverbindungPeriodisch
            );
    private NetzwerkStatusBeobachter netzwerkStatusBeobachter;
    private boolean netzwerkVerfuegbar;
    private boolean netzwerkstatusInitialisiert;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        benutzersitzung = new Benutzersitzung(this);
        if (!benutzersitzung.istZaehlerAngemeldet()) {
            starteLogin();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        setzeTestmodusHinweis();
        setzeAngemeldetenZaehler();
        artikelRepository = AppContainer.erstelleArtikelRepository(this);
        netzwerkStatusBeobachter = new NetzwerkStatusBeobachter(this, this::verarbeiteNetzwerkStatusAenderung);
        faelligeRecyclerView = findViewById(R.id.faelligeZaehlerlistenRecyclerView);
        nichtFaelligeRecyclerView = findViewById(R.id.nichtFaelligeZaehlerlistenRecyclerView);
        faelligeRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        nichtFaelligeRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        registriereBottomNavigation();
        registriereKopfMenue();
        ladeZaehlerlistenInDieAnsicht();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (netzwerkStatusBeobachter != null) {
            netzwerkStatusBeobachter.starten();
        }
        periodischerDatenbankPruefer.starten();
        if (benutzersitzung != null && benutzersitzung.istZaehlerAngemeldet() && artikelRepository != null) {
            ladeZaehlerlistenInDieAnsicht();
        }
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

    private void ladeZaehlerlistenInDieAnsicht() {
        executorService.execute(() -> {
            try {
                List<Zaehlerliste> alleZaehlerlisten = gruppiereZaehlerlistenFuerHauptmenue(artikelRepository.ladeZaehlerlisten());
                List<Zaehlerliste> faelligeZaehlerlisten = new ArrayList<>();
                List<Zaehlerliste> nichtFaelligeZaehlerlisten = new ArrayList<>();

                for (Zaehlerliste zaehlerliste : alleZaehlerlisten) {
                    if (zaehlerliste.istFaellig()) {
                        faelligeZaehlerlisten.add(zaehlerliste);
                    } else {
                        nichtFaelligeZaehlerlisten.add(zaehlerliste);
                    }
                }

                runOnUiThread(() -> {
                    faelligeRecyclerView.setAdapter(new ZaehlerlistenAdapter(faelligeZaehlerlisten, this::oeffneArtikeluebersicht));
                    nichtFaelligeRecyclerView.setAdapter(new ZaehlerlistenAdapter(nichtFaelligeZaehlerlisten, this::oeffneArtikeluebersicht));
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

    private void oeffneArtikeluebersicht(Zaehlerliste zaehlerliste) {
        frageNachBlattlistenVersion(zaehlerliste);
    }

    private void frageNachBlattlistenVersion(Zaehlerliste zaehlerliste) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.blattlisten_version_dialog_titel)
                .setMessage(R.string.blattlisten_version_dialog_text)
                .setPositiveButton(R.string.blattlisten_version_original, (dialog, which) ->
                        starteZaehlerlistenActivity(
                                zaehlerliste,
                                ZaehlerlistenActivity.ARTIKELUEBERSICHT_VERSION_1
                        ))
                .setNegativeButton(R.string.blattlisten_version_zweite, (dialog, which) ->
                        starteZaehlerlistenActivity(
                                zaehlerliste,
                                ZaehlerlistenActivity.ARTIKELUEBERSICHT_VERSION_2
                        ))
                .setNeutralButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void starteZaehlerlistenActivity(Zaehlerliste zaehlerliste, int artikeluebersichtVersion) {
        Intent intent = new Intent(MainActivity.this, ZaehlerlistenActivity.class);
        intent.putExtra(ZaehlerlistenActivity.EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_ID, zaehlerliste.getId());
        intent.putExtra(ZaehlerlistenActivity.EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_NUMMER, zaehlerliste.getBlattnummer());
        intent.putExtra(ZaehlerlistenActivity.EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_BEZEICHNUNG, zaehlerliste.getBezeichnung());
        intent.putExtra(ZaehlerlistenActivity.EXTRA_ARTIKELUEBERSICHT_VERSION, artikeluebersichtVersion);
        startActivity(intent);
    }

    private void setzeTestmodusHinweis() {
        TextView testmodusTextView = findViewById(R.id.testmodusTextView);
        testmodusTextView.setVisibility(AppContainer.istMockModusAktiv() ? View.VISIBLE : View.GONE);
    }

    private void aktualisiereSynchronisierungsstatus() {
        View syncStatusContainer = findViewById(R.id.syncStatusContainer);
        TextView syncStatusTextView = findViewById(R.id.syncStatusTextView);
        View syncStatusInfoButton = findViewById(R.id.syncStatusInfoButton);
        if (!AppContainer.istApiModusAktiv()) {
            syncStatusContainer.setVisibility(View.GONE);
            return;
        }

        boolean hatOffeneSynchronisierung = artikelRepository.hatOffeneSynchronisierung();
        syncStatusContainer.setVisibility(View.VISIBLE);
        if (!netzwerkVerfuegbar) {
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
        if (!netzwerkVerfuegbar) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.sync_status_dialog_titel)
                    .setMessage(R.string.sync_status_kein_netzwerk_detail)
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

    private void setzeAngemeldetenZaehler() {
        TextView menuSubtitleTextView = findViewById(R.id.menuSubtitleTextView);
        String zaehlerName = benutzersitzung.holeAngemeldetenZaehler();
        menuSubtitleTextView.setText(getString(R.string.hauptmenue_untertitel_mit_zaehler, zaehlerName));
    }

    private void registriereKopfMenue() {
        View menuIconButton = findViewById(R.id.menuIconButton);
        menuIconButton.setOnClickListener(this::zeigeKopfMenue);
    }

    private void zeigeKopfMenue(View anchor) {
        PopupMenu popupMenu = new PopupMenu(this, anchor);
        Menu menu = popupMenu.getMenu();
        menu.add(Menu.NONE, MENU_HILFE, Menu.NONE, R.string.hauptmenue_popup_hilfe);
        menu.add(Menu.NONE, MENU_DATENBANK, Menu.NONE, R.string.hauptmenue_popup_datenbank);
        menu.add(Menu.NONE, MENU_FEHLERMELDUNG, Menu.NONE, R.string.hauptmenue_popup_fehlermeldung);

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == MENU_HILFE) {
                zeigeHilfeDialog();
                return true;
            }
            if (itemId == MENU_DATENBANK) {
                pruefeDatenbankverbindungManuell();
                return true;
            }
            if (itemId == MENU_FEHLERMELDUNG) {
                zeigeSynchronisierungsdetails();
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void zeigeHilfeDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.hilfe_dialog_titel)
                .setMessage(R.string.hilfe_dialog_kontakte)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void pruefeDatenbankverbindungManuell() {
        Toast.makeText(this, R.string.datenbank_pruefung_startet, Toast.LENGTH_SHORT).show();
        executorService.execute(() -> {
            artikelRepository.pruefeDatenbankverbindungUndSynchronisiereSofort();
            runOnUiThread(() -> {
                ladeZaehlerlistenInDieAnsicht();
                if (artikelRepository.hatOffeneSynchronisierung()) {
                    zeigeSynchronisierungsdetails();
                } else {
                    Toast.makeText(this, R.string.datenbank_pruefung_erfolgreich, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void starteLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        startActivity(intent);
        finish();
    }

    private void registriereBottomNavigation() {
        View bottomNavProfil = findViewById(R.id.bottomNavProfil);
        bottomNavProfil.setOnClickListener(v -> bestaetigeAbmeldung());
    }

    private void bestaetigeAbmeldung() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.abmelden_dialog_titel)
                .setMessage(R.string.abmelden_dialog_text)
                .setPositiveButton(R.string.abmelden_dialog_ja, (dialog, which) -> meldeAbUndStarteLogin())
                .setNegativeButton(R.string.abmelden_dialog_nein, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void meldeAbUndStarteLogin() {
        benutzersitzung.abmelden();
        Intent intent = new Intent(this, LoginActivity.class);
        startActivity(intent);
        finish();
    }

    private List<Zaehlerliste> gruppiereZaehlerlistenFuerHauptmenue(List<Zaehlerliste> zaehlerlisten) {
        Map<String, List<Zaehlerliste>> gruppierteZaehlerlisten = new LinkedHashMap<>();
        for (Zaehlerliste zaehlerliste : zaehlerlisten) {
            String schluessel = zaehlerliste.getBezeichnung() == null ? "" : zaehlerliste.getBezeichnung().trim();
            gruppierteZaehlerlisten.computeIfAbsent(schluessel, key -> new ArrayList<>()).add(zaehlerliste);
        }

        List<Zaehlerliste> gruppen = new ArrayList<>();
        for (List<Zaehlerliste> gruppe : gruppierteZaehlerlisten.values()) {
            if (gruppe.isEmpty()) {
                continue;
            }

            Zaehlerliste ersteListe = gruppe.get(0);
            boolean istFaellig = false;
            boolean istAbgeschlossen = true;

            for (Zaehlerliste listenEintrag : gruppe) {
                if (listenEintrag.istFaellig()) {
                    istFaellig = true;
                }
                if (!listenEintrag.istAbgeschlossen()) {
                    istAbgeschlossen = false;
                }
            }

            gruppen.add(new Zaehlerliste(
                    ersteListe.getId(),
                    ersteListe.getBezeichnung(),
                    ersteListe.getBlattnummer(),
                    ersteListe.getBereich(),
                    ersteListe.getStichtag(),
                    ersteListe.getLetztesZaehldatum(),
                    istFaellig,
                    istAbgeschlossen,
                    ersteListe.getGespeicherterZaehlerName(),
                    ersteListe.getGespeicherterSchreiberName(),
                    ersteListe.getZaehlTyp()
            ));
        }

        return gruppen;
    }
}
