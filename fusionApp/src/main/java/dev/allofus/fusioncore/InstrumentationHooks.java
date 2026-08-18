package dev.allofus.fusioncore;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Hooks to Instrumentation.execStartActivity and Instrumentation.newActivity
 * for enabling dynamic loading of activities not declared in AndroidManifest.xml.
 */
public class InstrumentationHooks {

    private static final String TAG = "InstrumentationHooks";
    public static final String EXTRA_ORIENTATION = "fusioncore.target_orientation";
    public static final String EXTRA_IS_DYNAMIC_ACTIVITY = "fusioncore.is_dynamic_activity";
    public static final String EXTRA_ORIGINAL_INTENT = "fusioncore.original_intent";
    public static final String EXTRA_TARGET_ORIENTATION = "fusioncore.target_orientation";

    public static boolean areHooksInstalled = false;

    public static void install() {
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

            // Apply orientation if specified in the intent.
            hookAllMethodsByName(instrumentationClass, "callActivityOnCreate", new MethodHook() {
                @Override public void beforeCall(Pine.CallFrame callFrame) { handleCallActivityOnCreateBeforeCall(callFrame); }
            });
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

    private static void handleCallActivityOnCreateBeforeCall(Pine.CallFrame callFrame) {
        try {
            if (callFrame.args == null || callFrame.args.length == 0) return;
            Object arg0 = callFrame.args[0];
            if (!(arg0 instanceof Activity)) return;

            Activity activity = (Activity) arg0;
            Intent intent = activity.getIntent();
            if (intent != null && intent.hasExtra(EXTRA_ORIENTATION)) {
                int orientation = intent.getIntExtra(EXTRA_ORIENTATION, -1);
                if (orientation != -1) {
                    activity.setRequestedOrientation(orientation);
                    Log.d(TAG, "Applied target orientation " + orientation + " to " + activity.getClass().getName());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in callActivityOnCreate beforeCall", e);
        }
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
