package dev.allofus.fusioncore;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import dev.allofus.fusioncore.hooks.InstrumentationHooks;
import dev.allofus.fusioncore.hooks.ActivityManagerHooks;
import dev.allofus.fusioncore.hooks.PackageManagerHooks;
import dev.allofus.fusioncore.hooks.GameRuntime;
import dev.allofus.fusioncore.tools.FusionConfig;
import dev.allofus.fusioncore.tools.GlobalMetadata;
import dev.allofus.fusioncore.tools.Il2CppApiMapper;
import dev.allofus.fusioncore.tools.LibUnityDownloader;
import dev.allofus.fusioncore.tools.NativeLibraryManager;
import dev.allofus.fusioncore.tools.Utilities;
import dev.allofus.fusioncore.tools.UnityUtils;

public class BootstrapActivity extends AppCompatActivity {

    private static final String TAG = "FusionCore";

    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_USE_ORIGINAL_LIBUNITY = "og_libunity";
    public static final String EXTRA_USE_IL2CPP2MONO = "use_il2cpp2mono";
    public static final String BACKUP_UNITY_VERSION = "2017.0.0";
    public static final String IL2CPP_API_MAP = "il2cpp-api.map";
    private TextView statusView;
    private TextView progressDetailsView;
    private ProgressBar spinnerProgress;
    private ProgressBar downloadProgress;
    private final AtomicReference<Uri> selectedFileUri = new AtomicReference<>(null);
    private volatile CountDownLatch filePickerLatch;
    private final ActivityResultLauncher<Intent> selectFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                try {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                        Uri uri = result.getData().getData();
                        selectedFileUri.set(uri);
                    } else {
                        selectedFileUri.set(null);
                    }
                } finally {
                    if (filePickerLatch != null) {
                        filePickerLatch.countDown();
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

        try {
            gameContext = GameRuntime.prepareGameContext(gameContext, getClassLoader());
        } catch (Exception e) {
            failAndFinish("Failed to configure classes.", e);
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
            PackageManagerHooks.install(getApplicationContext(), gameContext);
            ActivityManagerHooks.install(getApplicationContext(), gameContext);
            GameRuntime.install(gameContext);
        } catch (Exception e) {
            failAndFinish("Failed to install base hooks", e);
            return;
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
                NativeLibraryManager.addDotnetLibrary("il2cpp");
                NativeLibraryManager.addDotnetLibrary("monosgen-2.0");
            }
            NativeLibraryManager.addCacheLibrary("unity");
            NativeLibraryManager.setupLibraryHooks(config);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to initialize Fusion library routing", t);
        }
    }

    private Map<String, String> resolveIl2CppApiMap(File gameLibDir, File codeCacheScoped,
                                                    boolean useOriginalLibUnity, File DeobfuscationDir) {
        File gameLibIl2Cpp = new File(gameLibDir, "libil2cpp.so");
        File cachedLibUnity = new File(codeCacheScoped, "libunity.so");

        if (!useOriginalLibUnity) {
            try {
                Map<String, String> map = Il2CppApiMapper.prepare(
                        new File(gameLibDir, "libunity.so"), gameLibIl2Cpp, cachedLibUnity);
                if (!map.isEmpty()) {
                    return map;
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to map obfuscated il2cpp exports automatically", e);
            }
        }

        try {
            if (!Il2CppApiMapper.hasObfuscatedExports(gameLibIl2Cpp)) {
                return new HashMap<>();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read il2cpp exports", e);
            return new HashMap<>();
        }

        File mapFile = new File(DeobfuscationDir, IL2CPP_API_MAP);
        if (!mapFile.isFile()) {
            Uri selected = promptForFile(R.string.boostrap_file_request_il2cpp_map, "map");
            if (selected == null) {
                runOnMainThread(() -> Toast.makeText(this, "WARNING: no il2cpp api deobfuscation map selected. are you sure you know what you are doing?", Toast.LENGTH_LONG).show());
                return new HashMap<>();
            }
            try {
                copyUriToFile(selected, mapFile);
            } catch (IOException e) {
                Log.e(TAG, "Couldn't copy the deobfuscated il2cpp api map!", e);
                return new HashMap<>();
            }
        }

        Map<String, String> map = GetIL2CPPMap(DeobfuscationDir);
        if (!useOriginalLibUnity && !map.isEmpty()) {
            try {
                Il2CppApiMapper.patchLibUnity(cachedLibUnity, map);
            } catch (IOException e) {
                Log.e(TAG, "Failed to apply il2cpp api map to libunity", e);
            }
        }
        return map;
    }

    private void applyGlobalMetadataOverride(String targetPackage, File copiedData) {
        if (!GlobalMetadata.hasValidOverride(targetPackage)) {
            return;
        }
        File target = new File(copiedData, "Managed/Metadata/" + GlobalMetadata.FILE_NAME);
        try (InputStream in = new FileInputStream(GlobalMetadata.getOverrideFile(targetPackage));
             FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            Log.i(TAG, "Applied global-metadata override to " + target.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to apply global-metadata override", e);
        }
    }

    private static File getRuntimeDir(Context context) {
        return context.getDir("fusion_runtime", Context.MODE_PRIVATE);
    }

    HashMap<String, String> GetIL2CPPMap(File DeobfuscationDir){
        File MapFile = new File(DeobfuscationDir, IL2CPP_API_MAP);
        HashMap<String, String> map = new HashMap<>();
        if (!MapFile.exists() || !MapFile.isFile()) {
            return map;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(MapFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                    continue;
                }
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String normal = line.substring(0, colonIndex).trim();
                    String deobfuscated = line.substring(colonIndex + 1).trim();
                    if (!normal.isEmpty() && !deobfuscated.isEmpty()) {
                        map.put(normal, deobfuscated);
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to parse IL2CPP API map file: " + MapFile.getAbsolutePath(), e);
        }
        return map;
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
        File bepInExDir = new File(dataOnSdCard, "BepInEx");
        File codeCacheScoped = new File(getRuntimeDir(appContext), targetPackage);

        setPhaseStatus(getString(R.string.bootstrap_status_copy_assets));
        File Assets = new File(dataOnSdCard, "Assets");
        File PersistentData = new File(dataOnSdCard, "PersistentData");
        boolean copied = Utilities.copyAssets(gameContext.getApplicationInfo(), Assets);
        if (!copied) {
            Log.e(TAG, "Failed to copy Unity Data assets! BepInEx may not work correctly.");
        }
        File copiedData = new File(Assets, "bin/Data");
        applyGlobalMetadataOverride(targetPackage, copiedData);
        File DeobfuscationDir = new File(bepInExDir, "Deobfuscation");
        setPhaseStatus(getString(R.string.bootstrap_status_detecting_version));
        String version = UnityUtils.TryGetVersion(copiedData);
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
        if(!useIl2Cpp2Mono) {
            dotnetDir = new File(getRuntimeDir(appContext), "dotnet");
            Utilities.extractZipFromAssets(appContext, "BepInEx-arm64.zip", bepInExDir);
            Utilities.extractZipFromAssets(appContext, "dotnet-arm64.zip", dotnetDir);
        } //we do something else
        else{
            dotnetDir = new File(getRuntimeDir(appContext), "mono");
            Utilities.extractZipFromAssets(appContext, "il2cpp2mono-arm64.zip", dotnetDir);
            ensureManagedDllsForMono(PersistentData);
        }

        Map<String, String> il2cppMap = useIl2Cpp2Mono
                ? GetIL2CPPMap(DeobfuscationDir)
                : resolveIl2CppApiMap(new File(gameLibDir), codeCacheScoped, useOriginalLibUnity, DeobfuscationDir);

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
                new String[]{},
                il2cppMap
        );
    }

    private void copyUriToFile(Uri sourceUri, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create parent directory: " + parent.getAbsolutePath());
        }

        try (InputStream in = getContentResolver().openInputStream(sourceUri);
             FileOutputStream out = new FileOutputStream(target, false)) {
            if (in == null) {
                throw new IOException("Unable to open input stream for URI: " + sourceUri);
            }
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
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

    private void ensureManagedDllsForMono(File GameDir) {
        File managedDir = new File(GameDir, "mono");
        File[] existingFiles = managedDir.listFiles();
        if (managedDir.exists() && existingFiles != null && existingFiles.length > 0) {
            Log.i(TAG, "Managed DLLs already present in " + managedDir.getAbsolutePath() + ", skipping selection prompt.");
            return;
        }

        Uri zipUri = promptForFile(R.string.bootstrap_request_dlls, "zip");
        if (zipUri == null) {
            throw new IllegalStateException("No Managed Zip file selected!");
        }

        setPhaseStatus(getString(R.string.bootstrap_status_extracting_mono_dlls));
        if (!extractZipFile(zipUri, managedDir)) {
            throw new IllegalStateException("Invalid or corrupted Managed Zip file provided!");
        }
    }

    public Uri promptForFile(int messageResId, @Nullable String extension) {
        filePickerLatch = new CountDownLatch(1);
        selectedFileUri.set(null);

        final String cleanExt = extension != null ? extension.trim().toLowerCase(Locale.ROOT).replace(".", "") : "";

        runOnMainThread(() -> {
            if (isFinishing() || isDestroyed()) {
                if (filePickerLatch != null) {
                    filePickerLatch.countDown();
                }
                return;
            }
            Toast.makeText(this, messageResId, Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);

            if (!cleanExt.isEmpty()) {
                String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(cleanExt);
                intent.setType(Objects.requireNonNullElse(mimeType, "*/*"));
            } else {
                intent.setType("*/*");
            }
            try {
                selectFileLauncher.launch(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch file picker for extension: " + extension, e);
                if (filePickerLatch != null) {
                    filePickerLatch.countDown();
                }
            }
        });

        try {
            filePickerLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Interrupted while waiting for file picker result", e);
            return null;
        }

        Uri resultUri = selectedFileUri.get();
        if (resultUri != null && !cleanExt.isEmpty()) {
            String fileName = getFileNameFromUri(resultUri);
            if (fileName != null && !fileName.toLowerCase(Locale.ROOT).endsWith("." + cleanExt)) {
                Log.w(TAG, "Selected file '" + fileName + "' does not match required extension: ." + cleanExt);
                runOnMainThread(() -> Toast.makeText(this, "Selected file must be a ." + cleanExt + " file", Toast.LENGTH_LONG).show());
                return null;
            }
        }

        return resultUri;
    }

    private String getFileNameFromUri(Uri uri) {
        if (uri == null) return null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1) {
                        String name = cursor.getString(index);
                        if (name != null && !name.isEmpty()) {
                            return name;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to query display name for URI: " + uri, e);
            }
        }
        String path = uri.getPath();
        if (path != null) {
            int cut = path.lastIndexOf('/');
            if (cut != -1) {
                return path.substring(cut + 1);
            }
            return path;
        }
        return null;
    }

    private boolean extractZipFile(Uri uri, File Target) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                Log.e(TAG, "openInputStream returned null for URI: " + uri);
                return false;
            }
            Utilities.extractZipFromInputStream(is, Target);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract zip from URI: " + uri, e);
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
