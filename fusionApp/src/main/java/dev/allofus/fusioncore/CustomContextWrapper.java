package dev.allofus.fusioncore;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

public class CustomContextWrapper extends ContextWrapper {
    private static final String TAG = "FusionCore";

    Context gameContext;
    Context fusionContext;
    String targetPackage;

    File gameDataDir;
    File gameFilesDir;
    File gameCacheDir;
    File gameCodeCacheDir;
    File gameNoBackupDir;
    File gameDatabasesDir;
    File gameSharedPrefsDir;
    File gameExternalCacheDir;
    File gameExternalFilesDir;
    File gameObbDir;

    ApplicationInfo modifiedAppInfo;

    public CustomContextWrapper(Context gameContext, Context fusionContext, Context baseContext, String targetPackage) {
        super(baseContext);
        this.gameContext = gameContext;
        this.fusionContext = fusionContext;
        this.targetPackage = targetPackage;

        this.gameDataDir = new File(fusionContext.getFilesDir(), targetPackage);
        this.gameFilesDir = new File(this.gameDataDir, "files");
        this.gameCacheDir = new File(this.gameDataDir, "cache");
        this.gameCodeCacheDir = new File(this.gameDataDir, "code_cache");
        this.gameNoBackupDir = new File(this.gameDataDir, "no_backup");
        this.gameDatabasesDir = new File(this.gameDataDir, "databases");
        this.gameSharedPrefsDir = new File(this.gameDataDir, "shared_prefs");

        File fusionExternalCache = fusionContext.getExternalCacheDir();
        this.gameExternalCacheDir = fusionExternalCache != null ? new File(fusionExternalCache, targetPackage) : null;

        File fusionExternalFiles = fusionContext.getExternalFilesDir(null);
        this.gameExternalFilesDir = fusionExternalFiles != null ? new File(fusionExternalFiles, targetPackage) : null;

        this.gameObbDir = new File(fusionContext.getObbDir(), targetPackage);

        ensureDirsExist(
                this.gameDataDir,
                this.gameFilesDir,
                this.gameCacheDir,
                this.gameCodeCacheDir,
                this.gameNoBackupDir,
                this.gameDatabasesDir,
                this.gameSharedPrefsDir,
                this.gameObbDir
        );
        if (this.gameExternalCacheDir != null) this.gameExternalCacheDir.mkdirs();
        if (this.gameExternalFilesDir != null) this.gameExternalFilesDir.mkdirs();

        this.getApplicationInfo().dataDir = baseContext.getApplicationInfo().dataDir;
        // this prevents the game from resolving its own libraries
        // that way we can override them properly with our own versions
        this.getApplicationInfo().nativeLibraryDir = "";
    }

    // ============ Resource & Package Overrides ============

    @Override
    public Resources getResources() {
        return gameContext.getResources();
    }

    @Override
    public AssetManager getAssets() {
        return gameContext.getAssets();
    }

    @Override
    public String getPackageName() {
        return targetPackage;
    }

    @Override
    public String getPackageResourcePath() {
        return gameContext.getPackageResourcePath();
    }

    @Override
    public String getPackageCodePath() {
        return gameContext.getPackageCodePath();
    }

    @Override
    public Context getApplicationContext() {
        return fusionContext.getApplicationContext();
    }

    @Override
    public ClassLoader getClassLoader() {
        return gameContext.getClassLoader();
    }

    @Override
    public Context createConfigurationContext(android.content.res.Configuration overrideConfiguration) {
        Context baseContext = super.createConfigurationContext(overrideConfiguration);
        return new CustomContextWrapper(gameContext, fusionContext, baseContext, targetPackage);
    }

    @Override
    public Context createDisplayContext(Display display) {
        Context baseContext = super.createDisplayContext(display);
        return new CustomContextWrapper(gameContext, fusionContext, baseContext, targetPackage);
    }

    @Override
    public Context createDeviceContext(int deviceId) {
        Context baseContext = super.createDeviceContext(deviceId);
        return new CustomContextWrapper(gameContext, fusionContext, baseContext, targetPackage);
    }

    @Override
    public File getDataDir() {
        return gameDataDir;
    }

    @Override
    public File getFilesDir() {
        return gameFilesDir;
    }

    @Override
    public File getCacheDir() {
        return gameCacheDir;
    }

    @Override
    public File getCodeCacheDir() {
        return gameCodeCacheDir;
    }

    @Override
    public File getNoBackupFilesDir() {
        return gameNoBackupDir;
    }

    @Override
    public File getDatabasePath(String name) {
        File dbFile = new File(gameDatabasesDir, name);
        File parent = dbFile.getParentFile();
        if (parent != null) parent.mkdirs();
        return dbFile;
    }

    @Override
    public File getDir(String name, int mode) {
        File dir = new File(gameFilesDir, name);
        dir.mkdirs();
        return dir;
    }

    @Nullable
    @Override
    public File getExternalCacheDir() {
        return gameExternalCacheDir;
    }

    @Override
    public File[] getExternalCacheDirs() {
        return gameExternalCacheDir != null ? new File[]{gameExternalCacheDir} : new File[0];
    }

    @Override
    public File getExternalFilesDir(String type) {
        if (type == null || gameExternalFilesDir == null) {
            return gameExternalFilesDir;
        }
        File typedDir = new File(gameExternalFilesDir, type);
        typedDir.mkdirs();
        return typedDir;
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        File dir = getExternalFilesDir(type);
        return dir != null ? new File[]{dir} : new File[0];
    }

    @Override
    public File getObbDir() {
        return gameObbDir;
    }

    @Override
    public File[] getObbDirs() {
        return new File[]{gameObbDir};
    }

    @Override
    public File[] getExternalMediaDirs() {
        File[] baseDirs = fusionContext.getExternalMediaDirs();
        if (baseDirs == null) return new File[0];
        File[] result = new File[baseDirs.length];
        for (int i = 0; i < baseDirs.length; i++) {
            result[i] = new File(baseDirs[i], targetPackage);
            result[i].mkdirs();
        }
        return result;
    }


    @Override
    public FileInputStream openFileInput(String name) throws FileNotFoundException {
        return new FileInputStream(new File(gameFilesDir, name));
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        return new FileOutputStream(new File(gameFilesDir, name));
    }

    @Override
    public boolean deleteFile(String name) {
        return new File(gameFilesDir, name).delete();
    }

    @Override
    public String[] fileList() {
        return gameFilesDir.list();
    }

    @Override
    public boolean deleteDatabase(String name) {
        return getDatabasePath(name).delete();
    }

    
    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return this.fusionContext.getSharedPreferences(name, mode);
    }

    public boolean deleteSharedPreferences(String name) {
        return this.fusionContext.deleteSharedPreferences(name);
    }

    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        return this.fusionContext.moveSharedPreferencesFrom(sourceContext, name);
    }

    @Override
    public Display getDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return this.fusionContext.getDisplay();
        }
        return null;
    }

    @Override
    public Object getSystemService(String name) {
        if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) {
            LayoutInflater inflater = LayoutInflater.from(fusionContext);
            return inflater.cloneInContext(this);
        }
        return this.fusionContext.getSystemService(name);
    }

    
    private static void ensureDirsExist(File... dirs) {
        for (File dir : dirs) {
            if (dir != null && !dir.exists()) {
                if (!dir.mkdirs()) {
                    Log.w(TAG, "Failed to create directory: " + dir.getAbsolutePath());
                }
            }
        }
    }
}
