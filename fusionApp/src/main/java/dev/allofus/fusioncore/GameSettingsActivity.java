package dev.allofus.fusioncore;

import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.pm.PackageInfoCompat;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dalvik.system.DexFile;

public class GameSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_PACKAGE_NAME = "extra_package_name";

    private ImageView ivAppIcon;
    private TextView tvAppName;
    private TextView tvPackageName;
    private TextView tvVersionInfo;

    private AutoCompleteTextView actvOverrideActivity;
    private SwitchCompat switchLibUnity;

    private String targetPackageName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_settings);

        initViews();
        setupToolbar();

        targetPackageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);

        if (TextUtils.isEmpty(targetPackageName)) {
            Toast.makeText(this, "No target package provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        resolveAndDisplayPackageInfo(targetPackageName);
        switchLibUnity.setChecked(FusionSettings.getUseUnstrippedLibUnityForGame(this, targetPackageName));
        actvOverrideActivity.setText(FusionSettings.getActivityOverrideForGame(this, targetPackageName), false);
        setupListeners();
    }

    private void initViews() {
        ivAppIcon = findViewById(R.id.ivAppIcon);
        tvAppName = findViewById(R.id.tvAppName);
        tvPackageName = findViewById(R.id.tvPackageName);
        tvVersionInfo = findViewById(R.id.tvVersionInfo);

        actvOverrideActivity = findViewById(R.id.activity_override_actv);
        switchLibUnity = findViewById(R.id.switchLibUnity);
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void resolveAndDisplayPackageInfo(String packageName) {
        PackageManager pm = getPackageManager();

        try {
            PackageInfo packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);

            CharSequence appLabel;
            Drawable appIcon;
            if (packageInfo.applicationInfo != null) {
                appLabel = pm.getApplicationLabel(packageInfo.applicationInfo);
                appIcon = pm.getApplicationIcon(packageInfo.applicationInfo);
            } else {
                appLabel = packageName;
                appIcon = AppCompatResources.getDrawable(this, R.drawable.android_48px);
            }

            String versionName = packageInfo.versionName != null ? packageInfo.versionName : "N/A";
            long versionCode = PackageInfoCompat.getLongVersionCode(packageInfo);

            tvAppName.setText(appLabel);
            tvPackageName.setText(packageName);
            tvVersionInfo.setText(String.format(Locale.getDefault(), "Version %s (%d)", versionName, versionCode));
            ivAppIcon.setImageDrawable(appIcon);

            Set<String> activities = new HashSet<>();
            activities.add("Automatic");
            if (packageInfo.activities != null) {
                for (ActivityInfo activity : packageInfo.activities) {
                    activities.add(activity.name);
                }
            } else {
                Toast.makeText(this, "Could not read activity list!", Toast.LENGTH_LONG).show();
            }

            var arrayAdapter = new ArrayAdapter<>(this, R.layout.item_dropdown, activities.toArray());
            actvOverrideActivity.setAdapter(arrayAdapter);
            actvOverrideActivity.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
                String selectedItem = (String) parent.getItemAtPosition(position);
                FusionSettings.setActivityOverrideForGame(this, targetPackageName, selectedItem);
            });
        } catch (PackageManager.NameNotFoundException e) {
            Toast.makeText(this, "Package not found: " + packageName, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupListeners() {
        switchLibUnity.setOnCheckedChangeListener((buttonView, isChecked) ->
                FusionSettings.setUseUnstrippedLibUnityForGame(this, targetPackageName, isChecked));
    }
}