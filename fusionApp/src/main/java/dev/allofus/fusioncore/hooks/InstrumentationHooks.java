package dev.allofus.fusioncore.hooks;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import dev.allofus.fusioncore.BuildConfig;
import dev.allofus.fusioncore.R;
import dev.allofus.fusioncore.StubActivity;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Hooks to Instrumentation.execStartActivity and Instrumentation.newActivity
 * for enabling dynamic loading of activities not declared in AndroidManifest.xml.
 */
public class InstrumentationHooks {

    private static final String TAG = "InstrumentationHooks";

    public static final String EXTRA_IS_DYNAMIC_ACTIVITY = "fusioncore.is_dynamic_activity";
    public static final String EXTRA_ORIGINAL_INTENT = "fusioncore.original_intent";
    public static final String EXTRA_TARGET_ORIENTATION = "fusioncore.target_orientation";

    public static boolean areHooksInstalled = false;

    public static void install(Context fusionContext) {
        if (areHooksInstalled) {
            Log.d(TAG, "Instrumentation hooks already installed");
            return;
        }

        try {
            Class<?> instrumentationClass = Instrumentation.class;

            // The execStartActivity hook will replace the unregistered activity with StubActivity.
            hookAllMethodsByName(instrumentationClass, "execStartActivity", new MethodHook() {
                @Override public void beforeCall(Pine.CallFrame callFrame) { handleExecStartBeforeCall(callFrame); }
            });

            // The newActivity hook restores the unregistered activity's intent from the StubActivity intent.
            hookAllMethodsByName(instrumentationClass, "newActivity", new MethodHook() {
                @Override public void beforeCall(Pine.CallFrame callFrame) { handleNewActivityBeforeCall(callFrame); }
            });

            hookActivityOnCreate(fusionContext);

            areHooksInstalled = true;
            Log.d(TAG, "Successfully installed Instrumentation hooks");
        } catch (Exception e) {
            Log.e(TAG, "Failed to install Instrumentation hooks", e);
        }
    }

    private static void hookAllMethodsByName(Class<?> clazz, String methodName, MethodHook hook) {
        try {
            Method[] methods = clazz.getDeclaredMethods();
            for (Method m : methods) {
                if (!m.getName().equals(methodName)) {
                    continue;
                }
                Pine.hook(m, hook);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to hook methods " + methodName + " for class " + clazz.getName());
        }
    }

    private static void hookActivityOnCreate(Context fusionContext) throws NoSuchMethodException {
        MethodHook orientationHook = new MethodHook() {
            @Override public void beforeCall(Pine.CallFrame callFrame) {
                if (!(callFrame.thisObject instanceof Activity)) {
                    return;
                }
                applyTargetOrientation((Activity) callFrame.thisObject);
            }
        };

        MethodHook loadingViewHook = new MethodHook() {
            @Override
            public void afterCall(Pine.CallFrame callFrame) throws Throwable {
                if (!(callFrame.thisObject instanceof Activity activity)) {
                    return;
                }

                ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
                Context themedFusionContext = new ContextThemeWrapper(fusionContext, androidx.appcompat.R.style.Theme_AppCompat);
                LayoutInflater inflater = LayoutInflater.from(themedFusionContext);
                View loadingView = inflater.inflate(R.layout.loading_view, decorView, false);
                decorView.addView(loadingView);
            }
        };

        Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
        Pine.hook(onCreate, orientationHook);
        Pine.hook(onCreate, loadingViewHook);

        Method onResume = Activity.class.getDeclaredMethod("onResume");
        Pine.hook(onResume, orientationHook);
    }

    private static void applyTargetOrientation(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent == null) {
                return;
            }
            int orientation = readTargetOrientation(intent);
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                return;
            }
            activity.setRequestedOrientation(orientation);
            Log.i(TAG, "Applied target orientation " + orientation
                    + " to " + activity.getClass().getName());
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply target orientation", e);
        }
    }

    private static int readTargetOrientation(Intent intent) {
        int orientation = intent.getIntExtra(EXTRA_TARGET_ORIENTATION,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (orientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            return orientation;
        }
        Intent original = resolveOriginalIntent(intent);
        if (original != null) {
            return original.getIntExtra(EXTRA_TARGET_ORIENTATION,
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }

    private static void handleExecStartBeforeCall(Pine.CallFrame callFrame) {
        try {
            int intentIdx = -1;

            if (callFrame.args != null) {
                for (int i = 0; i < callFrame.args.length; i++) {
                    Object arg = callFrame.args[i];
                    if (arg == null) continue;
                    if (Intent.class.isAssignableFrom(arg.getClass())) {
                        intentIdx = i;
                        break;
                    }
                }

                if (intentIdx < 0) {
                    Log.e(TAG, "No intent found in arguments for execStartActivity!");
                    return;
                }

                Intent intent = (Intent) callFrame.args[intentIdx];
                if (intent == null || intent.getComponent() == null) {
                    Log.e(TAG, "Intent or Intent component was null!");
                    return;
                }

                String targetClass = intent.getComponent().getClassName();

                if (isDynamicIntent(intent)) return;

                callFrame.args[intentIdx] = getInjectedIntent(intent);
                Log.d(TAG, "execStartActivity: intercepted unregistered activity: " + targetClass);
            } else {
                Log.e(TAG, "No arguments to handle execStartActivity!");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in execStartActivity beforeCall", e);
        }
    }

    private static void handleNewActivityBeforeCall(Pine.CallFrame callFrame) {
        try {
            if (callFrame.args == null) return;

            int intentIdx = -1;
            int strIdx = -1;

            for (int i = 0; i < callFrame.args.length; i++) {
                Object arg = callFrame.args[i];
                if (arg == null) continue;
                if (Intent.class.isAssignableFrom(arg.getClass())) {
                    intentIdx = i;
                }
                else if (String.class.isAssignableFrom(arg.getClass())) {
                    strIdx = i;
                }
            }

            if (intentIdx < 0 || strIdx < 0) {
                Log.e(TAG, "Intent or String not found in arguments!");
                return;
            }

            Intent intent = (Intent) callFrame.args[intentIdx];

            if (!isDynamicIntent(intent)) return;

            Intent original = resolveOriginalIntent(intent);

            if (original != null && original.getComponent() != null) {
                callFrame.args[intentIdx] = original;
                callFrame.args[strIdx] = original.getComponent().getClassName();
                Log.d(TAG, "newActivity: intercepted StubActivity for dynamic origin");
            } else {
                Log.e(TAG, "Failed to resolve original intent or component was null!");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in newActivity beforeCall", e);
        }
    }

    private static Intent resolveOriginalIntent(Intent currentIntent) {
        try {
            currentIntent.setExtrasClassLoader(InstrumentationHooks.class.getClassLoader());

            Intent originalIntent = currentIntent.getParcelableExtra(EXTRA_ORIGINAL_INTENT);

            if (originalIntent != null && originalIntent.getComponent() != null) {
                Log.d(TAG, "Resolved original intent for " + originalIntent.getComponent().getClassName());
                return originalIntent;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resolving original intent", e);
        }
        return null;
    }

    private static Intent getInjectedIntent(Intent intent) {
        Intent newIntent = new Intent(intent);
        newIntent.putExtra(EXTRA_IS_DYNAMIC_ACTIVITY, true);
        newIntent.putExtra(EXTRA_ORIGINAL_INTENT, intent);
        newIntent.setComponent(new ComponentName(BuildConfig.APPLICATION_ID, StubActivity.class.getName()));
        return newIntent;
    }

    private static boolean isDynamicIntent(Intent intent) {
        if (intent == null) return false;

        return intent.getBooleanExtra(EXTRA_IS_DYNAMIC_ACTIVITY, false);
    }
}
