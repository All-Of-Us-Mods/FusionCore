package com.unity3d.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import dev.allofus.fusioncore.ActivityBridge;
import dev.allofus.fusioncore.ClassLoaderHooks;
import dev.allofus.fusioncore.PackageManagerHooks;
import dev.allofus.fusioncore.CustomContextWrapper;
import dev.allofus.fusioncore.FusionConfig;
import dev.allofus.fusioncore.LibUnityDownloader;
import dev.allofus.fusioncore.NativeLibraryManager;
import dev.allofus.fusioncore.VersionLookup;

public class UnityPlayerActivity extends Activity implements IUnityPlayerLifecycleEvents
{
    public static final String TAG = "FusionCore";

    //public static final String TARGET_GAME = "com.innersloth.spacemafia";
    //public static final String TARGET_GAME = "com.abstractsoft.hybridanimals";
    public static final String TARGET_GAME = "air.com.midjiwan.polytopia";

    protected UnityPlayer mUnityPlayer; // don't change the name of this variable; referenced from native code
    public Context m_context;

    protected String updateUnityCommandLineArguments(String cmdLine)
    {
        return cmdLine;
    }

    // Setup activity layout
    @Override protected void onCreate(Bundle savedInstanceState)
    {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        String cmdLine = updateUnityCommandLineArguments(getIntent().getStringExtra("unity"));
        getIntent().putExtra("unity", cmdLine);

        // ---------- FUSION CORE -------------

        Context wrappedContext = null;
        try {
            Context gameContext = createPackageContext(TARGET_GAME, CONTEXT_IGNORE_SECURITY);
            m_context = gameContext;

            setupGameClassLoader(gameContext);

            try {
                // setup classloader hooks for better game compat
                ClassLoaderHooks.installHooks(gameContext.getClassLoader());

                // setup packagemanager hooks to handle external component management
                PackageManagerHooks.installHooks(getPackageManager());
            } catch (Exception e) {
                Log.e(TAG, "Failed to install hooks!", e);
            }

            boolean useOriginalLibUnity = getIntent().getBooleanExtra("og_libunity", false);

            String gameLibDir = gameContext.getApplicationInfo().nativeLibraryDir;
            String appLibDir = getApplicationInfo().nativeLibraryDir;

            File appDataDir = getExternalFilesDir(null);
            if (appDataDir == null) {
                appDataDir = getFilesDir();
            }

            // use a subfolder for our target game to avoid conflicts with other games using Fusion
            appDataDir = new File(appDataDir, TARGET_GAME);

            // copy the games Data folder so we can determine Unity version and use metadata
            var copiedData = new File(appDataDir, "Data_copy");
            boolean copied = copyAssets(gameContext.getAssets(), "bin/Data", copiedData);
            if (!copied) {
                Log.e(TAG, "Failed to copy Unity Data assets! BepInEx may not work correctly.");
            }

            // lookup unity version
            String version = VersionLookup.TryLookup(copiedData);
            if (version == null) {
                Log.e(TAG, "Failed to determine Unity version! BepInEx may not work correctly.");
                version = "2017.0.0";
                // force using original libunity since we don't know the version
                // and the one provided by Fusion may not be compatible
                useOriginalLibUnity = true;
            } else {
                Log.i(TAG, "Determined Unity version: " + version);
                if (LibUnityDownloader.downloadAndCacheSafely(getFilesDir(), version)) {
                    Log.i(TAG, "Successfully downloaded libunity for version " + version);
                } else {
                    Log.e(TAG, "Failed to download libunity for version " + version + ", falling back to original. BepInEx may not work correctly.");
                    useOriginalLibUnity = true;
                }
            }

            // extract our embedded dependencies
            File bepInExDir = new File(appDataDir, "BepInEx");
            File dotnetDir = new File(appDataDir, "dotnet");

            extractZipFromAssets(this, "BepInEx-arm64.zip", bepInExDir);
            extractZipFromAssets(this, "dotnet-arm64.zip", dotnetDir);

            FusionConfig config = new FusionConfig(
                    gameLibDir,
                    appLibDir,
                    appDataDir.getAbsolutePath(),
                    getFilesDir().getAbsolutePath(),
                    bepInExDir.getAbsolutePath(),
                    dotnetDir.getAbsolutePath(),
                    copiedData.getAbsolutePath(),
                    version,
                    useOriginalLibUnity
            );

            // Setup native library hooks
            File[] nativeLibs = new File(gameLibDir).listFiles();
            if (nativeLibs != null) {
                for (File file : nativeLibs) {
                    String extractedName = file.getName().substring(3).replace(".so", "");
                    NativeLibraryManager.addGameLibrary(extractedName);
                }
            } else {
                Log.e(TAG, "Failed to list game native libraries! BepInEx may not work correctly.");
            }

            NativeLibraryManager.addFusionLibrary("main");
            NativeLibraryManager.addDataLibrary("il2cpp");
            NativeLibraryManager.setupLibraryHooks(config);

            loadNativeLibraries();

            ActivityBridge.loadFusion(config);

            // Create custom context to redirect stuff
            wrappedContext = new CustomContextWrapper(gameContext, this, this);

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Fusion Core!", e);
        }

        if (wrappedContext == null) {
            Log.e(TAG, "Failed to create wrapped context! The game may crash or fail to load.");
            wrappedContext = this;
        }

        mUnityPlayer = new UnityPlayerForActivityOrService(wrappedContext, this);
        UnityPlayer.currentActivity = this;
        UnityPlayer.currentContext = wrappedContext;

        View unityView = mUnityPlayer.getView();
        if (unityView.getParent() instanceof ViewGroup) {
            ((ViewGroup) unityView.getParent()).removeView(unityView);
        }
        setContentView(unityView);
        unityView.requestFocus();
        applyImmersiveMode();
    }

    private ClassLoader setupGameClassLoader(Context gameContext) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(TARGET_GAME, 0);
            String gameApkPath = appInfo.sourceDir;
            Log.i(TAG, "Game APK path: " + gameApkPath);

            File dexDir = new File(getFilesDir(), "game_dex");
            // Clean up stale read-only dex files from previous run
            forceDeleteRecursive(dexDir);
            dexDir.mkdirs();

            extractAllDexFromApk(gameApkPath, dexDir);

            ClassLoader currentLoader = getClassLoader();
            File[] dexFiles = dexDir.listFiles((dir, name) -> name.endsWith(".dex"));
            if (dexFiles != null) {
                for (File dexFile : dexFiles) {
                    // Android 10+ requires dex files to be non-writable
                    dexFile.setWritable(false);
                    injectDexPath(currentLoader, dexFile.getAbsolutePath());
                }
            }

            Thread.currentThread().setContextClassLoader(currentLoader);
            Log.i(TAG, "Injected " + (dexFiles != null ? dexFiles.length : 0) + " game dex files");
            return currentLoader;
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup game classloader", e);
            return getClassLoader();
        }
    }

    private void injectDexPath(ClassLoader loader, String dexPath) {
        try {
            java.lang.reflect.Field pathListField = findField(loader.getClass(), "pathList");
            Object pathList = pathListField.get(loader);

            java.lang.reflect.Method addDexPathMethod = null;
            try {
                addDexPathMethod = pathList.getClass().getDeclaredMethod("addDexPath", String.class, File.class);
                addDexPathMethod.setAccessible(true);
                addDexPathMethod.invoke(pathList, dexPath, null);
                Log.i(TAG, "Injected dex via addDexPath: " + dexPath);
                return;
            } catch (NoSuchMethodException ignored) {}

            java.lang.reflect.Field dexElementsField = findField(pathList.getClass(), "dexElements");
            Object[] oldDexElements = (Object[]) dexElementsField.get(pathList);

            Class<?> elementClass = oldDexElements[0].getClass();
            java.lang.reflect.Constructor<?> elementConstructor = elementClass.getConstructor(File.class, boolean.class, java.util.zip.ZipFile.class, String.class);

            Object[] newDexElements = (Object[]) java.lang.reflect.Array.newInstance(elementClass, oldDexElements.length + 1);

            System.arraycopy(oldDexElements, 0, newDexElements, 1, oldDexElements.length);

            File dexFile = new File(dexPath);
            newDexElements[0] = elementConstructor.newInstance(dexFile, true, null, dexPath);

            dexElementsField.set(pathList, newDexElements);
            Log.i(TAG, "Injected dex via elements: " + dexPath);
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject dex path: " + dexPath, e);
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        while (clazz != null) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private void extractAllDexFromApk(String apkPath, File outputDir) throws IOException {
        int extracted = 0;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(apkPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    File outFile = new File(outputDir, name);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    Log.i(TAG, "Extracted: " + name);
                    extracted++;
                    zis.closeEntry();
                }
            }
        }
        Log.i(TAG, "Extracted " + extracted + " dex files");
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

    private static void forceDeleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) forceDeleteRecursive(f);
            }
        }
        file.setWritable(true);
        file.delete();
    }

    private static void loadNativeLibraries()
    {
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

    private static void extractZipFromAssets(Context context, String assetName, File outputFolder)
    {
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

                    // Prevent path traversal from malformed zip entries.
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

    // Apply immersive mode to hide system bars and provide a full-screen experience.
    private void applyImmersiveMode()
    {
        Window window = getWindow();
        if (window == null) {
            return;
        }

        // Use decor view controller to avoid Window#getInsetsController() null-decor crash on startup.
        View decorView = window.getDecorView();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            final int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            decorView.setSystemUiVisibility(flags);
        }
    }

    // When Unity player unloaded move task to background
    @Override public void onUnityPlayerUnloaded() {
        moveTaskToBack(true);
    }

    // Callback before Unity player process is killed
    @Override public void onUnityPlayerQuitted() {
    }

    @Override protected void onNewIntent(Intent intent)
    {
        // To support deep linking, we need to make sure that the client can get access to
        // the last sent intent. The clients access this through a JNI api that allows them
        // to get the intent set on launch. To update that after launch we have to manually
        // replace the intent with the one caught here.
        setIntent(intent);
        mUnityPlayer.newIntent(intent);
    }

    // Quit Unity
    @Override protected void onDestroy ()
    {
        mUnityPlayer.destroy();
        super.onDestroy();
    }

    // If the activity is in multi window mode or resizing the activity is allowed we will use
    // onStart/onStop (the visibility callbacks) to determine when to pause/resume.
    // Otherwise it will be done in onPause/onResume as Unity has done historically to preserve
    // existing behavior.
    @Override protected void onStop()
    {
        super.onStop();
        mUnityPlayer.onStop();
    }

    @Override protected void onStart()
    {
        super.onStart();
        mUnityPlayer.onStart();
    }

    // Pause Unity
    @Override protected void onPause()
    {
        super.onPause();
        mUnityPlayer.onPause();
    }

    // Resume Unity
    @Override protected void onResume()
    {
        super.onResume();
        applyImmersiveMode();
        mUnityPlayer.onResume();
    }

    // Low Memory Unity
    @Override public void onLowMemory()
    {
        super.onLowMemory();
        notifyTrimMemory(UnityPlayerForActivityOrService.MemoryUsage.Critical);
    }

    // Trim Memory Unity
    @Override public void onTrimMemory(int level)
    {
        super.onTrimMemory(level);
        notifyTrimMemory(mapTrimLevel(level));
    }

    // This ensures the layout will be correct.
    @Override public void onConfigurationChanged(Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);
        mUnityPlayer.configurationChanged(newConfig);
    }

    // Notify Unity of the focus change.
    @Override public void onWindowFocusChanged(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
        mUnityPlayer.windowFocusChanged(hasFocus);
    }

    // For some reason the multiple keyevent type is not supported by the ndk.
    // Force event injection by overriding dispatchKeyEvent().
    @Override public boolean dispatchKeyEvent(KeyEvent event)
    {
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE)
            return mUnityPlayer.injectEvent(event);
        return super.dispatchKeyEvent(event);
    }

    // Pass any events not handled by (unfocused) views straight to UnityPlayer
    @Override public boolean onKeyUp(int keyCode, KeyEvent event)     { return mUnityPlayer.injectEvent(event) || super.onKeyUp(keyCode, event); }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event)   { return mUnityPlayer.injectEvent(event) || super.onKeyDown(keyCode, event); }
    @Override public boolean onTouchEvent(MotionEvent event)          { return mUnityPlayer.injectEvent(event) || super.onTouchEvent(event); }
    @Override public boolean onGenericMotionEvent(MotionEvent event)  { return mUnityPlayer.injectEvent(event) || super.onGenericMotionEvent(event); }

    private void notifyTrimMemory(UnityPlayerForActivityOrService.MemoryUsage usage)
    {
        if (mUnityPlayer instanceof UnityPlayerForActivityOrService) {
            ((UnityPlayerForActivityOrService) mUnityPlayer).onTrimMemory(usage);
        }
    }

    private UnityPlayerForActivityOrService.MemoryUsage mapTrimLevel(int level)
    {
        if (level >= TRIM_MEMORY_COMPLETE || level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            return UnityPlayerForActivityOrService.MemoryUsage.Critical;
        }
        if (level >= TRIM_MEMORY_BACKGROUND || level >= TRIM_MEMORY_RUNNING_LOW) {
            return UnityPlayerForActivityOrService.MemoryUsage.High;
        }
        if (level >= TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_MODERATE) {
            return UnityPlayerForActivityOrService.MemoryUsage.Medium;
        }
        return UnityPlayerForActivityOrService.MemoryUsage.Low;
    }
}
