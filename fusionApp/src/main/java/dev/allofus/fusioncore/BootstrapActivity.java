package dev.allofus.fusioncore;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class BootstrapActivity extends Activity {

    private static final String TAG = "FusionCore";

    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_USE_ORIGINAL_LIBUNITY = "og_libunity";
    public static final String BACKUP_UNITY_VERSION = "2017.0.0";
    private static final String GLOBAL_METADATA_FILE = "global-metadata.dat";

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
        if (!installLauncherOnCreateHook(gameContext.getClassLoader(), launcherClassName,
                (launcherActivity, bundle) -> initializeFusion(launcherActivity, targetPackage, gameContext))) {
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

    private void initializeFusion(Activity launcherActivity, String targetPackage, Context gameContext) {
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

            // Hook getResources on launcher activity to return game resources.
            // The game's UnityPlayer runs in FusionCore's process so its Activity
            // has FusionCore resources, not the game's resources.
            if (gameContext != null && launcherActivity != null) {
                installGameResourceHooks(launcherActivity, gameContext);
            }

            // Ensure writable directories exist for Unity's persistentDataPath, cache, etc.
            // When the game runs in FusionCore's process, Unity resolves paths under
            // FusionCore's data dir. If these don't exist, statvfs returns 0 and Unity
            // reports "not enough storage space to install required resource".
            ensureGameDirectories(config, launcherActivity, targetPackage);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize Fusion in launcher beforeCall", t);
        }
    }

    // SafeResources: wraps game resources and prevents crash when Unity calls getString(0)/getText(0).
    // This avoids Pine hooks for getString/getText entirely, eliminating the SIGSEGV in ART
    // caused by Pine shadow frames interacting with exception stack trace creation.
    private static class SafeResources extends Resources {
        private static final String TAG = "FusionCore";
        private final Resources delegate;

        SafeResources(Resources delegate) {
            super(delegate.getAssets(), delegate.getDisplayMetrics(), delegate.getConfiguration());
            this.delegate = delegate;
        }

        @Override
        public String getString(int id) throws NotFoundException {
            if (id == 0) {
                Log.w(TAG, "SafeResources.getString(0) intercepted, returning empty string");
                return "";
            }
            return delegate.getString(id);
        }

        @Override
        public CharSequence getText(int id) throws NotFoundException {
            if (id == 0) {
                Log.w(TAG, "SafeResources.getText(0) intercepted, returning empty string");
                return "";
            }
            return delegate.getText(id);
        }
    }

    private void installGameResourceHooks(Activity launcherActivity, Context gameContext) {
        Resources gameResources = gameContext.getResources();
        SafeResources safeResources = new SafeResources(gameResources);

        // Primary approach: set mResources directly on the Activity via reflection.
        // This avoids ANY Pine hooks for resource handling (no shadow frames on the stack).
        try {
            Field resField = ContextThemeWrapper.class.getDeclaredField("mResources");
            resField.setAccessible(true);
            resField.set(launcherActivity, safeResources);
            Log.i(TAG, "Set SafeResources directly on activity: " + launcherActivity.getClass().getName());
            return;
        } catch (Exception e) {
            Log.w(TAG, "Direct reflection failed, falling back to Pine hook for getResources", e);
        }

        // Fallback: Pine hook for getResources (still returns SafeResources, still NO getString/getText hooks).
        try {
            Method getResourcesMethod = ContextThemeWrapper.class.getDeclaredMethod("getResources");
            Pine.hook(getResourcesMethod, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    if (callFrame.thisObject == launcherActivity) {
                        callFrame.setResult(safeResources);
                    }
                }
            });
            Log.i(TAG, "Installed getResources Pine hook (fallback) for " + launcherActivity.getClass().getName());
        } catch (NoSuchMethodException e2) {
            Log.e(TAG, "Failed to install any resource handling", e2);
        }
    }

    private void ensureGameDirectories(FusionConfig config, Activity activity, String targetPackage) {
        // Log key Unity path resolution points for diagnosis
        try {
            Log.i(TAG, "Game filesDir: " + activity.getFilesDir().getAbsolutePath());
            Log.i(TAG, "Game cacheDir: " + activity.getCacheDir().getAbsolutePath());
            Log.i(TAG, "Game appDataDir: " + config.appDataDirectory);
        } catch (Exception e) {
            Log.w(TAG, "Failed to log game paths", e);
        }

        // Create directories Unity expects for persistentDataPath and cache.
        // If these don't exist, statvfs returns 0 and Unity shows "not enough storage".
        String[][] dirSets = {
            {"Unity", "UnityCache", "cache", "tmp", "Download"},
            {"Unity", "UnityCache"},
        };

        for (String[] dirs : dirSets) {
            for (String dir : dirs) {
                try {
                    File d = new File(activity.getFilesDir(), dir);
                    if (d.mkdirs() || d.isDirectory()) {
                        Log.d(TAG, "Ensured dir: " + d.getAbsolutePath());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not create dir: " + dir, e);
                }
            }
        }

        // Also ensure subdirs in appDataDirectory
        try {
            for (String dir : new String[]{"cache", "tmp", "Download"}) {
                File d = new File(config.appDataDirectory, dir);
                if (d.mkdirs() || d.isDirectory()) {
                    Log.d(TAG, "Ensured data subdir: " + d.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not create data subdirs", e);
        }

        // Try external cache dir - Unity may use it for downloads
        try {
            File extCache = activity.getExternalCacheDir();
            if (extCache != null) {
                Log.i(TAG, "Game externalCacheDir: " + extCache.getAbsolutePath());
                for (String dir : new String[]{"Unity", "UnityCache"}) {
                    File d = new File(extCache, dir);
                    if (d.mkdirs() || d.isDirectory()) {
                        Log.d(TAG, "Ensured external dir: " + d.getAbsolutePath());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not create external cache dirs", e);
        }

        Log.i(TAG, "Game directories ensured for: " + targetPackage);
    }

    private PreparedFusionState prepareFusionState(Context appContext,
            Context gameContext,
            String targetPackage,
            boolean useOriginalLibUnity) {
        String gameLibDir = gameContext.getApplicationInfo().nativeLibraryDir;
        String appLibDir = appContext.getApplicationInfo().nativeLibraryDir;
        String targetGameAbi = resolveTargetGameAbi(gameLibDir);
        File appDataDir = new File(appContext.getFilesDir(), targetPackage);
        File dataOnSdCard = new File(new File(Environment.getExternalStorageDirectory(), "FusionCore"), targetPackage);

        setPhaseStatus(getString(R.string.bootstrap_status_copy_assets));
        File copiedData = new File(appDataDir, "Data_copy");
        boolean copied = Utilities.copyAssets(gameContext.getAssets(), "bin/Data", copiedData);
        if (!copied) {
            Log.e(TAG, "Failed to copy Unity Data assets! BepInEx may not work correctly.");
        } else {
            applyGlobalMetadataOverride(dataOnSdCard, copiedData);
        }

        setPhaseStatus(getString(R.string.bootstrap_status_detecting_version));
        String version = VersionLookup.TryLookup(copiedData);
        if (version == null) {
            Log.e(TAG, "Failed to determine Unity version! BepInEx may not work correctly.");
            version = BACKUP_UNITY_VERSION;
            useOriginalLibUnity = true;
        } else if (useOriginalLibUnity) {
            Log.i(TAG, "Skipping libunity download");
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

        setPhaseStatus(getString(R.string.bootstrap_status_extracting_runtime));
        File dotnetDir = new File(appDataDir, "dotnet");

        File bepInExDir = new File(dataOnSdCard, "BepInEx");

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

    private void applyGlobalMetadataOverride(File dataOnSdCard, File copiedData) {
        File overrideMetadata = new File(dataOnSdCard, GLOBAL_METADATA_FILE);
        if (!overrideMetadata.isFile()) {
            Log.i(TAG, "No global-metadata override found at " + overrideMetadata.getAbsolutePath());
            return;
        }

        File targetMetadata = new File(new File(copiedData, "Managed/Metadata"), GLOBAL_METADATA_FILE);
        try {
            copyFile(overrideMetadata, targetMetadata);
            Log.i(TAG, "Applied global-metadata override from " + overrideMetadata.getAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to apply global-metadata override from "
                    + overrideMetadata.getAbsolutePath(), e);
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create parent directory: " + parent.getAbsolutePath());
        }

        byte[] buffer = new byte[8192];
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target, false)) {
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
    }

    private static final class PreparedFusionState {

        private final String targetPackage;
        private final FusionConfig config;

        private PreparedFusionState(String targetPackage, FusionConfig config) {
            this.targetPackage = targetPackage;
            this.config = config;
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
