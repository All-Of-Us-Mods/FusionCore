package dev.allofus.fusioncore;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public class SelectorActivity extends Activity {
    private static final String TAG = "FusionCore";
    private static final int REQUEST_MANAGE_EXTERNAL_STORAGE = 1001;
    private static final String[] SUPPORTED_PACKAGES = {
            "com.innersloth.spacemafia",
            "com.abstractsoft.animalsmash",
            "com.abstractsoft.hybridanimals",
            "com.abstractsoft.humansversusai",
            "com.abstractsoft.mashypets",
            "com.antiherostudios.misfitz",
            "com.Radeon.RecRoom",
            "com.StefMorojna.SpaceflightSimulator",
            "com.DanVogt.DATAWING"
    };

    private String pendingLaunchPackage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_selector);

        View root = findViewById(R.id.selector_root);
        int basePadding = Math.round(getResources().getDisplayMetrics().density * 16f);
        Utilities.applyWindowInsets(root, basePadding);

        ListView listView = findViewById(R.id.selector_list);
        TextView emptyView = findViewById(R.id.selector_empty);
        listView.setEmptyView(emptyView);

        List<AppEntry> installedTargets = resolveInstalledTargets();
        Drawable defaultIcon = getPackageManager().getDefaultActivityIcon();
        ArrayAdapter<AppEntry> adapter = new ArrayAdapter<>(
                this,
                R.layout.item_selector_target,
                installedTargets
        ) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                RowHolder holder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_selector_target, parent, false);
                    holder = new RowHolder(
                            convertView.findViewById(R.id.row_icon),
                            convertView.findViewById(R.id.row_name),
                            convertView.findViewById(R.id.row_package),
                            convertView.findViewById(R.id.row_version)
                    );
                    convertView.setTag(holder);
                } else {
                    holder = (RowHolder) convertView.getTag();
                }

                AppEntry entry = getItem(position);
                if (entry != null) {
                    holder.icon.setImageDrawable(entry.icon != null ? entry.icon : defaultIcon);
                    holder.name.setText(entry.label);
                    holder.packageName.setText(entry.packageName);
                    holder.version.setText(Utilities.formatVersionText(entry.versionName, entry.versionCode));
                }
                return convertView;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry selected = installedTargets.get(position);
            maybeLaunchBootstrap(selected.packageName);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingLaunchPackage != null && hasExternalStorageManagerAccess()) {
            String packageName = pendingLaunchPackage;
            pendingLaunchPackage = null;
            launchBootstrap(packageName);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MANAGE_EXTERNAL_STORAGE || pendingLaunchPackage == null) {
            return;
        }

        if (hasExternalStorageManagerAccess()) {
            String packageName = pendingLaunchPackage;
            pendingLaunchPackage = null;
            launchBootstrap(packageName);
            return;
        }

        Toast.makeText(this, getString(R.string.selector_storage_permission_required), Toast.LENGTH_LONG).show();
    }

    private List<AppEntry> resolveInstalledTargets() {
        PackageManager pm = getPackageManager();
        List<AppEntry> result = new ArrayList<>();

        for (String pkg : SUPPORTED_PACKAGES) {
            if (pm.getLaunchIntentForPackage(pkg) == null) {
                continue;
            }

            String label = pkg;
            Drawable icon = pm.getDefaultActivityIcon();
            String versionName = "Unknown";
            long versionCode = 0L;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                label = pm.getApplicationLabel(info).toString();
                icon = pm.getApplicationIcon(info);

                PackageInfo packageInfo;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageInfo = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0));
                } else {
                    packageInfo = pm.getPackageInfo(pkg, 0);
                }
                if (packageInfo.versionName != null && !packageInfo.versionName.isEmpty()) {
                    versionName = packageInfo.versionName;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    versionCode = packageInfo.getLongVersionCode();
                } else {
                    versionCode = packageInfo.versionCode;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to resolve metadata for package: " + pkg, e);
            }

            Log.i(TAG, "Found installed target: " + pkg + " (" + label + ")");
            result.add(new AppEntry(pkg, label, icon, versionName, versionCode));
        }

        return result;
    }

    private void launchBootstrap(String packageName) {
        Intent intent = new Intent(this, BootstrapActivity.class);
        intent.putExtra(BootstrapActivity.EXTRA_TARGET_PACKAGE, packageName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
        overridePendingTransition(0, 0);
    }

    private void maybeLaunchBootstrap(String packageName) {
        if (!hasExternalStorageManagerAccess()) {
            pendingLaunchPackage = packageName;
            requestExternalStorageManagerAccess();
            return;
        }
        launchBootstrap(packageName);
    }

    private boolean hasExternalStorageManagerAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true;
        }
        return Environment.isExternalStorageManager();
    }

    private void requestExternalStorageManagerAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }

        Toast.makeText(this, getString(R.string.selector_storage_permission_prompt), Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE);
        } catch (Exception e) {
            Log.w(TAG, "Failed to open app-specific all-files access screen, opening generic page", e);
            Intent fallbackIntent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            try {
                startActivityForResult(fallbackIntent, REQUEST_MANAGE_EXTERNAL_STORAGE);
            } catch (Exception inner) {
                Log.e(TAG, "Failed to open all-files access settings", inner);
                Toast.makeText(this, getString(R.string.selector_storage_permission_open_failed), Toast.LENGTH_LONG).show();
            }
        }
    }

    private static final class AppEntry {
        private final String packageName;
        private final String label;
        private final Drawable icon;
        private final String versionName;
        private final long versionCode;

        private AppEntry(String packageName, String label, Drawable icon, String versionName, long versionCode) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.versionName = versionName;
            this.versionCode = versionCode;
        }

        @NonNull
        @Override
        public String toString() {
            if (label.equals(packageName)) {
                return packageName;
            }
            return label + " (" + packageName + ")";
        }
    }

    private static final class RowHolder {
        private final ImageView icon;
        private final TextView name;
        private final TextView packageName;
        private final TextView version;

        private RowHolder(ImageView icon, TextView name, TextView packageName, TextView version) {
            this.icon = icon;
            this.name = name;
            this.packageName = packageName;
            this.version = version;
        }
    }
}
