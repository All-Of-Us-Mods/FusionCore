package dev.allofus.fusioncore.hooks;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import java.io.File;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import dalvik.system.BaseDexClassLoader;
import dev.allofus.fusioncore.R;
import dev.allofus.fusioncore.tools.CustomContextWrapper;

public final class TranslatedRuntime {
    private TranslatedRuntime() {}

    public static Context prepareGameContext(Context gameContext, ClassLoader fusionLoader)
            throws ReflectiveOperationException {
        Field pathList = BaseDexClassLoader.class.getDeclaredField("pathList");
        pathList.setAccessible(true);
        Object fusionPaths = pathList.get(fusionLoader);
        android.content.pm.ApplicationInfo info = gameContext.getApplicationInfo();
        ArrayList<String> apks = new ArrayList<>();
        apks.add(info.sourceDir);
        if (info.splitSourceDirs != null) Collections.addAll(apks, info.splitSourceDirs);
        Method addDexPath = fusionPaths.getClass().getDeclaredMethod("addDexPath", String.class, File.class);
        addDexPath.setAccessible(true);
        addDexPath.invoke(fusionPaths, String.join(File.pathSeparator, apks), null);
        return new ContextWrapper(gameContext) {
            @Override public ClassLoader getClassLoader() { return fusionLoader; }
        };
    }

    public static void install(Context gameContext) throws ReflectiveOperationException {
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Method currentThread = threadClass.getDeclaredMethod("currentActivityThread");
        currentThread.setAccessible(true);
        Object thread = currentThread.invoke(null);
        Field instrumentation = threadClass.getDeclaredField("mInstrumentation");
        instrumentation.setAccessible(true);
        Instrumentation original = (Instrumentation) instrumentation.get(thread);
        if (!(original instanceof GameInstrumentation)) {
            instrumentation.set(thread, new GameInstrumentation(original, gameContext));
        }
        Log.i("TranslatedRuntime", "Installed activity loading");
    }

    private static final class GameInstrumentation extends Instrumentation {
        private final Instrumentation original;
        private final Context gameContext;
        private final Map<Activity, Intent> pendingIntents = Collections.synchronizedMap(new WeakHashMap<>());
        private final java.util.Set<Activity> gameActivities = Collections.newSetFromMap(new WeakHashMap<>());

        GameInstrumentation(Instrumentation original, Context gameContext) {
            this.original = original;
            this.gameContext = gameContext;
        }

        @Override public Activity newActivity(ClassLoader loader, String name, Intent intent)
                throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            intent.setExtrasClassLoader(gameContext.getClassLoader());
            if (!InstrumentationHooks.isDynamicIntent(intent)) return original.newActivity(loader, name, intent);
            Intent target = InstrumentationHooks.resolveOriginalIntent(intent);
            if (target == null || target.getComponent() == null) {
                throw new InstantiationException("Missing dynamic activity intent");
            }
            target = new Intent(target);
            target.setExtrasClassLoader(gameContext.getClassLoader());
            if (intent.hasExtra(InstrumentationHooks.EXTRA_FUSION_CONFIG)) {
                target.putExtra(InstrumentationHooks.EXTRA_FUSION_CONFIG,
                        intent.<android.os.Parcelable>getParcelableExtra(InstrumentationHooks.EXTRA_FUSION_CONFIG));
            }
            if (intent.hasExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION)) {
                target.putExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION,
                        intent.getIntExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION,
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
            }
            Activity activity = original.newActivity(gameContext.getClassLoader(),
                    target.getComponent().getClassName(), target);
            pendingIntents.put(activity, target);
            gameActivities.add(activity);
            return activity;
        }

        private void prepare(Activity activity) {
            Intent target = pendingIntents.remove(activity);
            if (target == null) return;
            try {
                activity.setIntent(target);
                Context fusionBase = activity.getBaseContext();
                Field base = ContextWrapper.class.getDeclaredField("mBase");
                base.setAccessible(true);
                base.set(activity, new CustomContextWrapper(gameContext, fusionBase));
                Field resources = ContextThemeWrapper.class.getDeclaredField("mResources");
                resources.setAccessible(true);
                resources.set(activity, null);
                ActivityInfo info = gameContext.getPackageManager().getActivityInfo(
                        new android.content.ComponentName(gameContext.getPackageName(), activity.getClass().getName()), 0);
                android.content.res.Resources.Theme theme = activity.getResources().newTheme();
                theme.applyStyle(info.getThemeResource() != 0 ? info.getThemeResource()
                        : android.R.style.Theme_Black_NoTitleBar, true);
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    activity.setTheme(theme);
                } else {
                    Field oldTheme = ContextThemeWrapper.class.getDeclaredField("mTheme");
                    oldTheme.setAccessible(true);
                    oldTheme.set(activity, theme);
                }
                InstrumentationHooks.applyTargetOrientation(activity);
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                Context loadingTheme = new ContextThemeWrapper(fusionBase, androidx.appcompat.R.style.Theme_AppCompat);
                decor.addView(LayoutInflater.from(loadingTheme).inflate(R.layout.loading_view, decor, false));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot prepare activity", e);
            }
        }

        @Override public void callActivityOnCreate(Activity activity, Bundle state) {
            prepare(activity);
            original.callActivityOnCreate(activity, state);
        }
        @Override public void callActivityOnCreate(Activity activity, Bundle state, PersistableBundle persistentState) {
            prepare(activity);
            original.callActivityOnCreate(activity, state, persistentState);
        }
        @Override public void callActivityOnResume(Activity activity) {
            if (gameActivities.contains(activity)) InstrumentationHooks.applyTargetOrientation(activity);
            original.callActivityOnResume(activity);
        }
        @Override public void callActivityOnNewIntent(Activity activity, Intent intent) {
            Intent target = InstrumentationHooks.resolveOriginalIntent(intent);
            original.callActivityOnNewIntent(activity, target != null ? target : intent);
        }
        @Override public void callActivityOnDestroy(Activity activity) {
            pendingIntents.remove(activity);
            gameActivities.remove(activity);
            original.callActivityOnDestroy(activity);
        }
        @Override public boolean onException(Object object, Throwable error) {
            return original.onException(object, error);
        }

        private Intent wrap(Intent intent) {
            if (intent == null || intent.getComponent() == null || InstrumentationHooks.isDynamicIntent(intent)) return intent;
            String packageName = intent.getComponent().getPackageName();
            String className = intent.getComponent().getClassName();
            if (!packageName.equals(gameContext.getPackageName()) &&
                    !packageName.equals(dev.allofus.fusioncore.BuildConfig.APPLICATION_ID)) return intent;
            if (className.startsWith("dev.allofus.fusioncore.")) return intent;
            return InstrumentationHooks.getInjectedIntent(intent);
        }

        private ActivityResult start(Class<?> targetType, Context who, IBinder contextThread,
                                     IBinder token, Object target, Intent intent, int requestCode,
                                     Bundle options, UserHandle user) {
            try {
                Class<?>[] types = user == null
                        ? new Class<?>[] {Context.class, IBinder.class, IBinder.class, targetType, Intent.class, int.class, Bundle.class}
                        : new Class<?>[] {Context.class, IBinder.class, IBinder.class, targetType, Intent.class, int.class, Bundle.class, UserHandle.class};
                Method method = Instrumentation.class.getDeclaredMethod("execStartActivity", types);
                method.setAccessible(true);
                Object[] args = user == null
                        ? new Object[] {who, contextThread, token, target, wrap(intent), requestCode, options}
                        : new Object[] {who, contextThread, token, target, wrap(intent), requestCode, options, user};
                return (ActivityResult) method.invoke(original, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new IllegalStateException("Cannot launch activity", cause);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot dispatch activity", e);
            }
        }

        public ActivityResult execStartActivity(Context who, IBinder thread, IBinder token,
                                               Activity target, Intent intent, int code, Bundle options) {
            return start(Activity.class, who, thread, token, target, intent, code, options, null);
        }
        public ActivityResult execStartActivity(Context who, IBinder thread, IBinder token,
                                               String target, Intent intent, int code, Bundle options) {
            return start(String.class, who, thread, token, target, intent, code, options, null);
        }
        public ActivityResult execStartActivity(Context who, IBinder thread, IBinder token,
                                               Activity target, Intent intent, int code, Bundle options, UserHandle user) {
            return start(Activity.class, who, thread, token, target, intent, code, options, user);
        }
    }
}