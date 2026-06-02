package dev.allofus.fusioncore;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;


import org.jetbrains.annotations.Nullable;

import java.io.File;

public class CustomContextWrapper extends ContextWrapper {
    Context fusionContext;
    Context appContext;

    public CustomContextWrapper(Context gameContext, Context fusionContext, Context appContext) {
        super(gameContext);
        this.fusionContext = fusionContext;
        // Store appContext directly — no recursive wrapping
        this.appContext = appContext;

        // Mono's DllImport resolver on Android derives its native linker namespace
        // search path from nativeLibraryDir. Setting it to "" (the previous value)
        // stripped FusionCore's lib dir from the search path, causing DllImport of
        // "libBootstrap.so" to fail with DllNotFoundException even after a successful
        // dlopen() with RTLD_GLOBAL (Mono uses android_dlopen_ext / linker namespaces
        // and does not honour RTLD_GLOBAL).
        //
        // We set nativeLibraryDir to FusionCore's own native library directory so
        // Mono can locate libBootstrap.so when setting up its namespace. The game's
        // native libs are handled separately via NativeLibraryManager.findLibrary().
        String fusionLibDir = fusionContext.getApplicationInfo().nativeLibraryDir;
        this.getApplicationInfo().nativeLibraryDir = fusionLibDir;
    }
    @Override
    public android.content.pm.ApplicationInfo getApplicationInfo() {
        android.content.pm.ApplicationInfo info = super.getApplicationInfo();
        // Force the identity on the object returned to the caller
        info.packageName = appContext.getPackageName();
        return info;
    }
    @Override
    public AssetManager getAssets() {
        return super.getAssets(); // game's assets
    }
    @Override
    public String getPackageName() {
        return appContext.getPackageName();
    }
    @Override
    public Resources.Theme getTheme() {
        // If fusionContext has an AppCompat theme, use it when AppCompat asks
        // Otherwise fall back to the game's theme
        try {
            Resources.Theme theme = fusionContext.getResources().newTheme();
            theme.applyStyle(androidx.appcompat.R.style.Theme_AppCompat, true);
            return theme;
        } catch (Exception e) {
            return super.getTheme(); // fallback to game theme
        }
    }

    @Override
    public void setTheme(int resid) {
        super.setTheme(resid); // apply to game context
    }
    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return this.appContext.getSharedPreferences(name, mode);
    }

    public boolean deleteSharedPreferences(String name) {
        return this.appContext.deleteSharedPreferences(name);
    }

    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        return this.appContext.moveSharedPreferencesFrom(sourceContext, name);
    }

    @Override
    public File getFilesDir() {
        // Return a GT-named subdirectory inside FusionCore's sandbox
        // This already exists because BootstrapActivity creates it as appDataDir
        File dir = new File(this.fusionContext.getFilesDir(), "com.AnotherAxiom.GorillaTag/files");
        dir.mkdirs();
        return dir;
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        return new File[0];
    }
    @Override
    public File getCacheDir() {
        return this.appContext.getCacheDir();
    }

    @Nullable
    @Override
    public File getExternalCacheDir() {
        return this.appContext.getExternalCacheDir();
    }


    @Override
    public File[] getExternalCacheDirs() {
        return this.appContext.getExternalCacheDirs();
    }


    @Override
    public File getExternalFilesDir(String type) {
        // Return null or a fusioncore-owned external path
        // Returning null tells Unity to skip external storage
        return null;
    }

    @Override
    public Display getDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return this.fusionContext.getDisplay();
        }
        return null;
    }

    @Override
    public Resources getResources() {
        return super.getBaseContext().getResources(); // always game's resources, not fusionContext's
    }

    @Override
    public Object getSystemService(String name) {
        if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) {
            // Clone the game's inflater with this context so resource lookups
            // use game resources but the inflater knows our wrapped context
            LayoutInflater base = (LayoutInflater) super.getSystemService(name);
            return base != null ? base.cloneInContext(this) : null;
        }
        if (Context.WINDOW_SERVICE.equals(name) || "ui_mode".equals(name)) {
            return super.getSystemService(name);
        }
        return this.fusionContext.getSystemService(name);
    }

    @Override
    public Context getBaseContext() {
        return super.getBaseContext();
    }

    @Override
    public Context getApplicationContext() {
        // If callers (like MelonLoader) call getApplicationContext().getAssets(),
        // they need to get the game's assets, not FusionCore's.
        // But returning `this` causes infinite loops; wrap carefully.
        return this; // or a thin wrapper that still delegates assets to the game
    }

    @Override
    public File getObbDir() {
        Log.i("f", "2");
        return null;
//        return this.appContext.getObbDir();
    }

    @Override
    public File[] getObbDirs() {
        return this.fusionContext.getObbDirs();
    }
}