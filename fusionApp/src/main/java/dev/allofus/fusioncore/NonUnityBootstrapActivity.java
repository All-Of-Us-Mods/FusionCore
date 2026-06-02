package dev.allofus.fusioncore;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Bootstraps a non-Unity native Android app by:
 *   1. Resolving the target package context and native library directory.
 *   2. Hooking {@code ClassLoader.findLibrary} so the target's own .so files
 *      still resolve to the correct paths inside its nativeLibraryDir.
 *   3. Cross-wiring the game and FusionCore class-loaders via
 *      {@link ClassLoaderHooks} so activity dispatch succeeds.
 *   4. Suppressing bogus {@code setComponentEnabledSetting} / {@code getPackageInfo}
 *      calls via {@link PackageManagerHooks}.
 *   5. Bypassing AppCompat theme assertions on the target activity via
 *      {@link AppCompatBypassHooks}.
 *   6. Installing a one-shot {@code beforeCall} hook on the target activity's
 *      {@code onCreate} that loads the user-supplied .so (and any extras) via
 *      {@link System#load} before the original implementation runs.
 *   7. Launching the target activity and finishing this one.
 *
 * <p>Intended use-case: {@code com.oculus.os.cm/.HeadsetApplication} (and any
 * other non-Unity APK where you want to inject one or more native libraries
 * without a managed runtime).
 *
 * <h3>Required Intent extras</h3>
 * <ul>
 *   <li>{@link #EXTRA_TARGET_PACKAGE}  – package name of the target app</li>
 *   <li>{@link #EXTRA_TARGET_ACTIVITY} – fully-qualified activity class name</li>
 *   <li>{@link #EXTRA_LIBRARY_PATH}    – absolute path to the primary .so to inject</li>
 * </ul>
 *
 * <h3>Optional Intent extras</h3>
 * <ul>
 *   <li>{@link #EXTRA_EXTRA_LIBRARY_PATHS} – {@code String[]} of additional .so paths
 *       loaded after the primary library but still before the target's own
 *       {@code onCreate}.</li>
 * </ul>
 */
public class NonUnityBootstrapActivity extends AppCompatActivity {

    public static final String TAG = "NonUnityBootstrap";

    // -------------------------------------------------------------------------
    // Intent extras
    // -------------------------------------------------------------------------

    /** Package name of the target app (e.g. {@code "com.oculus.os.cm"}). */
    public static final String EXTRA_TARGET_PACKAGE  = "target_package";

    /** Fully-qualified activity class name (e.g. {@code "com.oculus.os.cm.HeadsetApplication"}). */
    public static final String EXTRA_TARGET_ACTIVITY = "target_activity";

    /** Absolute path to the primary .so to inject (e.g. {@code "/sdcard/mymod.so"}). */
    public static final String EXTRA_LIBRARY_PATH    = "library_path";

    /**
     * Optional {@code String[]} of additional .so absolute paths to load after
     * {@link #EXTRA_LIBRARY_PATH} but still before the target's own onCreate.
     */
    public static final String EXTRA_EXTRA_LIBRARY_PATHS = "extra_library_paths";

    // -------------------------------------------------------------------------
    // Well-known target constants (HeadsetApplication)
    // -------------------------------------------------------------------------

    public static final String HEADSET_APP_PACKAGE  = "com.oculus.os.cm";
    public static final String HEADSET_APP_ACTIVITY = "com.oculus.os.cm.HeadsetApplication";

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final AtomicBoolean hookInstalled    = new AtomicBoolean(false);
    private final AtomicBoolean librariesLoaded  = new AtomicBoolean(false);

    private TextView    statusView;
    private ProgressBar spinner;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bootstrap);

        statusView = findViewById(R.id.bootstrap_status);
        spinner    = findViewById(R.id.bootstrap_progress);

        String targetPackage  = getIntent().getStringExtra(EXTRA_TARGET_PACKAGE);
        String targetActivity = getIntent().getStringExtra(EXTRA_TARGET_ACTIVITY);
        String libraryPath    = getIntent().getStringExtra(EXTRA_LIBRARY_PATH);
        String[] extraLibs    = getIntent().getStringArrayExtra(EXTRA_EXTRA_LIBRARY_PATHS);

        // Apply HeadsetApplication defaults when the caller omits the activity name.
        if (HEADSET_APP_PACKAGE.equals(targetPackage) && (targetActivity == null || targetActivity.isEmpty())) {
            targetActivity = HEADSET_APP_ACTIVITY;
            Log.i(TAG, "Defaulting target activity to HeadsetApplication");
        }

        if (targetPackage == null || targetPackage.isEmpty()) {
            failAndFinish("No target_package specified.");
            return;
        }
        if (targetActivity == null || targetActivity.isEmpty()) {
            failAndFinish("No target_activity specified.");
            return;
        }
        if (libraryPath == null || libraryPath.isEmpty()) {
            failAndFinish("No library_path specified.");
            return;
        }
        if (!new File(libraryPath).exists()) {
            failAndFinish("library_path does not exist: " + libraryPath);
            return;
        }

        setStatus("Preparing…");

        final NonUnityConfig config = new NonUnityConfig(
                targetPackage,
                targetActivity,
                libraryPath,
                /*gameLibraryDirectory*/ "",   // resolved on the background thread
                getApplicationInfo().nativeLibraryDir,
                extraLibs
        );

        // Kick off on a background thread so the UI renders first.
        statusView.post(() ->
                new Thread(() -> runFlow(config), "non-unity-bootstrap").start()
        );
    }

    // -------------------------------------------------------------------------
    // Core flow
    // -------------------------------------------------------------------------

    private void runFlow(NonUnityConfig config) {
        // ── 1. Create a package context for the target ────────────────────────
        setStatus("Creating package context…");
        Context gameContext;
        try {
            gameContext = createPackageContext(
                    config.targetPackage,
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
        } catch (Exception e) {
            failAndFinish("Failed to create package context for "
                    + config.targetPackage + ": " + e.getMessage());
            return;
        }

        ApplicationInfo gameAppInfo = gameContext.getApplicationInfo();
        String nativeLibDir = gameAppInfo.nativeLibraryDir;
        ClassLoader gameClassLoader = gameContext.getClassLoader();

        // Build the final config now that we have the real nativeLibraryDir.
        NonUnityConfig resolvedConfig = new NonUnityConfig(
                config.targetPackage,
                config.targetActivityClass,
                config.libraryPath,
                nativeLibDir,
                config.appLibraryDirectory,
                config.extraLibraryPaths
        );

        // ── 2. Register game native libs in NativeLibraryManager ─────────────
        setStatus("Registering native libraries…");
        registerGameNativeLibraries(nativeLibDir);

        // ── 3. Hook ClassLoader.findLibrary ───────────────────────────────────
        setStatus("Installing library redirect hook…");
        try {
            hookFindLibrary(gameClassLoader, nativeLibDir);
        } catch (Exception e) {
            Log.e(TAG, "findLibrary hook failed (non-fatal, continuing)", e);
        }

        // ── 4. Cross-wire class loaders ───────────────────────────────────────
        setStatus("Installing class-loader hooks…");
        try {
            ClassLoaderHooks.installHooks(gameClassLoader);
        } catch (Exception e) {
            Log.e(TAG, "ClassLoaderHooks failed (non-fatal, continuing)", e);
        }

        // ── 5. PackageManager hooks ───────────────────────────────────────────
        setStatus("Installing package-manager hooks…");
        try {
            PackageManagerHooks.installHooks(getPackageManager());
        } catch (Exception e) {
            Log.e(TAG, "PackageManagerHooks failed (non-fatal, continuing)", e);
        }

        // ── 6. Resolve the target Activity class ─────────────────────────────
        setStatus("Resolving target activity…");
        Class<?> activityClass;
        try {
            activityClass = gameClassLoader.loadClass(resolvedConfig.targetActivityClass);
        } catch (ClassNotFoundException e) {
            failAndFinish("Target activity class not found: "
                    + resolvedConfig.targetActivityClass);
            return;
        }

        // ── 7. AppCompat bypass hooks ─────────────────────────────────────────
        setStatus("Installing AppCompat bypass hooks…");
        try {
            AppCompatBypassHooks.installHooks(gameClassLoader, resolvedConfig.targetActivityClass);
        } catch (Exception e) {
            Log.e(TAG, "AppCompatBypassHooks failed (non-fatal, continuing)", e);
        }

        // ── 8. Install injection hook on the target's onCreate ───────────────
        setStatus("Installing injection hook…");
        if (!installInjectionHook(activityClass, resolvedConfig)) {
            failAndFinish("Could not install injection hook on "
                    + resolvedConfig.targetActivityClass);
            return;
        }

        // ── 9. Launch the target activity ─────────────────────────────────────
        setStatus("Launching…");
        runOnMainThread(() -> {
            try {
                Intent intent = new Intent();
                intent.setClassName(resolvedConfig.targetPackage,
                        resolvedConfig.targetActivityClass);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
            } catch (Throwable t) {
                failAndFinish("Failed to launch "
                        + resolvedConfig.targetActivityClass + ": " + t.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Injection hook
    // -------------------------------------------------------------------------

    /**
     * Finds {@code onCreate(Bundle)} on {@code activityClass} (walking the
     * hierarchy) and installs a one-shot {@code beforeCall} hook that loads
     * {@link NonUnityConfig#libraryPath} (and any extras) via
     * {@link System#load} before the original implementation runs.
     *
     * @return {@code true} if the hook was installed successfully.
     */
    private boolean installInjectionHook(Class<?> activityClass, NonUnityConfig config) {
        if (hookInstalled.get()) {
            return true;
        }

        Method onCreateMethod = findOnCreate(activityClass);
        if (onCreateMethod == null) {
            Log.e(TAG, "Could not find onCreate on " + activityClass.getName());
            return false;
        }
        onCreateMethod.setAccessible(true);

        Pine.hook(onCreateMethod, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                if (!librariesLoaded.compareAndSet(false, true)) {
                    return; // fire only once even if onCreate is somehow called again
                }

                Object receiver = callFrame.thisObject;
                Log.i(TAG, "beforeCall fired for " + config.targetActivityClass
                        + " on " + receiver);

                // Set the FusionCore theme so the window decorates correctly.
                if (receiver instanceof Activity) {
                    ((Activity) receiver).setTheme(R.style.UnityThemeSelector);
                }

                // ── Primary library ──────────────────────────────────────────
                loadLibrarySafely(config.libraryPath, "primary");

                // ── Extra libraries (in declaration order) ───────────────────
                if (config.extraLibraryPaths != null) {
                    for (int i = 0; i < config.extraLibraryPaths.length; i++) {
                        loadLibrarySafely(config.extraLibraryPaths[i], "extra[" + i + "]");
                    }
                }
            }
        });

        hookInstalled.set(true);
        Log.i(TAG, "Injection hook installed for " + config.targetActivityClass);
        return true;
    }

    /**
     * Calls {@link System#load} on {@code path}, logging success or failure.
     * Failures are non-fatal - the app launch continues so the target can
     * at least show its own error state rather than a blank FusionCore screen.
     */
    private static void loadLibrarySafely(String path, String label) {
        if (path == null || path.isEmpty()) {
            Log.w(TAG, "Skipping " + label + ": null or empty path");
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            Log.e(TAG, "Skipping " + label + ": file not found at " + path);
            return;
        }
        try {
            System.load(path);
            Log.i(TAG, "Loaded " + label + ": " + path);
        } catch (Throwable t) {
            Log.e(TAG, "System.load failed for " + label + ": " + path, t);
        }
    }

    // -------------------------------------------------------------------------
    // findLibrary hook
    // -------------------------------------------------------------------------

    /**
     * Redirects {@code ClassLoader.findLibrary} calls from the game's own
     * class-loader so that bare library names (e.g. {@code "foo"}) resolve to
     * their real paths inside the target's {@code nativeLibraryDir}.
     */
    private void hookFindLibrary(ClassLoader gameClassLoader, String nativeLibraryDir) {
        Method findLibraryMethod = findFindLibraryMethod(gameClassLoader);
        if (findLibraryMethod == null) {
            Log.w(TAG, "Could not locate findLibrary in ClassLoader hierarchy - skipping redirect");
            return;
        }

        Pine.hook(findLibraryMethod, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                // Only intercept calls originating from the game's ClassLoader.
                if (callFrame.thisObject != gameClassLoader) return;

                String libName = (String) callFrame.args[0];
                if (libName == null) return;

                // First, check if NativeLibraryManager already has a resolution.
                String managed = NativeLibraryManager.resolveLibraryPath(libName);
                if (managed != null) {
                    Log.i(TAG, "findLibrary (managed) " + libName + " → " + managed);
                    callFrame.setResult(managed);
                    return;
                }

                // Fall back to the target's own nativeLibraryDir.
                String candidate = nativeLibraryDir + "/lib" + libName + ".so";
                if (new File(candidate).exists()) {
                    Log.i(TAG, "findLibrary (redirect) " + libName + " → " + candidate);
                    callFrame.setResult(candidate);
                }
                // If not found anywhere, let the original search run normally.
            }
        });

        Log.i(TAG, "findLibrary hook installed for " + gameClassLoader);
    }

    // -------------------------------------------------------------------------
    // Native library registration
    // -------------------------------------------------------------------------

    /**
     * Enumerates all {@code lib*.so} files inside the game's native library
     * directory and registers them with {@link NativeLibraryManager} so the
     * managed {@code resolveLibraryPath} table is populated before hooks fire.
     */
    private static void registerGameNativeLibraries(String nativeLibraryDir) {
        File[] libs = new File(nativeLibraryDir).listFiles();
        if (libs == null) {
            Log.w(TAG, "No native libs found in " + nativeLibraryDir);
            return;
        }
        int count = 0;
        for (File f : libs) {
            String name = f.getName();
            if (name.startsWith("lib") && name.endsWith(".so") && name.length() > 6) {
                String soName = name.substring(3, name.length() - 3); // strip "lib" and ".so"
                NativeLibraryManager.addGameLibrary(soName);
                count++;
            }
        }
        Log.i(TAG, "Registered " + count + " game native libraries from " + nativeLibraryDir);
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    /**
     * Walks the class hierarchy looking for {@code onCreate(Bundle)}.
     * Stops before reaching {@link Object}.
     */
    private static Method findOnCreate(Class<?> clazz) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredMethod("onCreate", Bundle.class);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Walks the ClassLoader hierarchy looking for a concrete
     * {@code findLibrary(String)} method.
     */
    private static Method findFindLibraryMethod(ClassLoader startLoader) {
        Class<?> clazz = startLoader.getClass();
        while (clazz != null) {
            try {
                Method m = clazz.getDeclaredMethod("findLibrary", String.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private void setStatus(String msg) {
        runOnMainThread(() -> {
            if (statusView != null) statusView.setText(msg);
            if (spinner    != null) spinner.setVisibility(View.VISIBLE);
        });
    }

    private void failAndFinish(String msg) {
        Log.e(TAG, msg);
        runOnMainThread(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private void runOnMainThread(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            runOnUiThread(r);
        }
    }

    // -------------------------------------------------------------------------
    // Static factory - convenience launcher for HeadsetApplication
    // -------------------------------------------------------------------------

    /**
     * Builds and returns an {@link Intent} that launches this activity
     * pre-configured for {@code com.oculus.os.cm / HeadsetApplication}.
     *
     * @param context     the calling context
     * @param libraryPath absolute path to the primary .so to inject
     * @param extraLibs   optional additional .so paths, may be null
     */
    public static Intent buildHeadsetAppIntent(Context context,
                                               String libraryPath,
                                               String[] extraLibs) {
        Intent intent = new Intent(context, NonUnityBootstrapActivity.class);
        intent.putExtra(EXTRA_TARGET_PACKAGE,  HEADSET_APP_PACKAGE);
        intent.putExtra(EXTRA_TARGET_ACTIVITY, HEADSET_APP_ACTIVITY);
        intent.putExtra(EXTRA_LIBRARY_PATH,    libraryPath);
        if (extraLibs != null && extraLibs.length > 0) {
            intent.putExtra(EXTRA_EXTRA_LIBRARY_PATHS, extraLibs);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}