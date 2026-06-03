package dev.allofus.fusioncore;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Switch;

public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        View root = findViewById(R.id.settings_root);
        int basePadding = Math.round(getResources().getDisplayMetrics().density * 16f);
        Utilities.applyWindowInsets(root, basePadding);

        ImageButton backButton = findViewById(R.id.settings_action_back);
        backButton.setOnClickListener(v -> finish());

        Switch libUnityToggle = findViewById(R.id.settings_libunity_toggle);
        libUnityToggle.setChecked(FusionSettings.isDownloadUnstrippedLibUnity(this));
        libUnityToggle.setOnCheckedChangeListener(
                (button, isChecked) -> FusionSettings.setDownloadUnstrippedLibUnity(this, isChecked));
    }
}
