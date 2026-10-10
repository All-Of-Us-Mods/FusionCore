package dev.allofus.fusioncore.tools;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.DatabaseErrorHandler;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;
import android.view.Display;


import org.jetbrains.annotations.Nullable;

import java.io.File;

public class CustomContextWrapper extends ContextWrapper {
    Context fusionContext;
    Context gameContext;
    private final Context applicationContext;

    public CustomContextWrapper(Context gameContext, Context fusionContext) {
        this(gameContext, fusionContext, null);
    }

    private CustomContextWrapper(Context gameContext, Context fusionContext, Application application) {
        super(gameContext);
        this.gameContext = gameContext;
        this.fusionContext = fusionContext;
        if (application != null) {
            this.applicationContext = application;
        } else {
            Application fusionApplication = (Application) fusionContext.getApplicationContext();
            GameApplication gameApplication = new GameApplication(fusionApplication);
            gameApplication.attach(new CustomContextWrapper(gameContext, fusionApplication, gameApplication));
            this.applicationContext = gameApplication;
        }
        this.getApplicationInfo().dataDir = Utilities.getExternalFusionCoreDirectory(gameContext.getPackageName(), "PersistentData").getAbsolutePath();
        // this prevents the game from resolving its own libraries
        // that way we can override them properly with our own versions
        this.getApplicationInfo().nativeLibraryDir = "";
    }

    public Context getOriginalActivity() {
        return fusionContext;
    }

//    @Override
//    public Resources getResources() {
//        return this.appContext.getResources();
//    }


//    @Override
//    public ApplicationInfo getApplicationInfo() {
//        return super.getApplicationInfo();
//    }

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
    public File getDatabasePath(String name) {
        return fusionContext.getDatabasePath(name);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
        return fusionContext.openOrCreateDatabase(name, mode, factory);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
                                               DatabaseErrorHandler errorHandler) {
        return fusionContext.openOrCreateDatabase(name, mode, factory, errorHandler);
    }

    @Override public boolean deleteDatabase(String name) { return fusionContext.deleteDatabase(name); }
    @Override public String[] databaseList() { return fusionContext.databaseList(); }
    @Override public boolean moveDatabaseFrom(Context source, String name) {
        return fusionContext.moveDatabaseFrom(source, name);
    }

    @Override
    public File getFilesDir() {
        return this.fusionContext.getFilesDir();
    }

    @Override
    public File getCacheDir() {
        return this.fusionContext.getCacheDir();
    }

    @Nullable
    @Override
    public File getExternalCacheDir() {
        return this.fusionContext.getExternalCacheDir();
    }


    @Override
    public File[] getExternalCacheDirs() {
        return this.fusionContext.getExternalCacheDirs();
    }

    @Override
    public File getExternalFilesDir(String type) {
        if (type == null) {
            return Utilities.getExternalFusionCoreDirectory(gameContext.getPackageName(), "PersistentData");
        }
        return new File(Utilities.getExternalFusionCoreDirectory(gameContext.getPackageName(), "PersistentData"), type);
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
        return this.fusionContext.getSystemService(name);
    }

    @Override
    public Context getBaseContext() {
        return super.getBaseContext();
    }

    @Override
    public Context getApplicationContext() {
        return applicationContext;
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

    private static final class GameApplication extends Application {
        private final Application fusionApplication;

        GameApplication(Application fusionApplication) { this.fusionApplication = fusionApplication; }
        void attach(Context context) { attachBaseContext(context); }

        @Override public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
            fusionApplication.registerActivityLifecycleCallbacks(callback);
        }
        @Override public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
            fusionApplication.unregisterActivityLifecycleCallbacks(callback);
        }
        @Override public void registerComponentCallbacks(android.content.ComponentCallbacks callback) {
            fusionApplication.registerComponentCallbacks(callback);
        }
        @Override public void unregisterComponentCallbacks(android.content.ComponentCallbacks callback) {
            fusionApplication.unregisterComponentCallbacks(callback);
        }
    }
}
