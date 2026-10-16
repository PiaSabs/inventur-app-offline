package com.example.myapplication;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.View;
import android.view.WindowManager;
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

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Diese Activity kapselt die komplette Artikelansicht.
// Dadurch bleibt das Hauptmenue schlank und die Artikellogik kann separat erweitert werden.
public class ArtikeluebersichtV2Activity extends AppCompatActivity {

    public static final String EXTRA_ZAEHLERLISTEN_ID = "zaehlerlisten_id";
    public static final String EXTRA_ZAEHLERLISTEN_BEZEICHNUNG = "zaehlerlisten_bezeichnung";
    public static final String EXTRA_ABGESCHLOSSENE_BEARBEITUNG_ERLAUBT = "abgeschlossene_bearbeitung_erlaubt";

    private static final int FILTER_ALLE = 1;
    private static final int FILTER_OFFEN = 2;
    private static final int FILTER_GEZAEHLT = 3;
    private static final int FILTER_UNTER_MINDESTBESTAND = 4;
    private static final long AUTO_SAVE_DELAY_MS = 1200L;
    private static final SimpleDateFormat STICHTAG_FORMAT = new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY);

    private BottomSheetDialog artikelDetailDialog;
    private ArtikelRepository artikelRepository;
    private List<Artikel> artikelListe = new ArrayList<>();
    private ArtikelAdapterV2 artikelAdapter;
    private RecyclerView artikelRecyclerView;
    private int zaehlerlistenId;
    private Benutzersitzung benutzersitzung;
    private Zaehlerliste aktuelleZaehlerliste;
    private List<Zaehlerliste> verfuegbareZaehlerlisten = new ArrayList<>();
    private String ausgewaehlteZaehlerlistenBezeichnung = "";
    private String letzterGespeicherterStand = "";
    private boolean ignoriertAenderungenBeimInitialisieren = true;
    private int gesamtPositionenFuerBlatt;
    private TextWatcher schreiberTextWatcher;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoSaveRunnable = () -> speichereZaehlmengenLokal(true, null);
    private boolean autoSaveGeplant;
    private boolean autoSaveLaeuft;
    private boolean bearbeitungAbgeschlosseneListeErlaubt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        benutzersitzung = new Benutzersitzung(this);
        if (!benutzersitzung.istZaehlerAngemeldet()) {
            starteLogin();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_artikeluebersicht_v2);
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
        // PR: Hier bleibt die Anzeige absichtlich von der spaeteren API-/SQL-Anbindung entkoppelt.
        artikelRepository = AppContainer.erstelleArtikelRepository(this);
        artikelRepository.setzeAutomatischeSynchronisierungPausiert(true);
        registriereFesteListener();
        ladeAnsichtsdaten(artikelRecyclerView);
    }



    private void aktualisiereAnsichtManuell() {
        Toast.makeText(this, R.string.aktualisieren_startet, Toast.LENGTH_SHORT).show();
        if (hatUngespeicherteAenderungen() || autoSaveLaeuft || autoSaveGeplant) {
            speichereZaehlmengenLokal(false, () -> ladeAnsichtsdaten((RecyclerView) findViewById(R.id.artikelRecyclerView)));
            return;
        }
        ladeAnsichtsdaten((RecyclerView) findViewById(R.id.artikelRecyclerView));
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
                    artikelAdapter.setSuchbegriff(s == null ? "" : s.toString());
                    aktualisiereLeereAnsicht();
                }
            }
        });
    }

    private void speichereZaehlmengenLokal() {
        speichereZaehlmengenLokal(false, null);
    }

    private void speichereZaehlmengenLokal(Runnable onErfolg) {
        speichereZaehlmengenLokal(false, onErfolg);
    }

    private void speichereZaehlmengenLokal(boolean istAutomatisch, Runnable onErfolg) {
        fuehreSpeicherVorgangAus(istAutomatisch, false, onErfolg);
    }

    private void synchronisiereZaehlmengenBeimVerlassen(Runnable onErfolg) {
        fuehreSpeicherVorgangAus(false, true, onErfolg);
    }

    private void fuehreSpeicherVorgangAus(boolean istAutomatisch, boolean mitSynchronisierung, Runnable onErfolg) {
        EditText zaehlerEditText = findViewById(R.id.zaehlerEditText);
        EditText schreiberEditText = findViewById(R.id.schreiberEditText);

        if (zaehlerEditText.getText() != null && zaehlerEditText.getText().toString().trim().isEmpty()) {
            if (!istAutomatisch) {
                Toast.makeText(this, R.string.zaehler_fehlt, Toast.LENGTH_SHORT).show();
            }
            aktualisiereAutoSaveStatus();
            return;
        }

        if (schreiberEditText.getText() != null && schreiberEditText.getText().toString().trim().isEmpty()) {
            if (!istAutomatisch) {
                Toast.makeText(this, R.string.schreiber_fehlt, Toast.LENGTH_SHORT).show();
            }
            aktualisiereAutoSaveStatus();
            return;
        }

        String zaehlerName = zaehlerEditText.getText() == null ? "" : zaehlerEditText.getText().toString().trim();
        String schreiberName = schreiberEditText.getText() == null ? "" : schreiberEditText.getText().toString().trim();
        List<Artikel> artikelSnapshot = erstelleArtikelSnapshot();
        String gespeicherterFingerprint = erstelleAktuellenStandFingerprint();

        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        autoSaveGeplant = false;
        autoSaveLaeuft = true;
        aktualisiereAutoSaveStatus();

        executorService.execute(() -> {
            ZaehlmengenSpeicherAntwort antwort = mitSynchronisierung
                    ? artikelRepository.speichereZaehlmengenMitSynchronisierung(
                            zaehlerlistenId,
                            zaehlerName,
                            schreiberName,
                            artikelSnapshot
                    )
                    : artikelRepository.speichereZaehlmengenNurLokal(
                            zaehlerlistenId,
                            zaehlerName,
                            schreiberName,
                            artikelSnapshot
                    );
            runOnUiThread(() -> {
                autoSaveLaeuft = false;
                if (antwort.istErfolgreich()) {
                    boolean synchronisierungWeiterhinOffen = mitSynchronisierung
                            && artikelRepository != null
                            && artikelRepository.hatOffeneSynchronisierungFuerZaehlerliste(zaehlerlistenId);
                    letzterGespeicherterStand = gespeicherterFingerprint;
                    if (!istAutomatisch && synchronisierungWeiterhinOffen) {
                        zeigeSynchronisierungsfehlerDialog(artikelRepository.holeLetzteSynchronisierungsFehlermeldung());
                        aktualisiereSpeicherhinweis();
                        aktualisiereAutoSaveStatus();
                        return;
                    }
                    if (onErfolg != null && hatUngespeicherteAenderungen()) {
                        fuehreSpeicherVorgangAus(istAutomatisch, mitSynchronisierung, onErfolg);
                        return;
                    }
                    if (onErfolg != null && !hatUngespeicherteAenderungen()) {
                        onErfolg.run();
                    }
                }
                aktualisiereSpeicherhinweis();
                if (hatUngespeicherteAenderungen()) {
                    planeAutomatischesSpeichern();
                }
                aktualisiereAutoSaveStatus();
                if (!istAutomatisch && !antwort.istErfolgreich()) {
                    Toast.makeText(this, antwort.getMeldung(), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void zeigeSynchronisierungsfehlerDialog(String meldung) {
        new AlertDialog.Builder(this)
                .setTitle("Synchronisierung fehlgeschlagen")
                .setMessage(meldung == null || meldung.trim().isEmpty()
                        ? "Die SQL-Synchronisierung konnte nicht abgeschlossen werden."
                        : meldung)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                .show();
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

        titelTextView.setText(zaehlerliste.getBezeichnung());
        untertitelTextView.setText(getString(R.string.artikeluebersicht_bereich_prefix, zaehlerliste.getBereich()));
        stichtagTextView.setText(getString(R.string.stichtag_wert, zaehlerliste.getStichtag()));
        letztesZaehldatumTextView.setText(getString(R.string.artikeluebersicht_letzte_zaehlung_prefix, zaehlerliste.getLetztesZaehldatum()));
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

    private void verarbeiteArtikelAenderung() {
        aktualisiereFortschritt();
        if (!ignoriertAenderungenBeimInitialisieren) {
            planeAutomatischesSpeichern();
        }
    }

    private void oeffneArtikelDetailBottomSheet(Artikel artikel) {
        if (artikelDetailDialog != null) {
            EingabeDiagnose.ereignis("v2: mehrfaches Oeffnen verhindert");
            return;
        }
        if (istBearbeitungGesperrt()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.artikel_bearbeitung_gesperrt_titel)
                    .setMessage(R.string.artikel_bearbeitung_gesperrt_text)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                    .show();
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        artikelDetailDialog = dialog;
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> EingabeDiagnose.ereignis("v2: abgebrochen"));
        dialog.setOnDismissListener(d -> {
            EingabeDiagnose.ereignis("v2: geschlossen");
            artikelDetailDialog = null;
        });
        dialog.setOnShowListener(d -> {
            dialog.getBehavior().setSkipCollapsed(true);
            dialog.getBehavior().setHideable(false);
            dialog.getBehavior().setDraggable(false);
            dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            EingabeDiagnose.ereignis("v2: geoeffnet");
        });
        View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_artikel_v2, null);
        dialog.setContentView(sheetView);
        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            bottomSheet.setBackgroundColor(Color.TRANSPARENT);
        }

        TextView titleTextView = sheetView.findViewById(R.id.detailTitleTextView);
        TextView subtitleTextView = sheetView.findViewById(R.id.detailSubtitleTextView);
        TextView lagerortTextView = sheetView.findViewById(R.id.detailLagerortTextView);
        TextView istMengeTextView = sheetView.findViewById(R.id.detailIstMengeTextView);
        TextView mindestbestandTextView = sheetView.findViewById(R.id.detailMindestbestandTextView);
        TextView statusTextView = sheetView.findViewById(R.id.detailStatusTextView);
        TextView letzteZaehlungTextView = sheetView.findViewById(R.id.detailLetzteZaehlungTextView);
        EditText zaehlMengeEditText = sheetView.findViewById(R.id.detailZaehlMengeEditText);
        EditText anmerkungEditText = sheetView.findViewById(R.id.detailAnmerkungEditText);
        View minusButton = sheetView.findViewById(R.id.detailMinusButton);
        View plusButton = sheetView.findViewById(R.id.detailPlusButton);
        View speichernButton = sheetView.findViewById(R.id.detailSpeichernButton);
        sheetView.findViewById(R.id.detailAbbrechenButton).setOnClickListener(v -> dialog.cancel());

        titleTextView.setText(artikel.getBezeichnung());
        subtitleTextView.setText(getString(
                R.string.artikel_detail_untertitel,
                artikel.getPositionsNummer(),
                artikel.getArtikelnummer()
        ));
        lagerortTextView.setText(artikel.getLagerort());
        istMengeTextView.setText(String.valueOf(artikel.getIstMenge()));
        mindestbestandTextView.setText(String.valueOf(artikel.getMindestbestand()));
        statusTextView.setText(artikel.istGezaehlt() ? R.string.zaehlstatus_gezaehlt : R.string.zaehlstatus_offen);
        letzteZaehlungTextView.setText(formatiereMenge(artikel.getLetzteZaehlmenge()));
        zaehlMengeEditText.setText(artikel.getZaehlMenge() == null ? "" : String.valueOf(artikel.getZaehlMenge()));
        anmerkungEditText.setText(artikel.getAnmerkung());
        zaehlMengeEditText.setOnFocusChangeListener((v, fokus) ->
                EingabeDiagnose.ereignis(fokus ? "v2: Mengenfeld fokussiert" : "v2: Mengenfeld Fokus verloren"));
        EingabeDiagnose.beobachteTastatur(sheetView, "v2");

        minusButton.setOnClickListener(v -> {
            int aktuellerWert = parseZaehlmenge(zaehlMengeEditText);
            int neuerWert = Math.max(0, aktuellerWert - 1);
            zaehlMengeEditText.setText(String.valueOf(neuerWert));
            zaehlMengeEditText.setSelection(zaehlMengeEditText.getText().length());
        });

        plusButton.setOnClickListener(v -> {
            int aktuellerWert = parseZaehlmenge(zaehlMengeEditText);
            int neuerWert = aktuellerWert == Integer.MAX_VALUE ? aktuellerWert : aktuellerWert + 1;
            zaehlMengeEditText.setText(String.valueOf(neuerWert));
            zaehlMengeEditText.setSelection(zaehlMengeEditText.getText().length());
        });

        speichernButton.setOnClickListener(v -> {
            String mengeText = zaehlMengeEditText.getText() == null ? "" : zaehlMengeEditText.getText().toString().trim();
            Integer zaehlMenge = leseGueltigeZaehlmenge(zaehlMengeEditText, mengeText);
            if (zaehlMenge == null && !mengeText.isEmpty()) {
                return;
            }
            EingabeDiagnose.ereignis("v2: bestaetigt");
            artikel.setZaehlMenge(zaehlMenge);
            artikel.setAnmerkung(anmerkungEditText.getText() == null ? "" : anmerkungEditText.getText().toString().trim());
            artikelAdapter.notifyDataSetChanged();
            verarbeiteArtikelAenderung();
            aktualisiereLeereAnsicht();
            dialog.dismiss();
        });

        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }

    private Integer leseGueltigeZaehlmenge(EditText editText, String wert) {
        if (wert == null || wert.trim().isEmpty()) {
            return null;
        }
        try {
            int zaehlMenge = Integer.parseInt(wert.trim());
            if (zaehlMenge < 0) {
                editText.setError(getString(R.string.zaehlmenge_ungueltig));
                return null;
            }
            return zaehlMenge;
        } catch (NumberFormatException exception) {
            editText.setError(getString(R.string.zaehlmenge_ungueltig));
            return null;
        }
    }

    private String formatiereMenge(Integer menge) {
        return menge == null ? getString(R.string.kein_mengenwert) : String.valueOf(menge);
    }

    private int parseZaehlmenge(EditText editText) {
        String wert = editText.getText() == null ? "" : editText.getText().toString().trim();
        Integer zaehlMenge = leseGueltigeZaehlmenge(editText, wert);
        return zaehlMenge == null ? 0 : zaehlMenge;
    }

    private void aktualisiereLeereAnsicht() {
        TextView leerstandTextView = findViewById(R.id.leerstandTextView);
        RecyclerView artikelRecyclerView = findViewById(R.id.artikelRecyclerView);
        boolean istLeer = artikelAdapter.getItemCount() == 0;
        leerstandTextView.setVisibility(istLeer ? View.VISIBLE : View.GONE);
        artikelRecyclerView.setVisibility(istLeer ? View.GONE : View.VISIBLE);
    }

    private void setzeTestmodusHinweis() {
        TextView testmodusTextView = findViewById(R.id.testmodusTextView);
        testmodusTextView.setVisibility(AppContainer.istMockModusAktiv() ? View.VISIBLE : View.GONE);
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
        if (gespeicherterSchreiberName != null && !gespeicherterSchreiberName.trim().isEmpty()) {
            schreiberEditText.setText(gespeicherterSchreiberName);
        } else if (schreiberEditText.getText() == null || schreiberEditText.getText().toString().trim().isEmpty()) {
            schreiberEditText.setText("Peter");
        }
    }

    private void registriereVertretungsButtons() {
        ImageButton zaehlerVertretungButton = findViewById(R.id.zaehlerVertretungButton);
        ImageButton schreiberVertretungButton = findViewById(R.id.schreiberVertretungButton);

        zaehlerVertretungButton.setOnClickListener(v -> oeffneVertretungsDialog(
                R.string.zaehler_vertretung_titel,
                R.string.zaehler_vertretung_hinweis,
                R.string.zaehler_vertretung_hint,
                R.id.zaehlerEditText
        ));

        schreiberVertretungButton.setOnClickListener(v -> oeffneVertretungsDialog(
                R.string.schreiber_vertretung_titel,
                R.string.schreiber_vertretung_hinweis,
                R.string.schreiber_vertretung_hint,
                R.id.schreiberEditText
        ));
    }

    private void oeffneVertretungsDialog(int titelResId, int nachrichtResId, int hintResId, int editTextId) {
        EditText zielFeld = findViewById(editTextId);
        EditText dialogEditText = new EditText(this);
        dialogEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        dialogEditText.setHint(hintResId);
        dialogEditText.setSingleLine(true);
        CharSequence aktuellerWert = zielFeld.getText();
        if (aktuellerWert != null) {
            dialogEditText.setText(aktuellerWert.toString().trim());
            dialogEditText.setSelection(dialogEditText.getText().length());
        }

        new AlertDialog.Builder(this)
                .setTitle(titelResId)
                .setMessage(nachrichtResId)
                .setView(dialogEditText)
                .setPositiveButton(R.string.vertretung_dialog_uebernehmen, (dialog, which) -> {
                    String vertretung = dialogEditText.getText() == null
                            ? ""
                            : dialogEditText.getText().toString().trim();
                    if (!vertretung.isEmpty()) {
                        zielFeld.setText(vertretung);
                    }
                })
                .setNegativeButton(R.string.vertretung_dialog_abbrechen, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void starteLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (artikelDetailDialog != null) {
            EingabeDiagnose.ereignis("v2: Activity beendet");
            artikelDetailDialog.dismiss();
        }
        super.onDestroy();
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        if (artikelRepository != null) {
            artikelRepository.setzeAutomatischeSynchronisierungPausiert(false);
        }
        executorService.shutdownNow();
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
        if (!hatUngespeicherteAenderungen()
                && !autoSaveLaeuft
                && !autoSaveGeplant) {
            verlasseArtikeluebersichtMitAutomatischemAbschluss();
            return;
        }
        speichereZaehlmengenLokal(false, this::verlasseArtikeluebersichtMitAutomatischemAbschluss);
    }

    private void verlasseArtikeluebersichtMitAutomatischemAbschluss() {
        if (istBearbeitungGesperrt()) {
            finish();
            return;
        }

        if (sindAllePositionenGueltigGezahlt()) {
            markiereZaehlerlisteAutomatischAlsAbgeschlossenUndVerlasse();
            return;
        }
        frageNachAbschlussUndVerlasseGegebenenfalls();
    }

    private boolean sindAllePositionenGueltigGezahlt() {
        if (artikelListe == null || artikelListe.isEmpty()) {
            return false;
        }

        for (Artikel artikel : artikelListe) {
            if (!hatGueltigeZaehlmenge(artikel)) {
                return false;
            }
        }
        return true;
    }

    private boolean hatGueltigeZaehlmenge(Artikel artikel) {
        return artikel != null
                && artikel.getZaehlMenge() != null
                && artikel.getZaehlMenge() >= 0;
    }

    private void frageNachAbschlussUndVerlasseGegebenenfalls() {
        if (istBearbeitungGesperrt()) {
            finish();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.abschluss_dialog_titel)
                .setMessage(erstelleAbschlussDialogText())
                .setPositiveButton(R.string.abschluss_dialog_ja, (dialog, which) -> markiereZaehlerlisteAlsAbgeschlossenUndVerlasse())
                .setNegativeButton(R.string.abschluss_dialog_nein, (dialog, which) -> finish())
                .setNeutralButton(R.string.abschluss_dialog_abbrechen, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private String erstelleAbschlussDialogText() {
        int offeneArtikel = zaehleOffeneArtikel();
        boolean hatOffeneSynchronisierung = AppContainer.istApiModusAktiv()
                && artikelRepository != null
                && artikelRepository.hatOffeneSynchronisierung();

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

    private boolean istBearbeitungGesperrt() {
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
                artikelAdapter.zeigeAlleArtikel();
                aktualisiereLeereAnsicht();
                Toast.makeText(this, R.string.filter_alle_aktiv, Toast.LENGTH_SHORT).show();
                return true;
            }
            if (itemId == FILTER_OFFEN) {
                artikelAdapter.zeigeNurUngezaehlteArtikel();
                aktualisiereLeereAnsicht();
                Toast.makeText(this, R.string.filter_ungezaehlt_aktiv, Toast.LENGTH_SHORT).show();
                return true;
            }
            if (itemId == FILTER_GEZAEHLT) {
                artikelAdapter.zeigeNurGezaehlteArtikel();
                aktualisiereLeereAnsicht();
                Toast.makeText(this, R.string.filter_gezaehlt_aktiv, Toast.LENGTH_SHORT).show();
                return true;
            }
            if (itemId == FILTER_UNTER_MINDESTBESTAND) {
                artikelAdapter.zeigeNurUnterMindestbestandArtikel();
                aktualisiereLeereAnsicht();
                Toast.makeText(this, R.string.filter_unter_mindestbestand_aktiv, Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void ladeAnsichtsdaten(RecyclerView artikelRecyclerView) {
        executorService.execute(() -> {
            List<Artikel> geladeneArtikel = new ArrayList<>();
            Zaehlerliste geladeneZaehlerliste = null;
            List<Zaehlerliste> geladeneZaehlerlisten = new ArrayList<>();
            String fehlermeldung = null;

            try {
                geladeneZaehlerlisten = artikelRepository.ladeZaehlerlisten();
                artikelRepository.angleicheBeispielWochenlistenZuordnungFuerTest(
                        benutzersitzung.holeAngemeldetenZaehler(),
                        "Peter"
                );
                geladeneZaehlerlisten = artikelRepository.ladeZaehlerlisten();
                geladeneArtikel = artikelRepository.ladeArtikel(zaehlerlistenId);
                geladeneZaehlerliste = artikelRepository.ladeZaehlerlisteFuerId(zaehlerlistenId);
            } catch (RuntimeException exception) {
                fehlermeldung = "Artikeldaten konnten nicht geladen werden.";
            }

            List<Artikel> finaleGeladeneArtikel = geladeneArtikel;
            Zaehlerliste finaleGeladeneZaehlerliste = geladeneZaehlerliste;
            List<Zaehlerliste> finaleGeladeneZaehlerlisten = geladeneZaehlerlisten;
            String finaleFehlermeldung = fehlermeldung;
            runOnUiThread(() -> {
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

    private void initialisiereAnsicht(RecyclerView artikelRecyclerView) {
        if (aktuelleZaehlerliste != null && normalisiereText(ausgewaehlteZaehlerlistenBezeichnung).isEmpty()) {
            ausgewaehlteZaehlerlistenBezeichnung = aktuelleZaehlerliste.getBezeichnung();
        }
        setzeZaehlerlistenInformationen(aktuelleZaehlerliste);

        artikelAdapter = new ArtikelAdapterV2(artikelListe, this::oeffneArtikelDetailBottomSheet);
        artikelRecyclerView.setAdapter(artikelAdapter);
        EditText artikelSucheEditText = findViewById(R.id.artikelSucheEditText);
        if (artikelSucheEditText.getText() != null && artikelSucheEditText.getText().length() > 0) {
            artikelAdapter.setSuchbegriff(artikelSucheEditText.getText().toString());
        }
        aktualisiereFortschritt();
        aktualisiereLeereAnsicht();
        setzeTestmodusHinweis();
        setzeAngemeldetenZaehler();
        setzeStandardSchreiber();
        aktualisiereBlattNavigation();
        letzterGespeicherterStand = erstelleAktuellenStandFingerprint();
        autoSaveGeplant = false;
        autoSaveLaeuft = false;
        ignoriertAenderungenBeimInitialisieren = false;
        aktualisiereAutoSaveStatus();
    }

    private void markiereZaehlerlisteAlsAbgeschlossenUndVerlasse() {
        executorService.execute(() -> {
            try {
                artikelRepository.markiereZaehlerlisteAlsAbgeschlossen(zaehlerlistenId);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.abschluss_erfolgreich, Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (RuntimeException exception) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        R.string.abschluss_fehler,
                        Toast.LENGTH_SHORT
                ).show());
            }
        });
    }

    private void markiereZaehlerlisteAutomatischAlsAbgeschlossenUndVerlasse() {
        executorService.execute(() -> {
            try {
                artikelRepository.markiereZaehlerlisteAlsAbgeschlossen(zaehlerlistenId);
            } catch (RuntimeException exception) {
                // Rueckkehr zur Blattliste bleibt auch bei lokalem Fehler erlaubt.
            }
            runOnUiThread(() -> {
                setResult(RESULT_OK);
                finish();
            });
        });
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
        if (!kannZuBlattWechseln(richtung)) {
            Toast.makeText(
                    this,
                    richtung < 0 ? R.string.kein_vorheriges_blatt : R.string.kein_naechstes_blatt,
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        if (!hatUngespeicherteAenderungen() && !autoSaveLaeuft && !autoSaveGeplant) {
            wechsleZuBenachbartemBlatt(richtung);
            return;
        }
        speichereZaehlmengenLokal(false, () -> wechsleZuBenachbartemBlatt(richtung));
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




