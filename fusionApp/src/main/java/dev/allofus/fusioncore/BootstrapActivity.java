package dev.allofus.fusioncore;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class BootstrapActivity extends Activity {
    private static final String TAG = "FusionCore";

    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_USE_ORIGINAL_LIBUNITY = "og_libunity";
    public static final String BACKUP_UNITY_VERSION = "2017.0.0";

    private final AtomicBoolean hookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean fusionInitialized = new AtomicBoolean(false);
    private final AtomicBoolean nativeLibsLoaded = new AtomicBoolean(false);

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
            failAndFinish("No target package specified in intent extras!", null);
            return;
        }

        // Let the loading screen render first, then perform initialization work.
        statusView.post(() -> new Thread(() -> runBootstrapFlow(targetPackage), "bootstrap-flow").start());
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
            UnityPlayerHooks.installHooks(gameContext);
        } catch (Exception e) {
            Log.e(TAG, "Failed to install base hooks", e);
        }

        final String launcherClassName = launcher.getClassName();
        if (!installLauncherOnCreateHook(gameContext, launcherClassName,
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

    private boolean installLauncherOnCreateHook(Context gameContext,
                                                String launcherClassName,
                                                BeforeOnCreateAction action) {
        if (hookInstalled.get()) {
            return true;
        }

        try {
            Class<?> launcherClass = Class.forName(launcherClassName, false, gameContext.getClassLoader());
            Method onCreateMethod = Utilities.findOnCreateMethod(launcherClass);
            onCreateMethod.setAccessible(true);

            Pine.hook(onCreateMethod, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    if (!(callFrame.thisObject instanceof Activity)) {
                        Log.w(TAG, "Launcher hook hit but receiver is not an Activity: " + callFrame.thisObject);
                        return;
                    }

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
            System.loadLibrary("main");

            NativeLibraryManager.addDataLibrary("il2cpp");
            NativeLibraryManager.addDataLibrary("unity");
            NativeLibraryManager.setupLibraryHooks(config);

            loadNativeLibraries();
            ActivityBridge.loadFusion(config);
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
        File appDataDir = new File(appContext.getFilesDir(), targetPackage);

        setPhaseStatus(getString(R.string.bootstrap_status_copy_assets));
        File copiedData = new File(appDataDir, "Data_copy");
        boolean copied = Utilities.copyAssets(gameContext.getAssets(), "bin/Data", copiedData);
        if (!copied) {
            Log.e(TAG, "Failed to copy Unity Data assets! BepInEx may not work correctly.");
        }

        setPhaseStatus(getString(R.string.bootstrap_status_detecting_version));
        String version = VersionLookup.TryLookup(copiedData);
        if (version == null) {
            Log.e(TAG, "Failed to determine Unity version! BepInEx may not work correctly.");
            version = BACKUP_UNITY_VERSION;
            useOriginalLibUnity = true;
        } else {
            Log.i(TAG, "Determined Unity version: " + version);
            if (LibUnityDownloader.downloadAndCacheSafely(appDataDir, version, new LibUnityDownloader.DownloadProgressListener() {
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
                Log.i(TAG, "Successfully downloaded libunity for version " + version);
            } else {
                Log.e(TAG, "Failed to download libunity for version " + version + ", falling back to original.");
                useOriginalLibUnity = true;
            }
        }

        setPhaseStatus(getString(R.string.bootstrap_status_extracting_runtime));
        File bepInExDir = new File(appDataDir, "BepInEx");
        File dotnetDir = new File(appDataDir, "dotnet");

        Utilities.extractZipFromAssets(appContext, "BepInEx-arm64.zip", bepInExDir);
        Utilities.extractZipFromAssets(appContext, "dotnet-arm64.zip", dotnetDir);

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
            Log.e(TAG, "Failed to list game native libraries! BepInEx may not work correctly.");
        }

        FusionConfig config = new FusionConfig(
                gameLibDir,
                appLibDir,
                appDataDir.getAbsolutePath(),
                bepInExDir.getAbsolutePath(),
                dotnetDir.getAbsolutePath(),
                copiedData.getAbsolutePath(),
                version,
                useOriginalLibUnity
        );

        return new PreparedFusionState(targetPackage, config);
    }

    private static final class PreparedFusionState {
        private final String targetPackage;
        private final FusionConfig config;

        private PreparedFusionState(String targetPackage, FusionConfig config) {
            this.targetPackage = targetPackage;
            this.config = config;
        }
    }

    private void loadNativeLibraries() {
        if (!nativeLibsLoaded.compareAndSet(false, true)) {
            return;
        }

        System.loadLibrary("System.Native");
        System.loadLibrary("System.Globalization.Native");
        System.loadLibrary("System.IO.Compression.Native");
        System.loadLibrary("System.Security.Cryptography.Native.Android");
        System.loadLibrary("clrjit");
        System.loadLibrary("mscordbi");
        System.loadLibrary("mscordaccore");
        System.loadLibrary("coreclr");
        System.loadLibrary("fusion");
    }
}
