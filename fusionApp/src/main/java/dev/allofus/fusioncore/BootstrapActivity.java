package dev.allofus.fusioncore;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class BootstrapActivity extends AppCompatActivity {
    private static final String TAG = "FusionCore";

    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_USE_ORIGINAL_LIBUNITY = "og_libunity";
    public static final String BACKUP_UNITY_VERSION = "2017.0.0";

    private final AtomicBoolean hookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean fusionInitialized = new AtomicBoolean(false);

    private TextView statusView;
    private TextView progressDetailsView;
    private ProgressBar spinnerProgress;
    private ProgressBar downloadProgress;
    private volatile PreparedFusionState preparedState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bootstrap);
        statusView = findViewById(R.id.bootstrap_status);
        progressDetailsView = findViewById(R.id.bootstrap_progress_details);
        spinnerProgress = findViewById(R.id.bootstrap_progress);
        downloadProgress = findViewById(R.id.bootstrap_download_progress);
        setPhaseStatus(getString(R.string.bootstrap_status_preparing));

        String targetPackage = getIntent().getStringExtra(EXTRA_TARGET_PACKAGE);
        if (targetPackage == null || targetPackage.isEmpty()) {
            // Fall back to meta-data on the launching component so activity-aliases
            // (e.g. the per-game VR Library icons) can pin a target without extras.
            try {
                ComponentName cn = getIntent().getComponent();
                if (cn == null) cn = getComponentName();
                ActivityInfo info = getPackageManager().getActivityInfo(cn, PackageManager.GET_META_DATA);
                if (info.metaData != null) {
                    targetPackage = info.metaData.getString(EXTRA_TARGET_PACKAGE);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read target_package meta-data", e);
            }
        }
        if (targetPackage == null || targetPackage.isEmpty()) {
            failAndFinish("No target package specified in intent extras!", null);
            return;
        }
        final String finalTargetPackage = targetPackage;

        // Let the loading screen render first, then perform initialization work.
        statusView.post(() -> new Thread(() -> runBootstrapFlow(finalTargetPackage), "bootstrap-flow").start());
    }

    private void runBootstrapFlow(String targetPackage) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(targetPackage);
        if (launchIntent == null) {
            failAndFinish("No launch intent for target package: " + targetPackage, null);
            return;
        }

        ComponentName launcher = launchIntent.getComponent();
        if (launcher == null) {
            launcher = launchIntent.resolveActivity(getPackageManager());
        }

        if (launcher == null) {
            failAndFinish("Failed to resolve launcher activity for target package: " + targetPackage, null);
            return;
        }

        Context gameContext;
        try {
            gameContext = createPackageContext(targetPackage, CONTEXT_IGNORE_SECURITY | CONTEXT_INCLUDE_CODE);
        } catch (Exception e) {
            failAndFinish("Failed to create package context for target package: " + targetPackage, e);
            return;
        }

        boolean useOriginalLibUnity = getIntent().getBooleanExtra(EXTRA_USE_ORIGINAL_LIBUNITY, false);
        try {
            preparedState = prepareFusionState(this, gameContext, targetPackage, useOriginalLibUnity);
        } catch (Throwable t) {
            failAndFinish("Failed while preparing Fusion runtime.", t);
            return;
        }

        setPhaseStatus(getString(R.string.bootstrap_status_installing_hooks));
        try {
            ClassLoaderHooks.installHooks(gameContext.getClassLoader());
            PackageManagerHooks.installHooks(getPackageManager());
            UnityPlayerHooks.installHooks(gameContext, preparedState.metadataZip);
        } catch (Exception e) {
            Log.e(TAG, "Failed to install base hooks", e);
        }

        final String launcherClassName = launcher.getClassName();
        try {
            AppCompatBypassHooks.installHooks(gameContext.getClassLoader(), launcherClassName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to install AppCompat bypass hooks", e);
        }

        if (!installLauncherOnCreateHook(gameContext.getClassLoader(), launcherClassName,
                (launcherActivity, bundle) -> initializeFusion(launcherActivity, targetPackage))) {
            failAndFinish("Failed to install launcher hook! See log for details.", null);
            return;
        }

        try {
            var launcherClass = gameContext.getClassLoader().loadClass(launcherClassName);

            setPhaseStatus(getString(R.string.bootstrap_status_launching));
            runOnMainThread(() -> {
                try {
                    var intent = new Intent(this, launcherClass);
                    startActivity(intent);
                    finish();
                } catch (Throwable t) {
                    failAndFinish("Failed to launch target app's launcher activity: " + launcherClassName, t);
                }
            });
        } catch (Exception e) {
            failAndFinish("Failed to launch target app's launcher activity: " + launcherClassName, e);
        }
    }

    private void setPhaseStatus(String status) {
        runOnMainThread(() -> {
            if (statusView != null) {
                statusView.setText(status);
            }
            if (spinnerProgress != null) {
                spinnerProgress.setVisibility(View.VISIBLE);
            }
            if (downloadProgress != null) {
                downloadProgress.setVisibility(View.GONE);
                downloadProgress.setIndeterminate(false);
                downloadProgress.setProgress(0);
            }
            if (progressDetailsView != null) {
                progressDetailsView.setVisibility(View.GONE);
                progressDetailsView.setText("");
            }
        });
    }

    private void setDownloadStatus(long downloadedBytes, long totalBytes) {
        runOnMainThread(() -> {
            if (spinnerProgress != null) {
                spinnerProgress.setVisibility(View.GONE);
            }
            long progress = Math.max(0L, Math.min(100L, (downloadedBytes * 100L) / totalBytes));
            if (downloadProgress != null) {
                downloadProgress.setVisibility(View.VISIBLE);
                boolean hasTotal = totalBytes > 0L;
                downloadProgress.setIndeterminate(!hasTotal);
                if (hasTotal) {
                    int percent = (int) progress;
                    downloadProgress.setProgress(percent);
                }
            }
            if (statusView != null) {
                statusView.setText(getString(R.string.bootstrap_status_downloading_libunity));
            }
            if (progressDetailsView != null) {
                progressDetailsView.setVisibility(View.VISIBLE);
                int percent = totalBytes > 0L
                        ? (int) progress
                        : 0;
                progressDetailsView.setText(getString(
                        R.string.bootstrap_download_progress,
                        percent,
                        formatBytes(downloadedBytes),
                        totalBytes > 0L ? formatBytes(totalBytes) : "?"
                ));
            }
        });
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = new String[]{"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex < units.length - 1) {
            value /= 1024.0;
            unitIndex++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex]);
    }

    private void failAndFinish(String message, Throwable error) {
        runOnMainThread(() -> {
            if (error != null) {
                Log.e(TAG, message, error);
            } else {
                Log.e(TAG, message);
            }
            if (statusView != null) {
                statusView.setText(getString(R.string.bootstrap_status_error));
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private void runOnMainThread(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            runOnUiThread(runnable);
        }
    }

    private interface BeforeOnCreateAction {
        void run(Activity launcherActivity, Bundle bundle);
    }

    private boolean installLauncherOnCreateHook(ClassLoader gameClassLoader,
                                                String launcherClassName,
                                                BeforeOnCreateAction action) {
        if (hookInstalled.get()) {
            return true;
        }

        try {
            Class<?> launcherClass = Class.forName(launcherClassName, false, gameClassLoader);
            Method onCreateMethod = Utilities.findOnCreateMethod(launcherClass);
            onCreateMethod.setAccessible(true);

            Pine.hook(onCreateMethod, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    Log.i(TAG, "beforeCall fired for: " + callFrame.thisObject);
                    if (!(callFrame.thisObject instanceof Activity)) {
                        Log.w(TAG, "Launcher hook hit but receiver is not an Activity: " + callFrame.thisObject);
                        return;
                    }
                    Activity activity = (Activity) callFrame.thisObject;

                    activity.setTheme(dev.allofus.fusioncore.R.style.UnityThemeSelector);

                    Bundle bundle = null;
                    if (callFrame.args != null && callFrame.args.length > 0 && callFrame.args[0] instanceof Bundle) {
                        bundle = (Bundle) callFrame.args[0];
                    }

                    try {
                        action.run((Activity) callFrame.thisObject, bundle);
                    } catch (Throwable t) {
                        Log.e(TAG, "Fusion pre-onCreate action failed", t);
                    }
                }
            });

            hookInstalled.set(true);
            Log.i(TAG, "Installed launcher onCreate hook for " + launcherClassName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to install launcher onCreate hook for " + launcherClassName, e);
            return false;
        }
    }

    private void initializeFusion(Activity launcherActivity, String targetPackage) {
        if (!fusionInitialized.compareAndSet(false, true)) {
            return;
        }

        PreparedFusionState prepared = preparedState;
        if (prepared == null || !targetPackage.equals(prepared.targetPackage)) {
            Log.e(TAG, "Fusion config was not prepared for target package: " + targetPackage);
            return;
        }

        String launcherName = launcherActivity != null
                ? launcherActivity.getClass().getName()
                : "<unknown launcher>";
        Log.i(TAG, "Initializing Fusion for " + targetPackage + " via " + launcherName);

        try {
            FusionConfig config = prepared.config;

            NativeLibraryManager.addFusionLibrary("main");
            NativeLibraryManager.addFusionLibrary("fusion");
            NativeLibraryManager.addDataLibrary("il2cpp");
            NativeLibraryManager.addDataLibrary("unity");
            NativeLibraryManager.setupLibraryHooks(config);

            File stagedConfig = FusionConfigStore.write(this, config);
            Log.i(TAG, "Fusion config staged at " + stagedConfig.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize Fusion in launcher beforeCall", t);
        }
    }

    private PreparedFusionState prepareFusionState(Context appContext,
                                                   Context gameContext,
                                                   String targetPackage,
                                                   boolean useOriginalLibUnity) {
        String gameLibDir = gameContext.getApplicationInfo().nativeLibraryDir;
        String appLibDir = appContext.getApplicationInfo().nativeLibraryDir;
        String targetGameAbi = resolveTargetGameAbi(gameLibDir);
        File appDataDir = new File(appContext.getFilesDir(), targetPackage);

        setPhaseStatus(getString(R.string.bootstrap_status_copy_assets));
        File copiedData = new File(appDataDir, "Data_copy");
        boolean copied = Utilities.copyAssets(gameContext.getAssets(), "bin/Data", copiedData);
        if (!copied) {
            Log.e(TAG, "Failed to copy Unity Data assets! MelonLoader may not work correctly.");
        }
        File mdZip = new File(appDataDir, "metadata_override.zip");
        if (!mdZip.exists()) {
            try {
                File mdSrc = new File(copiedData, "Managed/Metadata/global-metadata.dat");
                createUncompressedAssetZip(mdSrc,
                        "assets/bin/Data/Managed/Metadata/global-metadata.dat", mdZip);
                Log.i(TAG, "Created uncompressed metadata zip at " + mdZip.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "Failed to create metadata zip", e);
            }
        }
        File melonAssemblyGenDir = new File("/storage/emulated/0/MelonLoader/dev.allofus.fusioncore/MelonLoader/Dependencies/Il2CppAssemblyGenerator");
        File mdDest = new File("/storage/emulated/0/MelonLoader/dev.allofus.fusioncore", "global-metadata.dat");
        if (!mdDest.exists() || mdDest.length() == 0) {
            melonAssemblyGenDir.mkdirs();
            File mdSrc = new File(copiedData, "Managed/Metadata/global-metadata.dat");
            try (java.io.InputStream in = new java.io.FileInputStream(mdSrc);
                 java.io.OutputStream out = new java.io.FileOutputStream(mdDest)) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Log.i(TAG, "Pre-placed global-metadata.dat at " + mdDest.getAbsolutePath());
        }
        setPhaseStatus(getString(R.string.bootstrap_status_detecting_version));
        String version = VersionLookup.TryLookup(copiedData);
        if (version == null) {
            Log.e(TAG, "Failed to determine Unity version! MelonLoader may not work correctly.");
            version = BACKUP_UNITY_VERSION;
            useOriginalLibUnity = true;
        } else {
            Log.i(TAG, "Determined Unity version: " + version);
            if (LibUnityDownloader.downloadAndCacheSafely(appDataDir, version, targetGameAbi, new LibUnityDownloader.DownloadProgressListener() {
                @Override
                public void onDownloadStarted(String url, long totalBytes) {
                    setDownloadStatus(0L, totalBytes);
                }

                @Override
                public void onDownloadProgress(long downloadedBytes, long totalBytes) {
                    setDownloadStatus(downloadedBytes, totalBytes);
                }

                @Override
                public void onDownloadFinished(boolean success, boolean usedCache) {
                    // No-op: next phase status is set by prepareFusionState.
                }
            })) {
                Log.i(TAG, "Successfully downloaded libunity for version " + version + " and ABI " + targetGameAbi);
            } else {
                Log.e(TAG, "Failed to download libunity for version " + version + " and ABI " + targetGameAbi + ", falling back to original.");
                useOriginalLibUnity = true;
            }
        }

        setPhaseStatus(getString(R.string.bootstrap_status_registering_libraries));
        File[] nativeLibs = new File(gameLibDir).listFiles();
        if (nativeLibs != null) {
            for (File file : nativeLibs) {
                String name = file.getName();
                if (name.startsWith("lib") && name.endsWith(".so") && name.length() > 6) {
                    String extractedName = name.substring(3, name.length() - 3);
                    NativeLibraryManager.addGameLibrary(extractedName);
                }
            }
        } else {
            Log.e(TAG, "Failed to list game native libraries! MelonLoader may not work correctly.");
        }

        FusionConfig config = new FusionConfig(
                gameLibDir,
                appLibDir,
                appDataDir.getAbsolutePath(),
                copiedData.getAbsolutePath(),
                version,
                useOriginalLibUnity
        );

        return new PreparedFusionState(targetPackage, config, mdZip);
    }
    private static void createUncompressedAssetZip(File src, String entryPath, File dest) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(dest);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos)) {
            zos.setMethod(java.util.zip.ZipOutputStream.STORED);
            byte[] data = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                data = Files.readAllBytes(src.toPath());
            }
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(entryPath);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            entry.setCrc(crc.getValue());
            zos.putNextEntry(entry);
            zos.write(data);
            zos.closeEntry();
        }
    }
    private static final class PreparedFusionState {
        private final String targetPackage;
        private final FusionConfig config;
        private final File metadataZip;

        private PreparedFusionState(String targetPackage, FusionConfig config, File metadataZip) {
            this.targetPackage = targetPackage;
            this.config = config;
            this.metadataZip = metadataZip;
        }
    }
    private String resolveTargetGameAbi(String gameLibDir) {
        if (gameLibDir == null || gameLibDir.isEmpty()) {
            return null;
        }

        String abi = new File(gameLibDir).getName();
        if (abi.isEmpty()) {
            return null;
        }

        return abi;
    }
}
