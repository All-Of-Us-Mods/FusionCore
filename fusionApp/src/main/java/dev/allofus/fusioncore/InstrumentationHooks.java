package dev.allofus.fusioncore;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
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
    private static final String EXTRA_DYNAMIC_ACTIVITY_ORIGIN = "fusioncore.dynamic_activity_origin";
    private static final String EXTRA_ORIGINAL_INTENT = "fusioncore.original_intent";

    public static boolean areHooksInstalled = false;

    public static void install() {
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
                    @Override public void afterCall(Pine.CallFrame callFrame) { }
                });
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to hook newActivity methods");
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
                String targetClass = intent.getComponent().getClassName();

                String originKey = getOriginKeyForIntent(intent);
                if (originKey != null && originKey.startsWith("stub:")) return;

                /*if (!isTargetUnregistered(targetClass)) {
                    Log.d(TAG, "execStartActivity: registered target: " + targetClass);
                    return;
                }*/

                callFrame.args[intentIdx] = getInjectedIntent(intent, targetClass);
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
            String dynamicOrigin = getOriginKeyForIntent(intent);

            if (dynamicOrigin != null && dynamicOrigin.startsWith("stub:")) {
                Intent original = resolveOriginalIntent(intent);

                if (original != null && original.getComponent() != null) {
                    callFrame.args[intentIdx] = original;
                    callFrame.args[strIdx] = original.getComponent().getClassName();
                    Log.d(TAG, "newActivity: intercepted StubActivity for dynamic origin");
                } else {
                    Log.e(TAG, "Failed to resolve original intent or component was null!");
                }
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

    private static Intent getInjectedIntent(Intent intent, String targetClass) {
        ComponentName componentName = new ComponentName(BootstrapActivity.class.getPackage().getName(), targetClass);
        String originKey = EXTRA_DYNAMIC_ACTIVITY_ORIGIN + ":" + componentName.flattenToString();

        Intent newIntent = new Intent(intent);
        newIntent.putExtra(EXTRA_DYNAMIC_ACTIVITY_ORIGIN, originKey);
        newIntent.putExtra(EXTRA_ORIGINAL_INTENT, intent);
        newIntent.setComponent(new ComponentName(StubActivity.class.getPackage().getName(), StubActivity.class.getName()));
        return newIntent;
    }

    private static String getOriginKeyForIntent(Intent intent) {
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
}
