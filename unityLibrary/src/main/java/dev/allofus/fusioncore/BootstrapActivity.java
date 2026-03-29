package dev.allofus.fusioncore;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class BootstrapActivity extends Activity {
    private static final String TAG = "FusionCore";

    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_USE_ORIGINAL_LIBUNITY = "og_libunity";
    public static final String BACKUP_UNITY_VERSION = "2017.0.0";

    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean FUSION_INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean NATIVE_LIBS_LOADED = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String targetPackage = getIntent().getStringExtra(EXTRA_TARGET_PACKAGE);
        if (targetPackage == null || targetPackage.isEmpty()) {
            Log.e(TAG, "No target package specified in intent extras!");
            finish();
            return;
        }

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(targetPackage);
        if (launchIntent == null) {
            Log.e(TAG, "No launch intent for target package: " + targetPackage);
            finish();
            return;
        }

        ComponentName launcher = launchIntent.getComponent();
        if (launcher == null) {
            launcher = launchIntent.resolveActivity(getPackageManager());
        }

        if (launcher == null) {
            Log.e(TAG, "Failed to resolve launcher activity for target package: " + targetPackage);
            finish();
            return;
        }

        Context gameContext;
        try {
            gameContext = createPackageContext(targetPackage, CONTEXT_IGNORE_SECURITY | CONTEXT_INCLUDE_CODE);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create package context for target package: " + targetPackage, e);
            finish();
            return;
        }

        try {
            ClassLoaderHooks.installHooks(gameContext.getClassLoader());
            PackageManagerHooks.installHooks(getPackageManager());
            UnityPlayerHooks.installHooks(gameContext);
        } catch (Exception e) {
            Log.e(TAG, "Failed to install base hooks", e);
        }

        final String launcherClassName = launcher.getClassName();
        final boolean useOriginalLibUnity = getIntent().getBooleanExtra(EXTRA_USE_ORIGINAL_LIBUNITY, false);

        if (!installLauncherOnCreateHook(gameContext, launcherClassName,
                (launcherActivity, bundle) -> initializeFusion(launcherActivity, gameContext, targetPackage, useOriginalLibUnity))) {
            finish();
            Toast.makeText(this, "Failed to install launcher hook! See log for details.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            var launcherClass = gameContext.getClassLoader().loadClass(launcherClassName);

            var intent = new Intent(this, launcherClass);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);

            finish();
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch target app's launcher activity: " + launcherClassName, e);
            Toast.makeText(this, "Failed to launch target app! See log for details.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private interface BeforeOnCreateAction {
        void run(Activity launcherActivity, Bundle bundle);
    }

    private static boolean installLauncherOnCreateHook(Context gameContext, String launcherClassName, BeforeOnCreateAction action) {
        if (HOOK_INSTALLED.get()) {
            return true;
        }

        try {
            Class<?> launcherClass = Class.forName(launcherClassName, false, gameContext.getClassLoader());
            Method onCreateMethod = findOnCreateMethod(launcherClass);
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

            HOOK_INSTALLED.set(true);
            Log.i(TAG, "Installed launcher onCreate hook for " + launcherClassName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to install launcher onCreate hook for " + launcherClassName, e);
            return false;
        }
    }

    private static Method findOnCreateMethod(Class<?> clazz) throws NoSuchMethodException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredMethod("onCreate", Bundle.class);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }

        throw new NoSuchMethodException("onCreate(Bundle) not found for " + clazz.getName());
    }

    private static void initializeFusion(Activity launcherActivity, Context gameContext, String targetPackage, boolean useOriginalLibUnity) {
        if (!FUSION_INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        try {
            String gameLibDir = gameContext.getApplicationInfo().nativeLibraryDir;
            String appLibDir = launcherActivity.getApplicationInfo().nativeLibraryDir;

            final File appDataDir = new File(launcherActivity.getFilesDir(), targetPackage);

            File copiedData = new File(appDataDir, "Data_copy");
            boolean copied = copyAssets(gameContext.getAssets(), "bin/Data", copiedData);
            if (!copied) {
                Log.e(TAG, "Failed to copy Unity Data assets! BepInEx may not work correctly.");
            }

            String version = VersionLookup.TryLookup(copiedData);
            if (version == null) {
                Log.e(TAG, "Failed to determine Unity version! BepInEx may not work correctly.");
                version = BACKUP_UNITY_VERSION;
                useOriginalLibUnity = true;
            } else {
                Log.i(TAG, "Determined Unity version: " + version);
                if (LibUnityDownloader.downloadAndCacheSafely(launcherActivity.getFilesDir(), version)) {
                    Log.i(TAG, "Successfully downloaded libunity for version " + version);
                } else {
                    Log.e(TAG, "Failed to download libunity for version " + version + ", falling back to original.");
                    useOriginalLibUnity = true;
                }
            }

            File bepInExDir = new File(appDataDir, "BepInEx");
            File dotnetDir = new File(appDataDir, "dotnet");

            extractZipFromAssets(launcherActivity, "BepInEx-arm64.zip", bepInExDir);
            extractZipFromAssets(launcherActivity, "dotnet-arm64.zip", dotnetDir);

            FusionConfig config = new FusionConfig(
                    gameLibDir,
                    appLibDir,
                    appDataDir.getAbsolutePath(),
                    launcherActivity.getFilesDir().getAbsolutePath(),
                    bepInExDir.getAbsolutePath(),
                    dotnetDir.getAbsolutePath(),
                    copiedData.getAbsolutePath(),
                    version,
                    useOriginalLibUnity
            );

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

    private static boolean copyAssets(AssetManager gameAssets, String assetPath, File outputFolder) {
        deleteRecursive(outputFolder);

        try {
            if (!copyAssetEntry(gameAssets, assetPath, outputFolder)) {
                Log.e(TAG, "Could not find Unity Data assets!");
                return false;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy Unity Data assets!", e);
            return false;
        }

        return true;
    }

    private static boolean copyAssetEntry(AssetManager gameAssets, String assetPath, File outputTarget) throws IOException {
        String[] children = gameAssets.list(assetPath);
        if (children == null) {
            return false;
        }

        if (children.length > 0) {
            if (!outputTarget.exists() && !outputTarget.mkdirs()) {
                return false;
            }

            for (String child : children) {
                File childTarget = new File(outputTarget, child);
                String childPath = assetPath + "/" + child;
                if (!copyAssetEntry(gameAssets, childPath, childTarget)) {
                    return false;
                }
            }
            return true;
        }

        File parent = outputTarget.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }

        byte[] buffer = new byte[8192];
        try (InputStream is = gameAssets.open(assetPath);
             OutputStream os = new FileOutputStream(outputTarget)) {
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        }

        return true;
    }

    private static boolean deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!deleteRecursive(f)) {
                        return false;
                    }
                }
            }
        }

        return file.delete();
    }

    private static void loadNativeLibraries() {
        if (!NATIVE_LIBS_LOADED.compareAndSet(false, true)) {
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

    private static void extractZipFromAssets(Context context, String assetName, File outputFolder) {
        try {
            if (!outputFolder.exists() && !outputFolder.mkdirs()) {
                throw new IOException("Failed to create output directory: " + outputFolder.getAbsolutePath());
            }

            String outputRoot = outputFolder.getCanonicalPath() + File.separator;
            byte[] buffer = new byte[8192];

            try (InputStream is = context.getAssets().open(assetName);
                 ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is))) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    String entryName = ze.getName();
                    if (entryName == null || entryName.isEmpty()) {
                        zis.closeEntry();
                        continue;
                    }

                    File target = new File(outputFolder, entryName);
                    String targetPath = target.getCanonicalPath();

                    if (!targetPath.startsWith(outputRoot)) {
                        throw new IOException("Blocked zip entry outside output folder: " + entryName);
                    }

                    if (ze.isDirectory()) {
                        if (!target.exists() && !target.mkdirs()) {
                            throw new IOException("Failed to create directory: " + targetPath);
                        }
                    } else {
                        File parent = target.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw new IOException("Failed to create parent directory: " + parent.getAbsolutePath());
                        }

                        try (FileOutputStream fos = new FileOutputStream(target)) {
                            int count;
                            while ((count = zis.read(buffer)) != -1) {
                                fos.write(buffer, 0, count);
                            }
                        }
                    }

                    zis.closeEntry();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract " + assetName + " from assets!", e);
        }
    }
}
