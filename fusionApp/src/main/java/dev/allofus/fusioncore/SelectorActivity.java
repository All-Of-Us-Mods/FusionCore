package dev.allofus.fusioncore;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public class SelectorActivity extends Activity {
    private static final String TAG = "FusionCore";
    private static final String[] SUPPORTED_PACKAGES = {
            "com.innersloth.spacemafia",
            "com.abstractsoft.hybridanimals",
            "com.StefMorojna.SpaceflightSimulator",
            "com.DanVogt.DATAWING"
    };

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
            launchBootstrap(selected.packageName);
        });
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
