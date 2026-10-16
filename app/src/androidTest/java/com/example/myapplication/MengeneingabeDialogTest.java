package com.example.myapplication;

import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MengeneingabeDialogTest {
    @Test
    public void entwurfBleibtOffenUndSpeichertErstNachBestaetigung() {
        // LoginActivity meldet im Demo-Modus ab. Bestehende Sitzung danach wiederherstellen.
        Benutzersitzung sitzung = new Benutzersitzung(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        String vorherigerZaehler = sitzung.holeAngemeldetenZaehler();
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            MengeneingabeDialog dialog = new MengeneingabeDialog();
            Artikel artikel = new Artikel("TEST", "Test", 1, 0, null);
            for (String wert : new String[]{"5", "125", "0", ""}) {
                scenario.onActivity(a -> {
                    dialog.zeige(a, artikel, () -> { });
                    dialog.zeige(a, artikel, () -> fail("Doppelter Dialog"));
                });
                Integer vorher = artikel.getZaehlMenge();
                onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).perform(replaceText(wert));
                onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).check(matches(isDisplayed()));
                assertEquals(vorher, artikel.getZaehlMenge());
                onView(withText(R.string.anmerkung_dialog_speichern)).inRoot(isDialog()).perform(click());
                assertEquals(wert.isEmpty() ? null : Integer.valueOf(wert), artikel.getZaehlMenge());
            }
            artikel.setZaehlMenge(125);
            scenario.onActivity(a -> dialog.zeige(a, artikel, () -> fail("Abbrechen darf nicht speichern")));
            onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).check(matches(withText("125")))
                    .perform(replaceText("999"));
            onView(withText(R.string.anmerkung_dialog_abbrechen)).inRoot(isDialog()).perform(click());
            assertEquals(Integer.valueOf(125), artikel.getZaehlMenge());
            scenario.onActivity(a -> dialog.zeige(a, artikel, () -> fail("Ungueltige Menge")));
            onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).perform(replaceText("2147483648"));
            onView(withText(R.string.anmerkung_dialog_speichern)).inRoot(isDialog()).perform(click());
            onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).check(matches(isDisplayed()));
            assertEquals(Integer.valueOf(125), artikel.getZaehlMenge());
            scenario.onActivity(a -> dialog.schliesse());
        } finally {
            if (vorherigerZaehler == null || vorherigerZaehler.isEmpty()) {
                sitzung.abmelden();
            } else {
                sitzung.setzeAngemeldetenZaehler(vorherigerZaehler);
            }
        }
    }
}
