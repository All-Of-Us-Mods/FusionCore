package dev.allofus.fusioncore;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.HashMap;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Hooks to Instrumentation.execStartActivity and Instrumentation.newActivity
 * for enabling dynamic loading of activities not declared in AndroidManifest.xml.
 */
public class InstrumentationHooks {

    private static final String TAG = "InstrumentationHooks";
    private static final String EXTRA_DYNAMIC_ACTIVITY_ORIGIN = "fusioncore.dynamic_activity_origin";
    private static final String EXTRA_ORIGINAL_INTENT = "fusioncore.original_intent";

    public static boolean areHooksInstalled = false;

    private static final Map<Integer, Intent> DYNAMIC_INTENT_STASH = new HashMap<>();

    public static void install(Context context) {
        if (areHooksInstalled) {
            Log.d(TAG, "Instrumentation hooks already installed");
            return;
        }

        try {
            hookExecStartActivity();
            hookNewActivity();

            areHooksInstalled = true;
            Log.d(TAG, "Successfully installed Instrumentation hooks");
        } catch (Exception e) {
            Log.e(TAG, "Failed to install Instrumentation hooks", e);
        }
    }

    private static void hookExecStartActivity() {
        Class<?> instrumentationClass = Instrumentation.class;

        try {
            Method[] methods = instrumentationClass.getDeclaredMethods();
            for (Method m : methods) {
                if (!m.getName().equals("execStartActivity")) {
                    continue;
                }
                Pine.hook(m, new MethodHook() {
                    @Override public void beforeCall(Pine.CallFrame callFrame) { handleExecStartBeforeCall(callFrame); }
                    @Override public void afterCall(Pine.CallFrame callFrame) {}
                });
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to hook newActivity methods");
        }
    }

    private static void hookNewActivity() {
        Class<?> instrumentationClass = Instrumentation.class;

        try {
            Method[] methods = instrumentationClass.getDeclaredMethods();
            for (Method m : methods) {
                if (!m.getName().equals("newActivity")) {
                    continue;
                }
                Pine.hook(m, new MethodHook() {
                    @Override public void beforeCall(Pine.CallFrame callFrame) { handleNewActivityBeforeCall(callFrame); }
                    @Override public void afterCall(Pine.CallFrame callFrame) {}
                });
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to hook newActivity methods");
        }
    }

    private static void handleExecStartBeforeCall(Pine.CallFrame callFrame) {
        try {
            int index = -1;
            Intent intent = null;
            String targetClass = null;

            if (callFrame.args != null) {
                for (Object arg : callFrame.args) {
                    index++;
                    if (arg == null) continue;
                    if (Intent.class.isAssignableFrom(arg.getClass())) {
                        intent = (Intent) arg;
                        break;
                    }
                }

                if (intent != null) {
                    targetClass = intent.getComponent().getClassName();
                }
            }

            if (intent == null || targetClass == null) {
                Log.e(TAG, "intent or targetClass was null!");
                return;
            }

            String originKey = getDynamicActivityOrigin(intent);
            if (originKey != null && originKey.startsWith("stub:")) return;

            /*if (!isTargetUnregistered(targetClass)) {
                Log.d(TAG, "execStartActivity: registered target: " + targetClass);
                return;
            }*/

            callFrame.args[index] = markAsDynamicLaunch(intent, targetClass);
            Log.d(TAG, "execStartActivity: intercepted unregistered activity: " + targetClass);
        } catch (Exception e) {
            Log.e(TAG, "Error in execStartActivity beforeCall", e);
        }
    }

    private static void handleNewActivityBeforeCall(Pine.CallFrame callFrame) {
        try {
            if (callFrame.args != null) {
                int intentIdx = -1;
                int strIdx = -1;
                for (int i = 0; i < callFrame.args.length; i++) {
                    Object arg = callFrame.args[i];
                    if (arg == null) continue;
                    if (Intent.class.isAssignableFrom(arg.getClass())) {
                        intentIdx = i;
                    }
                    if (String.class.isAssignableFrom(arg.getClass())) {
                        strIdx = i;
                    }
                }

                if (intentIdx < 0 || strIdx < 0) {
                    Log.e(TAG, "Intent or String not found!");
                }

                Intent intent = (Intent) callFrame.args[intentIdx];
                String dynamicOrigin = getDynamicActivityOrigin(intent);
                if (dynamicOrigin != null && dynamicOrigin.startsWith("stub:")) {
                    Intent original = resolveOriginalIntent(intent);
                    callFrame.args[intentIdx] = original;
                    callFrame.args[strIdx] = original.getComponent().getClassName();
                    Log.d(TAG, "newActivity: intercepted StubActivity for dynamic origin");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in newActivity beforeCall", e);
        }
    }

    private static Intent markAsDynamicLaunch(Intent intent, String targetClass) {
        ComponentName componentName = new ComponentName(BootstrapActivity.class.getPackage().getName(), targetClass);
        String originKey = EXTRA_DYNAMIC_ACTIVITY_ORIGIN + ":" + componentName.flattenToString();

        Intent newIntent = new Intent(intent);
        newIntent.putExtra(EXTRA_DYNAMIC_ACTIVITY_ORIGIN, originKey);
        newIntent.putExtra(EXTRA_ORIGINAL_INTENT, intent);
        newIntent.setComponent(new ComponentName(StubActivity.class.getPackage().getName(), StubActivity.class.getName()));
        return newIntent;
    }

    private static String getDynamicActivityOrigin(Intent intent) {
        if (intent == null) return null;

        try {
            String originKey = intent.getStringExtra(EXTRA_DYNAMIC_ACTIVITY_ORIGIN);
            if (originKey != null && originKey.startsWith(EXTRA_DYNAMIC_ACTIVITY_ORIGIN + ":")) {
                int colonIndex = originKey.indexOf(":");
                String componentString = originKey.substring(colonIndex + 1);
                String[] parts = componentString.split("/", 2);
                if (parts.length == 2) {
                    return "stub:" + parts[0] + "/" + parts[1];
                }
            }
        } catch (Exception e) { /* Ignore */ }

        try {
            String className = intent.getStringExtra("fusioncore.original_component");
            if (className != null) {
                return "stub:" + BootstrapActivity.class.getPackage().getName() + "/" + className;
            }
        } catch (Exception e) { /* Ignore */ }

        return null;
    }

    private static Intent resolveOriginalIntent(Intent currentIntent) {
        try {
            Object originalIntentObj = currentIntent.getExtras().get(EXTRA_ORIGINAL_INTENT);
            if (originalIntentObj instanceof Intent originalIntent) {

                Log.d(TAG, "Resolved original intent for "+ originalIntent.getComponent().getClassName());
                return originalIntent;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resolving original intent", e);
        }
        return null;
    }


}
