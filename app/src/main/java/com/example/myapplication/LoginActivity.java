package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class LoginActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Benutzersitzung benutzersitzung = new Benutzersitzung(this);
        if (AppContainer.istMockModusAktiv()) {
            // Fuer den aktuellen Testlauf soll der Flow immer sauber bei Login starten.
            benutzersitzung.abmelden();
        }
        if (benutzersitzung.istZaehlerAngemeldet()) {
            starteHauptmenue();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login_draft);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.loginRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    systemBars.bottom
            );
            return insets;
        });

        android.view.View anmeldenButton = findViewById(R.id.anmeldenButton);
        anmeldenButton.setOnClickListener(v -> meldeZaehlerAn());
    }

    private void meldeZaehlerAn() {
        EditText zaehlerNameEditText = findViewById(R.id.zaehlerNameEditText);
        String zaehlerName = zaehlerNameEditText.getText() == null
                ? ""
                : zaehlerNameEditText.getText().toString().trim();

        if (zaehlerName.isEmpty()) {
            Toast.makeText(this, R.string.login_zaehler_fehlt, Toast.LENGTH_SHORT).show();
            return;
        }

        Benutzersitzung benutzersitzung = new Benutzersitzung(this);
        benutzersitzung.setzeAngemeldetenZaehler(zaehlerName);
        starteHauptmenue();
    }

    private void starteHauptmenue() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
