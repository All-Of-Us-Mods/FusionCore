package dev.allofus.fusioncore;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import dev.allofus.fusioncore.tools.Utilities;

public class SelectorActivity extends AppCompatActivity {
    private static final String TAG = "FusionCore";
    private static final int REQUEST_MANAGE_EXTERNAL_STORAGE = 1001;
    private static final String[] UNITY_ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"};

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

                    ImageButton settingsButton = convertView.findViewById(R.id.selector_action_settings);
                    settingsButton.setOnClickListener(v -> {
                        var intent = new Intent(getContext(), GameSettingsActivity.class);
                        intent.putExtra(GameSettingsActivity.EXTRA_PACKAGE_NAME, entry.packageName);
                        startActivity(intent);
                    });
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
        Set<String> seenPackages = new HashSet<>();

        Intent launchIntent = new Intent(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = pm.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL);

        for (ResolveInfo resolveInfo : activities) {
            String packageName = resolveInfo.activityInfo.packageName;
            if (packageName == null || !seenPackages.add(packageName)) {
                continue;
            }
            if (packageName.equals(getPackageName())) {
                continue;
            }

            ApplicationInfo info;
            try {
                info = pm.getApplicationInfo(packageName, 0);
            } 
            catch (PackageManager.NameNotFoundException e) {
                continue;
            }

            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }

            if (!hasIl2Cpp(info)) {
                continue;
            }

            String label = packageName;
            Drawable icon = pm.getDefaultActivityIcon();
            String versionName = "Unknown";
            long versionCode = 0L;
            try {
                label = pm.getApplicationLabel(info).toString();
                icon = pm.getApplicationIcon(info);

                PackageInfo packageInfo;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageInfo = pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0));
                } else {
                    packageInfo = pm.getPackageInfo(packageName, 0);
                }
                if (packageInfo.versionName != null && !packageInfo.versionName.isEmpty()) {
                    versionName = packageInfo.versionName;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    versionCode = packageInfo.getLongVersionCode();
                } else {
                    //noinspection deprecation
                    versionCode = packageInfo.versionCode;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to resolve metadata for package: " + packageName, e);
            }

            Log.i(TAG, "Found installed target: " + packageName + " (" + label + ")");
            result.add(new AppEntry(packageName, label, icon, versionName, versionCode));
        }

        return result;
    }

    private static boolean hasIl2Cpp(ApplicationInfo info) {
        List<String> apkPaths = new ArrayList<>();
        if (info.sourceDir != null) {
            apkPaths.add(info.sourceDir);
        }
        if (info.splitSourceDirs != null) {
            Collections.addAll(apkPaths, info.splitSourceDirs);
        }
        for (String apk : apkPaths) {
            if (apkContainsIl2Cpp(apk)) {
                return true;
            }
        }

        String nativeDir = info.nativeLibraryDir;
        if (nativeDir != null && !nativeDir.isEmpty()) {
            File dir = new File(nativeDir);
            if (new File(dir, "libil2cpp.so").exists()) {
                return true;
            }
            File[] abiDirs = dir.listFiles();
            if (abiDirs != null) {
                for (File abiDir : abiDirs) {
                    if (abiDir.isDirectory() && new File(abiDir, "libil2cpp.so").exists()) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean apkContainsIl2Cpp(String apkPath) {
        try {
            try (ZipFile zip = new ZipFile(apkPath)) {
                for (String abi : UNITY_ABIS) {
                    if (zip.getEntry("lib/" + abi + "/libil2cpp.so") != null) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Unreadable APK; fall through to the nativeLibraryDir check.
        }
        return false;
    }

    private void launchBootstrap(String packageName) {
        Intent intent = new Intent(this, BootstrapActivity.class);
        intent.putExtra(BootstrapActivity.EXTRA_TARGET_PACKAGE, packageName);
        intent.putExtra(BootstrapActivity.EXTRA_USE_ORIGINAL_LIBUNITY,
                !FusionSettings.getUseUnstrippedLibUnityForGame(this, packageName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        //noinspection deprecation
        overridePendingTransition(0, 0);
        finish();
        //noinspection deprecation
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

    private record AppEntry(String packageName, String label, Drawable icon, String versionName,
                            long versionCode) {

        @NonNull
        @Override
        public String toString() {
            if (label.equals(packageName)) {
                return packageName;
            }
            return label + " (" + packageName + ")";
        }
    }

    private record RowHolder(ImageView icon, TextView name, TextView packageName,
                             TextView version) {
    }
}
