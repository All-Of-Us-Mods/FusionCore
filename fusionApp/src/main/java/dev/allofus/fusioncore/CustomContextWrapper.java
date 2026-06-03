package dev.allofus.fusioncore;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.lang.reflect.Field;

public class CustomContextWrapper extends ContextWrapper {
    private static final String TAG = "FusionCore";  // maybe move somewhere? Its used in both BootstrapActivity and this

    private final Context fusionContext;
    private final Context appContext;
    private final String targetPackage;

    private final File dataDir;

    @Nullable private final File externalCacheDir;
    @Nullable private final File externalFilesDir;
    @Nullable private final File obbDir;

    public CustomContextWrapper(Context gameContext, Context fusionContext, Context appContext, String targetPackage) {
        super(gameContext);
        this.fusionContext = fusionContext;
        this.targetPackage = targetPackage;
        this.appContext = appContext != fusionContext ? appContext : fusionContext;

        this.dataDir = new File(fusionContext.getFilesDir(), targetPackage);
        ensureDirs(
            dataDir,
            new File(dataDir, "files"),
            new File(dataDir, "cache"),
            new File(dataDir, "code_cache"),
            new File(dataDir, "no_backup"),
            new File(dataDir, "databases"),
            new File(dataDir, "shared_prefs"),
            new File(dataDir, "lib")
        );

        File extCache = fusionContext.getExternalCacheDir();
        this.externalCacheDir = extCache != null ? new File(extCache, targetPackage) : null;

        File extFiles = fusionContext.getExternalFilesDir(null);
        this.externalFilesDir = extFiles != null ? new File(extFiles, targetPackage) : null;

        File obb = fusionContext.getObbDir();
        this.obbDir = obb != null ? new File(obb, targetPackage) : null;

        if (externalCacheDir != null) externalCacheDir.mkdirs();
        if (externalFilesDir != null) externalFilesDir.mkdirs();
        if (obbDir != null) obbDir.mkdirs();

        patchDataDir(gameContext, dataDir, targetPackage);
    }

    private void patchDataDir(Context base, File dir, String pkg) {
        try {
            Context impl = base;
            while (impl instanceof ContextWrapper) {
                impl = ((ContextWrapper) impl).getBaseContext();
            }

            Object loadedApk = getField(impl.getClass(), impl, "mPackageInfo");
            ApplicationInfo ai = (ApplicationInfo) getField(loadedApk.getClass(), loadedApk, "mApplicationInfo");
            ApplicationInfo clone = new ApplicationInfo(ai);
            clone.dataDir = dir.getAbsolutePath();
            clone.nativeLibraryDir = "";
            clone.packageName = pkg;
            setField(loadedApk.getClass(), loadedApk, "mApplicationInfo", clone);

            String[] cached = {
                "mFilesDir", "mCacheDir", "mDatabasesDir",
                "mPreferencesDir", "mNoBackupFilesDir", "mCodeCacheDir"
            };
            for (String name : cached) {
                try {
                    setField(impl.getClass(), impl, name, null);
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "patchDataDir failed", e);
        }
    }

    @Override
    public String getPackageName() {
        return targetPackage;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return fusionContext.getSharedPreferences(targetPackage + "_" + name, mode);
    }

    @Override
    public boolean deleteSharedPreferences(String name) {
        return fusionContext.deleteSharedPreferences(targetPackage + "_" + name);
    }

    @Override
    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        return fusionContext.moveSharedPreferencesFrom(sourceContext, targetPackage + "_" + name);
    }

    @Override
    public Context createConfigurationContext(android.content.res.Configuration overrideConfiguration) {
        return new CustomContextWrapper(super.createConfigurationContext(overrideConfiguration), fusionContext, appContext, targetPackage);
    }

    @Override
    public Context createDisplayContext(Display display) {
        return new CustomContextWrapper(super.createDisplayContext(display), fusionContext, appContext, targetPackage);
    }

    @Override
    public Context createDeviceContext(int deviceId) {
        return new CustomContextWrapper(super.createDeviceContext(deviceId), fusionContext, appContext, targetPackage);
    }

    @Nullable
    @Override
    public File getExternalCacheDir() {
        return externalCacheDir;
    }

    @Override
    public File[] getExternalCacheDirs() {
        return externalCacheDir != null ? new File[]{externalCacheDir} : new File[0];
    }

    @Nullable
    @Override
    public File getExternalFilesDir(String type) {
        if (externalFilesDir == null) {
            return null;
        }
        if (type == null) {
            return externalFilesDir;
        }
        File dir = new File(externalFilesDir, type);
        dir.mkdirs();
        return dir;
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        File dir = getExternalFilesDir(type);
        return dir != null ? new File[]{dir} : new File[0];
    }

    @Override
    public File[] getExternalMediaDirs() {
        File[] base = fusionContext.getExternalMediaDirs();
        if (base == null) {
            return new File[0];
        }
        File[] out = new File[base.length];
        for (int i = 0; i < base.length; i++) {
            out[i] = new File(base[i], targetPackage);
            out[i].mkdirs();
        }
        return out;
    }

    @Override
    public Display getDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return fusionContext.getDisplay();
        }
        return null;
    }

    @Override
    public Object getSystemService(@NonNull String name) {
        if (LAYOUT_INFLATER_SERVICE.equals(name)) {
            return LayoutInflater.from(fusionContext).cloneInContext(this);
        }
        return fusionContext.getSystemService(name);
    }

    @Override
    public Context getApplicationContext() {
        return appContext;
    }

    @Nullable
    @Override
    public File getObbDir() {
        return obbDir;
    }

    @Override
    public File[] getObbDirs() {
        return obbDir != null ? new File[]{obbDir} : new File[0];
    }

    private static void ensureDirs(File... dirs) {
        for (File d : dirs) {
            if (d != null && !d.exists() && !d.mkdirs()) {
                Log.w(TAG, "Failed to create dir: " + d.getAbsolutePath());
            }
        }
    }

    private static Object getField(Class<?> clazz, Object target, String name) throws Exception {
        Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(Class<?> clazz, Object target, String name, Object value) throws Exception {
        Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
