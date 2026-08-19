package dev.allofus.fusioncore;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.pm.PackageInfoCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

public class GameSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_PACKAGE_NAME = "extra_package_name";

    private ImageView ivAppIcon;
    private TextView tvAppName;
    private TextView tvPackageName;
    private TextView tvVersionInfo;

    private MaterialSwitch switchLibUnity;

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
        setupListeners();
    }

    private void initViews() {
        ivAppIcon = findViewById(R.id.ivAppIcon);
        tvAppName = findViewById(R.id.tvAppName);
        tvPackageName = findViewById(R.id.tvPackageName);
        tvVersionInfo = findViewById(R.id.tvVersionInfo);

        switchLibUnity = findViewById(R.id.switchLibUnity);
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void resolveAndDisplayPackageInfo(String packageName) {
        PackageManager pm = getPackageManager();

        try {
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageInfo = pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0));
            } else {
                packageInfo = pm.getPackageInfo(packageName, 0);
            }

            CharSequence appLabel = pm.getApplicationLabel(packageInfo.applicationInfo);
            Drawable appIcon = pm.getApplicationIcon(packageInfo.applicationInfo);

            String versionName = packageInfo.versionName != null ? packageInfo.versionName : "N/A";
            long versionCode = PackageInfoCompat.getLongVersionCode(packageInfo);

            tvAppName.setText(appLabel);
            tvPackageName.setText(packageName);
            tvVersionInfo.setText(String.format("Version %s (%d)", versionName, versionCode));
            ivAppIcon.setImageDrawable(appIcon);

        } catch (PackageManager.NameNotFoundException e) {
            Toast.makeText(this, "Package not found: " + packageName, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupListeners() {
        switchLibUnity.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FusionSettings.setUseUnstrippedLibUnityForGame(this, targetPackageName, isChecked);
        });
    }
}