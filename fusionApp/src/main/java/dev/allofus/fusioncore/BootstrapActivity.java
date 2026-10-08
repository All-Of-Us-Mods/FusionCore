package dev.allofus.fusioncore;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import dev.allofus.fusioncore.hooks.ClassHooks;
import dev.allofus.fusioncore.hooks.ClassLoaderHooks;
import dev.allofus.fusioncore.hooks.InstrumentationHooks;
import dev.allofus.fusioncore.hooks.PackageManagerHooks;
import dev.allofus.fusioncore.hooks.ResourceHooks;
import dev.allofus.fusioncore.hooks.UnityPlayerHooks;
import dev.allofus.fusioncore.tools.FusionConfig;
import dev.allofus.fusioncore.tools.LibUnityDownloader;
import dev.allofus.fusioncore.tools.NativeLibraryManager;
import dev.allofus.fusioncore.tools.Utilities;
import dev.allofus.fusioncore.tools.VersionLookup;

public class BootstrapActivity extends AppCompatActivity {

    private static final String TAG = "FusionCore";

    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_USE_ORIGINAL_LIBUNITY = "og_libunity";
    public static final String EXTRA_USE_IL2CPP2MONO = "use_il2cpp2mono";
    public static final String BACKUP_UNITY_VERSION = "2017.0.0";

    private TextView statusView;
    private TextView progressDetailsView;
    private ProgressBar spinnerProgress;
    private ProgressBar downloadProgress;

    private File pendingGameDir;
    private CountDownLatch pendingLatch;
    private boolean[] pendingResultHolder;

    private final ActivityResultLauncher<Intent> selectZipLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                File targetDir = pendingGameDir;
                CountDownLatch latch = pendingLatch;
                boolean[] resultHolder = pendingResultHolder;

                pendingGameDir = null;
                pendingLatch = null;
                pendingResultHolder = null;

                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    Uri uri = result.getData().getData();
                    setPhaseStatus(getString(R.string.bootstrap_status_extracting_mono_dlls));

                    new Thread(() -> {
                        boolean extracted = extractManagedZipUri(uri, targetDir);
                        if (resultHolder != null) {
                            resultHolder[0] = extracted;
                        }
                        if (latch != null) {
                            latch.countDown();
                        }
                    }, "extract-managed-zip").start();
                } else {
                    if (resultHolder != null) {
                        resultHolder[0] = false;
                    }
                    if (latch != null) {
                        latch.countDown();
                    }
                }
            });

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
        Context gameContext;
        try {
            gameContext = createPackageContext(targetPackage, CONTEXT_IGNORE_SECURITY | CONTEXT_INCLUDE_CODE);
        } catch (Exception e) {
            failAndFinish("Failed to create package context for target package: " + targetPackage, e);
            return;
        }

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(targetPackage);
        if (launchIntent == null) {
            failAndFinish("No launch intent for target package: " + targetPackage, null);
            return;
        }

        ComponentName launcherComponent = launchIntent.getComponent();
        if (launcherComponent == null) {
            launcherComponent = launchIntent.resolveActivity(getPackageManager());
        }

        var overrideActivity = FusionSettings.getActivityOverrideForGame(this, targetPackage);
        try {
            if (!overrideActivity.equals(getString(R.string.settings_automatic))) {
                var overrideClass = gameContext.getClassLoader().loadClass(overrideActivity);
                if (overrideClass != null) {
                    launcherComponent = new ComponentName(targetPackage, overrideActivity);
                    Log.i(TAG, "Using override activity " + overrideActivity);
                    runOnUiThread(() -> Toast.makeText(this, "Using override activity " + overrideActivity, Toast.LENGTH_LONG).show());
                } else {
                    Log.i(TAG, "Failed to find override activity " + overrideActivity);
                    runOnUiThread(()-> Toast.makeText(this, "Failed to find override activity.", Toast.LENGTH_LONG).show());
                }
            }
        } catch (Exception e) {
            runOnUiThread(()-> Toast.makeText(this, "Exception when finding override activity.", Toast.LENGTH_LONG).show());
            Log.e(TAG, "Failed to get override activity "+ overrideActivity, e);
        }

        if (launcherComponent == null) {
            failAndFinish("Failed to resolve launcher activity for target package: " + targetPackage, null);
            return;
        }

        final int targetOrientation = resolveTargetOrientation(launcherComponent);

        boolean useOriginalLibUnity = getIntent().getBooleanExtra(EXTRA_USE_ORIGINAL_LIBUNITY, false);
        boolean useIl2Cpp2Mono = getIntent().getBooleanExtra(EXTRA_USE_IL2CPP2MONO, false);
        FusionConfig config;

        try {
            config = prepareConfig(
                    getApplicationContext(),
                    gameContext,
                    launcherComponent,
                    targetPackage,
                    useOriginalLibUnity,
                    useIl2Cpp2Mono
            );
        } catch (Throwable t) {
            failAndFinish("Failed while preparing Fusion runtime.", t);
            return;
        }

        Class<?> launcherClass;
        try {
            launcherClass = gameContext.getClassLoader().loadClass(launcherComponent.getClassName());
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Failed to get class for launcher activity!");
            return;
        }

        setPhaseStatus(getString(R.string.bootstrap_status_installing_hooks));
        try {
            ClassLoaderHooks.installHooks(gameContext.getClassLoader());
            ClassHooks.installHooks(gameContext.getClassLoader());
            PackageManagerHooks.installHooks(getPackageManager());
            InstrumentationHooks.install(getApplicationContext());
            UnityPlayerHooks.installHooks(gameContext);
            ResourceHooks.installHooks(gameContext.getResources(), getApplicationContext().getResources());
        } catch (Exception e) {
            Log.e(TAG, "Failed to install base hooks", e);
        }

        var className = launcherComponent.getClassName();

        try {
            setPhaseStatus(getString(R.string.bootstrap_status_launching));
            initializeFusion(config);
            runOnMainThread(() -> {
                try {
                    var intent = new Intent(this, launcherClass);

                    // Using the stub activity intent here avoids one extra layer of hooks running.
                    // Its not necessary but could be more performant.
                    var intentWrapped = new Intent(this, StubActivity.class);
                    intentWrapped.putExtra(InstrumentationHooks.EXTRA_IS_DYNAMIC_ACTIVITY, true);
                    intentWrapped.putExtra(InstrumentationHooks.EXTRA_ORIGINAL_INTENT, intent);
                    intentWrapped.putExtra(InstrumentationHooks.EXTRA_FUSION_CONFIG, config);
                    intentWrapped.putExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION, targetOrientation);
                    startActivity(intentWrapped);
                    finish();
                } catch (Throwable t) {
                    failAndFinish("Failed to launch target app's launcher activity: " + className, t);
                }
            });
        } catch (Exception e) {
            failAndFinish("Failed to launch target app's launcher activity: " + className, e);
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

    private void initializeFusion(FusionConfig config) {
        Log.i(TAG, "Initializing Fusion for " + config.gamePackageId + " via " + config.gameLauncherName);

        try {
            NativeLibraryManager.addFusionLibrary("main");
            NativeLibraryManager.addFusionLibrary("fusion");
            if(!config.isIl2Cpp2Mono) {
                NativeLibraryManager.addCacheLibrary("il2cpp");
            }
            else {
                NativeLibraryManager.AddDotnetLibrary("il2cpp");
                NativeLibraryManager.AddDotnetLibrary("monosgen-2.0");
            }
            NativeLibraryManager.addCacheLibrary("unity");
            NativeLibraryManager.setupLibraryHooks(config);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize Fusion in launcher beforeCall", t);
        }
    }

    private FusionConfig prepareConfig(Context appContext,
                                       Context gameContext,
                                       ComponentName launcherComponent,
                                       String targetPackage,
                                       boolean useOriginalLibUnity,
                                       boolean useIl2Cpp2Mono) {

        String gameLibDir = gameContext.getApplicationInfo().nativeLibraryDir;
        String appLibDir = appContext.getApplicationInfo().nativeLibraryDir;

        String targetGameAbi = resolveTargetGameAbi(gameLibDir);
        File appDataDir = new File(appContext.getFilesDir(), targetPackage);

        File dataOnSdCard = Utilities.getExternalFusionCoreDirectory(targetPackage, null);
        File codeCacheScoped = new File(appContext.getCodeCacheDir(), targetPackage);

        setPhaseStatus(getString(R.string.bootstrap_status_copy_assets));
        File Assets = new File(dataOnSdCard, "Assets");
        File PersistentData = new File(dataOnSdCard, "PersistentData");
        boolean copied = Utilities.copyAssets(gameContext.getAssets(), "", Assets);
        if (!copied) {
            Log.e(TAG, "Failed to copy Unity Data assets! BepInEx may not work correctly.");
        }
        File copiedData = new File(Assets, "bin/Data");

        setPhaseStatus(getString(R.string.bootstrap_status_detecting_version));
        String version = VersionLookup.TryLookup(copiedData);
        if (version == null) {
            Log.e(TAG, "Failed to determine Unity version! BepInEx may not work correctly.");
            version = BACKUP_UNITY_VERSION;
            useOriginalLibUnity = true;
        } else if (useOriginalLibUnity) {
            Log.i(TAG, "Skipping libunity download");
        } else {
            Log.i(TAG, "Determined Unity version: " + version);
            if (LibUnityDownloader.downloadAndCacheSafely(codeCacheScoped, version, targetGameAbi, new LibUnityDownloader.DownloadProgressListener() {
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
                    // No-op: next phase will handle this.
                }
            })) {
                Log.i(TAG, "Successfully downloaded libunity for version " + version + " and ABI " + targetGameAbi);
            } else {
                Log.e(TAG, "Failed to download libunity for version " + version + " and ABI " + targetGameAbi + ", falling back to original.");
                useOriginalLibUnity = true;
            }
        }

        setPhaseStatus(getString(R.string.bootstrap_status_extracting_runtime));
        File dotnetDir;
        File bepInExDir = new File(dataOnSdCard, "BepInEx");
        if(!useIl2Cpp2Mono) {
            dotnetDir = new File(appContext.getCodeCacheDir(), "dotnet");
            Utilities.extractZipFromAssets(appContext, "BepInEx-arm64.zip", bepInExDir);
            Utilities.extractZipFromAssets(appContext, "dotnet-arm64.zip", dotnetDir);
        } //we do something else
        else{
            dotnetDir = new File(appContext.getCodeCacheDir(), "mono");
            Utilities.extractZipFromAssets(appContext, "il2cpp2mono-arm64.zip", dotnetDir);
            ensureManagedDllsForMono(PersistentData);
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
            Log.e(TAG, "Failed to list game native libraries! BepInEx may not work correctly.");
        }

        return new FusionConfig(
                targetPackage,
                launcherComponent.flattenToString(),
                gameLibDir,
                appLibDir,
                appDataDir.getAbsolutePath(),
                codeCacheScoped.getAbsolutePath(),
                bepInExDir.getAbsolutePath(),
                dotnetDir.getAbsolutePath(),
                copiedData.getAbsolutePath(),
                version,
                useOriginalLibUnity,
                useIl2Cpp2Mono,
                new String[]{},
                new String[]{}
        );
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

    private void ensureManagedDllsForMono(File GameDir) {
        File managedDir = new File(GameDir, "mono");
        if (managedDir.exists()) {
            Log.i(TAG, "Managed DLLs already present in " + managedDir.getAbsolutePath() + ", skipping selection prompt.");
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};

        runOnMainThread(() -> {
            if (isFinishing() || isDestroyed()) {
                latch.countDown();
                return;
            }

            promptSelectManagedZip(GameDir, latch, success);
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Managed DLLs selection", e);
        }

        if (!success[0]) {
            throw new IllegalStateException(getString(R.string.bootstrap_mono_missing_dlls));
        }
    }

    private void promptSelectManagedZip(File GameDir, CountDownLatch latch, boolean[] resultHolder) {
        pendingGameDir = GameDir;
        pendingLatch = latch;
        pendingResultHolder = resultHolder;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        String[] mimeTypes = {"application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);

        try {
            selectZipLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch zip picker", e);
            Toast.makeText(this, R.string.bootstrap_mono_file_picker_error, Toast.LENGTH_LONG).show();
            pendingGameDir = null;
            pendingLatch = null;
            pendingResultHolder = null;
            resultHolder[0] = false;
            latch.countDown();
        }
    }

    private boolean extractManagedZipUri(Uri uri, File bepInExDir) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                Log.e(TAG, "Failed to open InputStream from Uri: " + uri);
                return false;
            }
            File managedDir = new File(bepInExDir, "mono");
            Utilities.extractZipFromInputStream(is, managedDir);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract managed zip from Uri: " + uri, e);
            return false;
        }
    }

    private int resolveTargetOrientation(ComponentName launcher) {
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(launcher, 0);
            if (info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                Log.i(TAG, "Target orientation unspecified; defaulting to landscape");
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            }
            return info.screenOrientation;
        } catch (NameNotFoundException e) {
            return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
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
