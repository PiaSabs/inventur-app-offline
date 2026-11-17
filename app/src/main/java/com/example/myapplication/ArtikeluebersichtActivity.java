package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Gemeinsames Laden, Speichern und Navigieren fuer beide Artikelansichten.
public class ArtikeluebersichtActivity extends AppCompatActivity {

    public static final String EXTRA_ZAEHLERLISTEN_ID = "zaehlerlisten_id";
    public static final String EXTRA_ZAEHLERLISTEN_BEZEICHNUNG = "zaehlerlisten_bezeichnung";
    public static final String EXTRA_ABGESCHLOSSENE_BEARBEITUNG_ERLAUBT = "abgeschlossene_bearbeitung_erlaubt";

    protected static final int FILTER_ALLE = 1;
    protected static final int FILTER_OFFEN = 2;
    protected static final int FILTER_GEZAEHLT = 3;
    protected static final int FILTER_UNTER_MINDESTBESTAND = 4;
    private static final long AUTO_SAVE_DELAY_MS = 1200L;

    private final MengeneingabeDialog mengeneingabeDialog = new MengeneingabeDialog();
    private ArtikelRepository artikelRepository;
    private List<Artikel> artikelListe = new ArrayList<>();
    protected RecyclerView.Adapter<?> artikelAdapter;
    private RecyclerView artikelRecyclerView;
    private int zaehlerlistenId;
    private Benutzersitzung benutzersitzung;
    private Zaehlerliste aktuelleZaehlerliste;
    private List<Zaehlerliste> verfuegbareZaehlerlisten = new ArrayList<>();
    private String ausgewaehlteZaehlerlistenBezeichnung = "";
    private String letzterGespeicherterStand = "";
    private String zaehlerAnzeigeStand = "";
    private String schreiberAnzeigeStand = "";
    private final Object lokaleSpeicherSperre = new Object();
    private volatile long speicherGeneration;
    private Runnable nachLokalemSpeichern;
    private boolean ignoriertAenderungenBeimInitialisieren = true;
    private int gesamtPositionenFuerBlatt;
    private long ladeGeneration;
    private TextWatcher schreiberTextWatcher;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoSaveRunnable = () -> speichereZaehlmengenLokal(null);
    private boolean autoSaveGeplant;
    private boolean autoSaveLaeuft;
    private boolean bearbeitungAbgeschlosseneListeErlaubt;
    private boolean austrittLaeuft;
    private AlertDialog abschlussDialog;
    private AlertDialog austrittsFortschritt;
    private AlertDialog austrittsRueckmeldung;
    private AlertDialog austrittsDetails;
    private Runnable austrittWiederholen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        benutzersitzung = new Benutzersitzung(this);
        if (!benutzersitzung.istZaehlerAngemeldet()) {
            starteLogin();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(artikelLayout());
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.artikeluebersichtRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottomInset = Math.max(systemBars.bottom, imeInsets.bottom);
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomInset);
            return insets;
        });

        zaehlerlistenId = getIntent().getIntExtra(EXTRA_ZAEHLERLISTEN_ID, 1);
        ausgewaehlteZaehlerlistenBezeichnung = getIntent().getStringExtra(EXTRA_ZAEHLERLISTEN_BEZEICHNUNG);
        bearbeitungAbgeschlosseneListeErlaubt = getIntent().getBooleanExtra(EXTRA_ABGESCHLOSSENE_BEARBEITUNG_ERLAUBT, false);

        // Die RecyclerView ist die eigentliche Listenansicht fuer die Artikeluebersicht.
        artikelRecyclerView = findViewById(R.id.artikelRecyclerView);
        artikelRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Die Activity laedt die Daten bewusst ueber das Repository.
        artikelRepository = AppContainer.erstelleArtikelRepository(this);
        artikelRepository.setzeAutomatischeSynchronisierungPausiert(true);
        registriereFesteListener();
        ladeAnsichtsdaten(artikelRecyclerView);
    }




    private void registriereSuchfeld() {
        EditText artikelSucheEditText = findViewById(R.id.artikelSucheEditText);
        artikelSucheEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (artikelAdapter != null) {
                    setzeSuchbegriff(s == null ? "" : s.toString());
                    aktualisiereLeereAnsicht();
                }
            }
        });
    }

    private void speichereZaehlmengenLokal(Runnable onErfolg) {
        if (ignoriertAenderungenBeimInitialisieren || isDestroyed()) return;
        if (onErfolg != null && (nachLokalemSpeichern == null || austrittLaeuft)) nachLokalemSpeichern = onErfolg;
        if (autoSaveLaeuft) return;
        String zaehlerAnzeige = holeAktuellenZaehlerFilter();
        String schreiberAnzeige = holeAktuellenSchreiberFilter();
        // Unbearbeitete Anzeigevorgaben sind keine synchronisierungspflichtige Kopfänderung.
        String zaehlerName = zaehlerAnzeige.equals(zaehlerAnzeigeStand) ? null : zaehlerAnzeige;
        String schreiberName = schreiberAnzeige.equals(schreiberAnzeigeStand) ? null : schreiberAnzeige;
        final int blattId = zaehlerlistenId;
        List<Artikel> artikelSnapshot = erstelleArtikelSnapshot();
        String fingerprint = erstelleAktuellenStandFingerprint();
        long generation = ++speicherGeneration;
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        autoSaveGeplant = false;
        autoSaveLaeuft = true;
        aktualisiereAutoSaveStatus();
        executorService.execute(() -> {
            ZaehlmengenSpeicherAntwort antwort;
            try {
                synchronized (lokaleSpeicherSperre) {
                    if (generation != speicherGeneration) return;
                    antwort = artikelRepository.speichereZaehlmengenNurLokal(
                            blattId, zaehlerName, schreiberName, artikelSnapshot);
                }
            } catch (RuntimeException exception) {
                antwort = new ZaehlmengenSpeicherAntwort(false, getString(R.string.lokales_speichern_fehler), null);
            }
            ZaehlmengenSpeicherAntwort ergebnis = antwort;
            runOnUiThread(() -> {
                if (isDestroyed() || generation != speicherGeneration || blattId != zaehlerlistenId) return;
                autoSaveLaeuft = false;
                if (!ergebnis.istErfolgreich()) {
                    nachLokalemSpeichern = null;
                    if (austrittLaeuft) zeigeAustrittsFehler(getString(R.string.lokales_speichern_fehler));
                    else Toast.makeText(this, R.string.lokales_speichern_fehler, Toast.LENGTH_LONG).show();
                } else {
                    zaehlerAnzeigeStand = zaehlerAnzeige;
                    schreiberAnzeigeStand = schreiberAnzeige;
                    letzterGespeicherterStand = fingerprint;
                    if (hatUngespeicherteAenderungen()) {
                        speichereZaehlmengenLokal(null);
                        return;
                    }
                    Runnable fortsetzen = nachLokalemSpeichern;
                    nachLokalemSpeichern = null;
                    if (fortsetzen != null) fortsetzen.run();
                }
                aktualisiereSpeicherhinweis();
                aktualisiereAutoSaveStatus();
            });
        });
    }

    @Override
    protected void onPause() {
        // Ein bewusster Austritt laeuft weiter; Pause allein startet keine Uebertragung.
        if (austrittLaeuft) {
            super.onPause();
            return;
        }
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        autoSaveGeplant = false;
        nachLokalemSpeichern = null;
        if (artikelRepository != null && !ignoriertAenderungenBeimInitialisieren
                && !istBearbeitungGesperrt() && (hatUngespeicherteAenderungen() || autoSaveLaeuft)) {
            String zaehlerAnzeige = holeAktuellenZaehlerFilter();
            String schreiberAnzeige = holeAktuellenSchreiberFilter();
            List<Artikel> snapshot = erstelleArtikelSnapshot();
            String fingerprint = erstelleAktuellenStandFingerprint();
            ++speicherGeneration;
            try {
                // Vor dem Lebenszykluswechsel nur lokal sichern; aeltere Auftraege nicht nachschreiben lassen.
                synchronized (lokaleSpeicherSperre) {
                    ZaehlmengenSpeicherAntwort antwort = artikelRepository.speichereZaehlmengenNurLokal(
                            zaehlerlistenId, zaehlerAnzeige.equals(zaehlerAnzeigeStand) ? null : zaehlerAnzeige,
                            schreiberAnzeige.equals(schreiberAnzeigeStand) ? null : schreiberAnzeige, snapshot);
                    if (!antwort.istErfolgreich()) throw new IllegalStateException("Lokale Speicherung nicht bestaetigt");
                }
                zaehlerAnzeigeStand = zaehlerAnzeige;
                schreiberAnzeigeStand = schreiberAnzeige;
                letzterGespeicherterStand = fingerprint;
            } catch (RuntimeException exception) {
                Toast.makeText(this, R.string.lokales_speichern_fehler, Toast.LENGTH_LONG).show();
            }
            autoSaveLaeuft = false;
            aktualisiereAutoSaveStatus();
        }
        super.onPause();
    }


    private void setzeZaehlerlistenInformationen(Zaehlerliste zaehlerliste) {
        if (zaehlerliste == null) {
            return;
        }

        android.widget.TextView titelTextView = findViewById(R.id.screenTitleTextView);
        android.widget.TextView untertitelTextView = findViewById(R.id.screenSubtitleTextView);
        android.widget.TextView stichtagTextView = findViewById(R.id.stichtagTextView);
        android.widget.TextView letztesZaehldatumTextView = findViewById(R.id.letztesZaehldatumTextView);
        android.widget.TextView blattnummerTextView = findViewById(R.id.blattnummerTextView);
        android.widget.TextView bereichTextView = findViewById(R.id.bereichTextView);
        android.widget.TextView originalLetzteZaehlungTextView = findViewById(R.id.originalLetzteZaehlungTextView);

        titelTextView.setText(zaehlerliste.getBezeichnung());
        untertitelTextView.setText(getString(R.string.artikeluebersicht_bereich_prefix, zaehlerliste.getBereich()));
        stichtagTextView.setText(getString(R.string.stichtag_wert, zaehlerliste.getStichtag()));
        letztesZaehldatumTextView.setText(getString(R.string.artikeluebersicht_letzte_zaehlung_prefix, zaehlerliste.getLetztesZaehldatum()));
        if (originalLetzteZaehlungTextView != null) {
            originalLetzteZaehlungTextView.setText(getString(R.string.artikeluebersicht_letzte_zaehlung_prefix, zaehlerliste.getLetztesZaehldatum()));
        }
        blattnummerTextView.setText(getString(R.string.artikeluebersicht_blattnummer_prefix, zaehlerliste.getBlattnummer()));
        bereichTextView.setText(zaehlerliste.getBereich());
    }

    private void aktualisiereFortschritt() {
        int gezaehlt = 0;
        for (Artikel artikel : artikelListe) {
            if (artikel.istGezaehlt()) {
                gezaehlt++;
            }
        }

        TextView fortschrittTextView = findViewById(R.id.fortschrittTextView);
        fortschrittTextView.setText(getString(R.string.artikeluebersicht_fortschritt_prefix, gezaehlt, gesamtPositionenFuerBlatt));
        if (!ignoriertAenderungenBeimInitialisieren) {
            aktualisiereSpeicherhinweis();
        }
    }

    protected void verarbeiteArtikelAenderung() {
        aktualisiereFortschritt();
        if (!ignoriertAenderungenBeimInitialisieren) {
            planeAutomatischesSpeichern();
        }
    }

    protected void aktualisiereLeereAnsicht() {
        TextView leerstandTextView = findViewById(R.id.leerstandTextView);
        RecyclerView artikelRecyclerView = findViewById(R.id.artikelRecyclerView);
        boolean istLeer = artikelAdapter.getItemCount() == 0;
        leerstandTextView.setVisibility(istLeer ? View.VISIBLE : View.GONE);
        artikelRecyclerView.setVisibility(istLeer ? View.GONE : View.VISIBLE);
    }

    private void setzeTestmodusHinweis() {
        TextView testmodusTextView = findViewById(R.id.testmodusTextView);
        if (artikelRepository.istSqlSchreibenGesperrt()) {
            testmodusTextView.setText(R.string.sync_status_sql_gesperrt);
            testmodusTextView.setVisibility(View.VISIBLE);
        } else {
            testmodusTextView.setVisibility(AppContainer.istMockModusAktiv() ? View.VISIBLE : View.GONE);
        }
    }

    private void planeAutomatischesSpeichern() {
        if (ignoriertAenderungenBeimInitialisieren) {
            return;
        }
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        if (!hatUngespeicherteAenderungen()) {
            autoSaveGeplant = false;
            aktualisiereAutoSaveStatus();
            return;
        }
        autoSaveGeplant = true;
        aktualisiereSpeicherhinweis();
        aktualisiereAutoSaveStatus();
        autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY_MS);
    }

    private void aktualisiereAutoSaveStatus() {
        View speichernButton = findViewById(R.id.speichernButton);
        TextView speichernTextView = findViewById(R.id.speichernTextView);
        ImageView speichernIconImageView = findViewById(R.id.speichernIconImageView);

        speichernButton.setClickable(false);
        speichernButton.setFocusable(false);
        speichernButton.setEnabled(false);
        speichernButton.setAlpha(1f);
        speichernIconImageView.setAlpha(1f);

        if (autoSaveLaeuft) {
            speichernTextView.setText(R.string.autosave_status_speichert);
            return;
        }
        if (autoSaveGeplant || hatUngespeicherteAenderungen()) {
            speichernTextView.setText(R.string.autosave_status_wartet);
            return;
        }
        speichernTextView.setText(R.string.autosave_status_gespeichert);
    }

    private void setzeAngemeldetenZaehler() {
        EditText zaehlerEditText = findViewById(R.id.zaehlerEditText);
        String zaehlerName = aktuelleZaehlerliste != null
                && aktuelleZaehlerliste.getGespeicherterZaehlerName() != null
                && !aktuelleZaehlerliste.getGespeicherterZaehlerName().trim().isEmpty()
                ? aktuelleZaehlerliste.getGespeicherterZaehlerName()
                : benutzersitzung.holeAngemeldetenZaehler();
        zaehlerEditText.setText(zaehlerName);
        zaehlerEditText.setEnabled(false);
        zaehlerEditText.setFocusable(false);
        zaehlerEditText.setClickable(false);
    }

    private void setzeStandardSchreiber() {
        EditText schreiberEditText = findViewById(R.id.schreiberEditText);
        String gespeicherterSchreiberName = aktuelleZaehlerliste == null
                ? ""
                : aktuelleZaehlerliste.getGespeicherterSchreiberName();
        schreiberEditText.setText(gespeicherterSchreiberName != null && !gespeicherterSchreiberName.trim().isEmpty()
                ? gespeicherterSchreiberName : "Peter");
        schreiberEditText.setEnabled(false);
        schreiberEditText.setFocusable(false);
        schreiberEditText.setClickable(false);
    }

    private void registriereVertretungsButtons() {
        ImageButton zaehlerVertretungButton = findViewById(R.id.zaehlerVertretungButton);
        ImageButton schreiberVertretungButton = findViewById(R.id.schreiberVertretungButton);

        zaehlerVertretungButton.setEnabled(false);
        schreiberVertretungButton.setEnabled(false);
        zaehlerVertretungButton.setOnClickListener(null);
        schreiberVertretungButton.setOnClickListener(null);
    }

    private void starteLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        schliesseMengeneingabe();
        if (abschlussDialog != null) abschlussDialog.dismiss();
        if (austrittsFortschritt != null) austrittsFortschritt.dismiss();
        if (austrittsRueckmeldung != null) austrittsRueckmeldung.dismiss();
        if (austrittsDetails != null) austrittsDetails.dismiss();
        super.onDestroy();
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        if (artikelRepository != null) {
            artikelRepository.setzeAutomatischeSynchronisierungPausiert(false);
        }
        executorService.shutdown();
    }

    private void registriereSchreiberAenderungen() {
        EditText schreiberEditText = findViewById(R.id.schreiberEditText);
        if (schreiberTextWatcher != null) {
            schreiberEditText.removeTextChangedListener(schreiberTextWatcher);
        }
        schreiberTextWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!ignoriertAenderungenBeimInitialisieren) {
                    aktualisiereSpeicherhinweis();
                    aktualisiereBlattNavigation();
                    planeAutomatischesSpeichern();
                }
            }
        };
        schreiberEditText.addTextChangedListener(schreiberTextWatcher);
    }

    private void pruefeUngespeicherteAenderungenVorDemVerlassen() {
        if (austrittLaeuft || abschlussDialog != null || ignoriertAenderungenBeimInitialisieren) return;
        if (aktuelleZaehlerliste != null && aktuelleZaehlerliste.istAbgeschlossen()) {
            synchronisiereUndVerlasse(false, this::finish);
            return;
        }
        frageNachAbschlussUndVerlasseGegebenenfalls();
    }

    private void frageNachAbschlussUndVerlasseGegebenenfalls() {
        abschlussDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.abschluss_dialog_titel)
                .setMessage(erstelleAbschlussDialogText())
                .setPositiveButton(R.string.abschluss_dialog_ja, (dialog, which) -> synchronisiereUndVerlasse(true, this::finish))
                .setNegativeButton(R.string.abschluss_dialog_nein, (dialog, which) -> synchronisiereUndVerlasse(false, this::finish))
                .setNeutralButton(R.string.abschluss_dialog_abbrechen, (dialog, which) -> dialog.dismiss())
                .create();
        abschlussDialog.setOnDismissListener(dialog -> abschlussDialog = null);
        abschlussDialog.show();
    }

    private String erstelleAbschlussDialogText() {
        int offeneArtikel = zaehleOffeneArtikel();
        boolean hatOffeneSynchronisierung = AppContainer.istApiModusAktiv()
                && artikelRepository != null
                && artikelRepository.hatOffeneMengensynchronisierung(zaehlerlistenId);

        if (offeneArtikel > 0 && hatOffeneSynchronisierung) {
            return getString(R.string.abschluss_offene_und_sync_text, offeneArtikel);
        }
        if (offeneArtikel > 0) {
            return getString(R.string.abschluss_offene_artikel_text, offeneArtikel);
        }
        if (hatOffeneSynchronisierung) {
            return getString(R.string.abschluss_sync_offen_text);
        }
        return getString(R.string.abschluss_dialog_text);
    }

    private int zaehleOffeneArtikel() {
        int offeneArtikel = 0;
        for (Artikel artikel : artikelListe) {
            if (!artikel.istGezaehlt()) {
                offeneArtikel++;
            }
        }
        return offeneArtikel;
    }

    protected boolean istBearbeitungGesperrt() {
        return aktuelleZaehlerliste != null
                && aktuelleZaehlerliste.istAbgeschlossen()
                && !bearbeitungAbgeschlosseneListeErlaubt;
    }

    private boolean hatUngespeicherteAenderungen() {
        return !erstelleAktuellenStandFingerprint().equals(letzterGespeicherterStand);
    }

    private String erstelleAktuellenStandFingerprint() {
        StringBuilder stringBuilder = new StringBuilder();

        EditText zaehlerEditText = findViewById(R.id.zaehlerEditText);
        String zaehlerName = zaehlerEditText.getText() == null
                ? ""
                : zaehlerEditText.getText().toString().trim();
        stringBuilder.append(zaehlerName).append('|');

        EditText schreiberEditText = findViewById(R.id.schreiberEditText);
        String schreiberName = schreiberEditText.getText() == null
                ? ""
                : schreiberEditText.getText().toString().trim();
        stringBuilder.append(schreiberName).append('|');

        for (Artikel artikel : artikelListe) {
            stringBuilder.append(artikel.getArtikelnummer())
                    .append(':')
                    .append(artikel.getZaehlMenge() == null ? "null" : artikel.getZaehlMenge())
                    .append(':')
                    .append(artikel.getAnmerkung() == null ? "" : artikel.getAnmerkung())
                    .append(';');
        }

        return stringBuilder.toString();
    }

    private void aktualisiereSpeicherhinweis() {
        TextView listSectionHintTextView = findViewById(R.id.listSectionHintTextView);
        if (istBearbeitungGesperrt()) {
            listSectionHintTextView.setText(R.string.artikel_bearbeitung_gesperrt_hinweis);
            return;
        }
        int textResId = hatUngespeicherteAenderungen()
                ? R.string.artikel_liste_untertitel_ungespeichert
                : R.string.artikel_liste_untertitel;
        listSectionHintTextView.setText(textResId);
    }

    private List<Artikel> erstelleArtikelSnapshot() {
        return ArtikelSnapshots.erstelle(artikelListe);
    }

    private void zeigeFilterMenue(android.view.View anchor) {
        PopupMenu popupMenu = new PopupMenu(this, anchor);
        Menu menu = popupMenu.getMenu();
        menu.add(Menu.NONE, FILTER_ALLE, Menu.NONE, R.string.filter_alle);
        menu.add(Menu.NONE, FILTER_OFFEN, Menu.NONE, R.string.filter_ungezaehlt);
        menu.add(Menu.NONE, FILTER_GEZAEHLT, Menu.NONE, R.string.filter_gezaehlt);
        menu.add(Menu.NONE, FILTER_UNTER_MINDESTBESTAND, Menu.NONE, R.string.filter_unter_mindestbestand);

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == FILTER_ALLE) {
                setzeFilter(FILTER_ALLE);
                aktualisiereLeereAnsicht();
                Toast.makeText(this, R.string.filter_alle_aktiv, Toast.LENGTH_SHORT).show();
                return true;
            }
            if (itemId == FILTER_OFFEN) {
                setzeFilter(FILTER_OFFEN);
                aktualisiereLeereAnsicht();
                Toast.makeText(this, R.string.filter_ungezaehlt_aktiv, Toast.LENGTH_SHORT).show();
                return true;
            }
            if (itemId == FILTER_GEZAEHLT) {
                setzeFilter(FILTER_GEZAEHLT);
                aktualisiereLeereAnsicht();
                Toast.makeText(this, R.string.filter_gezaehlt_aktiv, Toast.LENGTH_SHORT).show();
                return true;
            }
            if (itemId == FILTER_UNTER_MINDESTBESTAND) {
                setzeFilter(FILTER_UNTER_MINDESTBESTAND);
                aktualisiereLeereAnsicht();
                Toast.makeText(this, R.string.filter_unter_mindestbestand_aktiv, Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void ladeAnsichtsdaten(RecyclerView artikelRecyclerView) {
        ignoriertAenderungenBeimInitialisieren = true;
        final int ladeBlattId = zaehlerlistenId;
        final long generation = ++ladeGeneration;

        executorService.execute(() -> {
            List<Artikel> geladeneArtikel = new ArrayList<>();
            Zaehlerliste geladeneZaehlerliste = null;
            List<Zaehlerliste> geladeneZaehlerlisten = new ArrayList<>();
            String fehlermeldung = null;

            try {
                artikelRepository.ladeSqlBlattFallsFehlt(ladeBlattId);
                geladeneZaehlerlisten = artikelRepository.ladeZaehlerlisten();
                artikelRepository.angleicheBeispielWochenlistenZuordnungFuerTest(
                        benutzersitzung.holeAngemeldetenZaehler(),
                        "Peter"
                );
                geladeneZaehlerlisten = artikelRepository.ladeZaehlerlisten();
                geladeneArtikel = artikelRepository.ladeArtikel(ladeBlattId);
                geladeneZaehlerliste = artikelRepository.ladeZaehlerlisteFuerId(ladeBlattId);
            } catch (RemoteDatenException | RuntimeException exception) {
                fehlermeldung = "Artikeldaten konnten nicht geladen werden.";
            }

            List<Artikel> finaleGeladeneArtikel = geladeneArtikel;
            Zaehlerliste finaleGeladeneZaehlerliste = geladeneZaehlerliste;
            List<Zaehlerliste> finaleGeladeneZaehlerlisten = geladeneZaehlerlisten;
            String finaleFehlermeldung = fehlermeldung;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing() || ladeBlattId != zaehlerlistenId
                        || generation != ladeGeneration) return;
                ignoriertAenderungenBeimInitialisieren = true;
                artikelListe = finaleGeladeneArtikel;
                gesamtPositionenFuerBlatt = finaleGeladeneArtikel.size();
                aktuelleZaehlerliste = finaleGeladeneZaehlerliste;
                verfuegbareZaehlerlisten = finaleGeladeneZaehlerlisten;
                initialisiereAnsicht(artikelRecyclerView);
                if (finaleFehlermeldung != null) {
                    Toast.makeText(this, finaleFehlermeldung, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    protected int artikelLayout() {
        return R.layout.activity_artikeluebersicht;
    }

    protected RecyclerView.Adapter<?> erstelleArtikelAdapter(List<Artikel> artikel) {
        boolean bearbeitungErlaubt = !istBearbeitungGesperrt();
        return new ArtikelAdapter(artikel, bearbeitungErlaubt, new ArtikelAdapter.OnArtikelInteraktionListener() {
            @Override
            public void onArtikelStatusChanged() {
                verarbeiteArtikelAenderung();
            }

            @Override
            public void onMengeBearbeiten(Artikel artikel) {
                mengeneingabeDialog.zeige(ArtikeluebersichtActivity.this, artikel, () -> {
                    artikelAdapter.notifyDataSetChanged();
                    verarbeiteArtikelAenderung();
                });
            }
        });
    }

    protected void setzeSuchbegriff(String suchbegriff) {
        ((ArtikelAdapter) artikelAdapter).setSuchbegriff(suchbegriff);
    }

    protected void setzeFilter(int modus) {
        ArtikelAdapter adapter = (ArtikelAdapter) artikelAdapter;
        switch (modus) {
            case FILTER_OFFEN: adapter.zeigeNurUngezaehlteArtikel(); break;
            case FILTER_GEZAEHLT: adapter.zeigeNurGezaehlteArtikel(); break;
            case FILTER_UNTER_MINDESTBESTAND: adapter.zeigeNurUnterMindestbestandArtikel(); break;
            default: adapter.zeigeAlleArtikel();
        }
    }

    protected void schliesseMengeneingabe() {
        mengeneingabeDialog.schliesse();
    }

    private void initialisiereAnsicht(RecyclerView artikelRecyclerView) {
        if (aktuelleZaehlerliste != null && normalisiereText(ausgewaehlteZaehlerlistenBezeichnung).isEmpty()) {
            ausgewaehlteZaehlerlistenBezeichnung = aktuelleZaehlerliste.getBezeichnung();
        }
        setzeZaehlerlistenInformationen(aktuelleZaehlerliste);

        artikelAdapter = erstelleArtikelAdapter(artikelListe);
        artikelRecyclerView.setAdapter(artikelAdapter);
        EditText artikelSucheEditText = findViewById(R.id.artikelSucheEditText);
        if (artikelSucheEditText.getText() != null && artikelSucheEditText.getText().length() > 0) {
            setzeSuchbegriff(artikelSucheEditText.getText().toString());
        }
        aktualisiereFortschritt();
        aktualisiereLeereAnsicht();
        setzeTestmodusHinweis();
        setzeAngemeldetenZaehler();
        setzeStandardSchreiber();
        zaehlerAnzeigeStand = holeAktuellenZaehlerFilter();
        schreiberAnzeigeStand = holeAktuellenSchreiberFilter();
        aktualisiereBlattNavigation();
        letzterGespeicherterStand = erstelleAktuellenStandFingerprint();
        autoSaveGeplant = false;
        autoSaveLaeuft = false;
        ignoriertAenderungenBeimInitialisieren = false;
        aktualisiereAutoSaveStatus();
    }

    private void synchronisiereUndVerlasse(boolean abschliessen, Runnable navigation) {
        if (austrittLaeuft || ignoriertAenderungenBeimInitialisieren) return;
        austrittLaeuft = true;
        int blatt = zaehlerlistenId;
        austrittWiederholen = () -> synchronisiereUndVerlasse(abschliessen, navigation);
        austrittsFortschritt = new AlertDialog.Builder(this)
                .setTitle(R.string.austritt_sync_titel)
                .setMessage(R.string.austritt_sync_speichern)
                .setView(new android.widget.ProgressBar(this))
                .setCancelable(false).create();
        austrittsFortschritt.show();
        Runnable uebertragen = () -> {
            austrittsFortschritt.setMessage(getString(R.string.austritt_sync_uebertragen));
            executorService.execute(() -> {
                ZaehlmengenSpeicherAntwort antwort = artikelRepository.synchronisiereBeimVerlassen(blatt, abschliessen);
                Zaehlerliste gespeichert = null;
                if (abschliessen) {
                    try {
                        gespeichert = artikelRepository.ladeZaehlerlisteFuerId(blatt);
                    } catch (RuntimeException exception) {
                        antwort = new ZaehlmengenSpeicherAntwort(false,
                                "Lokaler Abschlussstand konnte nicht gelesen werden. Änderungen bleiben erhalten.", null);
                    }
                }
                Zaehlerliste kopf = gespeichert;
                ZaehlmengenSpeicherAntwort ergebnis = antwort;
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || zaehlerlistenId != blatt) return;
                    if (kopf != null) {
                        aktuelleZaehlerliste = kopf;
                        // Ein nur lokal gespeicherter Abschluss sperrt die Mengenbearbeitung ebenfalls.
                        if (istBearbeitungGesperrt()) {
                            artikelAdapter = erstelleArtikelAdapter(artikelListe);
                            artikelRecyclerView.setAdapter(artikelAdapter);
                            aktualisiereSpeicherhinweis();
                        }
                    }
                    if (hatUngespeicherteAenderungen()) {
                        zeigeAustrittsRueckmeldung(ergebnis.mitOffenenEingaben(),navigation);
                    } else zeigeAustrittsRueckmeldung(ergebnis,navigation);
                });
            });
        };
        if (istBearbeitungGesperrt()) uebertragen.run();
        else speichereZaehlmengenLokal(uebertragen);
    }

    private void zeigeAustrittsFehler(String meldung) {
        ZaehlmengenSpeicherAntwort antwort=new ZaehlmengenSpeicherAntwort(false,meldung,null);
        antwort.mitMengenbericht(0,0,0,0,java.util.Collections.emptyMap(),hatUngespeicherteAenderungen());
        zeigeAustrittsRueckmeldung(antwort,null);
    }

    private void zeigeAustrittsRueckmeldung(ZaehlmengenSpeicherAntwort antwort, Runnable navigation) {
        if (austrittsFortschritt != null) austrittsFortschritt.dismiss();
        boolean erfolg=antwort.istErfolgreich();
        if(!erfolg) austrittLaeuft=false;
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(antwort.getTitel())
                .setMessage(antwort.getBenutzerMeldung()).setCancelable(false);
        if(erfolg) {
            dialog.setPositiveButton(android.R.string.ok,(fenster,which) -> {
                austrittLaeuft=false;
                if(hatUngespeicherteAenderungen() || artikelRepository.hatOffeneMengensynchronisierung(zaehlerlistenId)) {
                    zeigeAustrittsRueckmeldung(antwort.mitOffenenEingaben(),navigation);
                    return;
                }
                setResult(RESULT_OK);
                navigation.run();
            });
        } else {
            dialog.setNegativeButton(R.string.austritt_sync_bleiben,null)
                    .setNeutralButton("!",(fenster,which) -> {
                        austrittsDetails=new AlertDialog.Builder(this).setTitle("Informationen f\u00fcr die IT")
                                .setMessage(antwort.getMeldung()).setPositiveButton(android.R.string.ok,
                                        (details,w) -> zeigeAustrittsRueckmeldung(antwort,navigation)).setCancelable(false).show();
                    });
            if(antwort.istWiederholbar()) dialog.setPositiveButton(R.string.austritt_sync_wiederholen,
                    (fenster,which) -> austrittWiederholen.run());
        }
        austrittsRueckmeldung=dialog.create();
        austrittsRueckmeldung.show();
        if(!erfolg) {
            android.widget.Button info=austrittsRueckmeldung.getButton(AlertDialog.BUTTON_NEUTRAL);
            info.setTextColor(android.graphics.Color.rgb(190,35,30));
            info.setTextSize(24);
            info.setContentDescription("Technische Fehlerinformationen f\u00fcr die IT anzeigen");
        }
    }

    private void registriereFesteListener() {
        ImageButton filterButton = findViewById(R.id.filterButton);
        filterButton.setOnClickListener(this::zeigeFilterMenue);

        registriereSuchfeld();

        ImageButton zurueckButton = findViewById(R.id.zurueckButton);
        zurueckButton.setOnClickListener(v -> pruefeUngespeicherteAenderungenVorDemVerlassen());

        ImageButton vorherigesBlattButton = findViewById(R.id.vorherigesBlattButton);
        vorherigesBlattButton.setOnClickListener(v -> pruefeUngespeicherteAenderungenVorDemBlattwechsel(-1));

        ImageButton naechstesBlattButton = findViewById(R.id.naechstesBlattButton);
        naechstesBlattButton.setOnClickListener(v -> pruefeUngespeicherteAenderungenVorDemBlattwechsel(1));

        registriereVertretungsButtons();
        registriereSchreiberAenderungen();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                pruefeUngespeicherteAenderungenVorDemVerlassen();
            }
        });
    }

    private void pruefeUngespeicherteAenderungenVorDemBlattwechsel(int richtung) {
        if (austrittLaeuft || abschlussDialog != null || ignoriertAenderungenBeimInitialisieren) return;
        if (!kannZuBlattWechseln(richtung)) {
            Toast.makeText(
                    this,
                    richtung < 0 ? R.string.kein_vorheriges_blatt : R.string.kein_naechstes_blatt,
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        synchronisiereUndVerlasse(false, () -> wechsleZuBenachbartemBlatt(richtung));
    }

    private void wechsleZuBenachbartemBlatt(int richtung) {
        List<Zaehlerliste> wochenBlattlisten = ermittleWochenBlattlistenFuerNavigation();
        int aktuellerIndex = findeAktuellenBlattIndex(wochenBlattlisten);
        int zielIndex = aktuellerIndex + richtung;
        if (aktuellerIndex < 0 || zielIndex < 0 || zielIndex >= wochenBlattlisten.size()) {
            Toast.makeText(this, R.string.blattwechsel_nicht_moeglich, Toast.LENGTH_SHORT).show();
            return;
        }

        zaehlerlistenId = wochenBlattlisten.get(zielIndex).getId();
        ladeAnsichtsdaten((RecyclerView) findViewById(R.id.artikelRecyclerView));
    }

    private void aktualisiereBlattNavigation() {
        ImageButton vorherigesBlattButton = findViewById(R.id.vorherigesBlattButton);
        ImageButton naechstesBlattButton = findViewById(R.id.naechstesBlattButton);

        boolean kannZurueck = kannZuBlattWechseln(-1);
        boolean kannWeiter = kannZuBlattWechseln(1);

        vorherigesBlattButton.setEnabled(kannZurueck);
        vorherigesBlattButton.setAlpha(kannZurueck ? 1f : 0.35f);

        naechstesBlattButton.setEnabled(kannWeiter);
        naechstesBlattButton.setAlpha(kannWeiter ? 1f : 0.35f);
    }

    private boolean kannZuBlattWechseln(int richtung) {
        List<Zaehlerliste> wochenBlattlisten = ermittleWochenBlattlistenFuerNavigation();
        int aktuellerIndex = findeAktuellenBlattIndex(wochenBlattlisten);
        int zielIndex = aktuellerIndex + richtung;
        return aktuellerIndex >= 0 && zielIndex >= 0 && zielIndex < wochenBlattlisten.size();
    }

    private int findeAktuellenBlattIndex() {
        return findeAktuellenBlattIndex(verfuegbareZaehlerlisten);
    }

    private int findeAktuellenBlattIndex(List<Zaehlerliste> zaehlerlisten) {
        return BlattAuswahl.findeBlattIndex(zaehlerlisten, zaehlerlistenId);
    }

    private List<Zaehlerliste> ermittleWochenBlattlistenFuerNavigation() {
        return BlattAuswahl.wochenBlaetter(verfuegbareZaehlerlisten,
                holeAktuellenZaehlerFilter(), holeAktuellenSchreiberFilter(),
                ausgewaehlteZaehlerlistenBezeichnung);
    }

    private String holeAktuellenZaehlerFilter() {
        EditText zaehlerEditText = findViewById(R.id.zaehlerEditText);
        return zaehlerEditText.getText() == null ? "" : zaehlerEditText.getText().toString().trim();
    }

    private String holeAktuellenSchreiberFilter() {
        EditText schreiberEditText = findViewById(R.id.schreiberEditText);
        return schreiberEditText.getText() == null ? "" : schreiberEditText.getText().toString().trim();
    }

    private String normalisiereText(String wert) {
        return BlattAuswahl.normalisiereText(wert);
    }
}




