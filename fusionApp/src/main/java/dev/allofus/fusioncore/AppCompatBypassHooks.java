package dev.allofus.fusioncore;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public final class AppCompatBypassHooks {
    private static final String TAG = "AppCompatBypassHooks";

    private static final Set<String> TARGET_METHODS = new HashSet<>(Arrays.asList(
            "setContentView",
            "findViewById",
            "addContentView",
            "getMenuInflater",
            "onPostCreate",           // triggers ensureSubDecor → theme assertion
            "onPostResume",           // delegate.onPostResume → applyDayNight → ensureSubDecor
            "onConfigurationChanged", // delegate routes this too
            "onStop",
            "onDestroy",
            "onTitleChanged",
            "onMenuOpened",
            "onPanelClosed"
    ));

    // Activity#mCalled is set by Activity.onPostCreate's super impl. Instrumentation
    // checks it after dispatch and throws SuperNotCalledException if false. Since we
    // suppress the entire override (Method.invoke on the super method does virtual
    // dispatch and would recurse back into us), set the field directly.
    private static Field sActivityMCalled;

    static {
        try {
            sActivityMCalled = Activity.class.getDeclaredField("mCalled");
            sActivityMCalled.setAccessible(true);
        } catch (NoSuchFieldException e) {
            Log.e(TAG, "Could not cache Activity#mCalled", e);
        }
    }

    private AppCompatBypassHooks() {}

    public static void installHooks(ClassLoader gameClassLoader, String launcherClassName) {
        Class<?> launcher;
        try {
            launcher = gameClassLoader.loadClass(launcherClassName);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "Launcher class not found: " + launcherClassName, e);
            return;
        }

        Class<?> current = launcher;
        while (current != null && current != Activity.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (!TARGET_METHODS.contains(method.getName())) continue;
                int mods = method.getModifiers();
                // Pine can't hook abstract or native methods. ComponentActivity declares
                // setContentView(int) as abstract, which would otherwise abort the loop.
                if (Modifier.isAbstract(mods) || Modifier.isNative(mods)) continue;
                try {
                    hook(method, current);
                } catch (Throwable t) {
                    Log.w(TAG, "Skipping unhookable method "
                            + current.getName() + "#" + method.getName(), t);
                }
            }
            current = current.getSuperclass();
        }
    }

    private static void hook(Method method, Class<?> declaringClass) {
        method.setAccessible(true);
        Pine.hook(method, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                if (!(callFrame.thisObject instanceof Activity)) return;

                Activity activity = (Activity) callFrame.thisObject;
                Window window = activity.getWindow();
                Object[] args = callFrame.args == null ? new Object[0] : callFrame.args;
                String name = method.getName();

                try {
                    switch (name) {
                        case "setContentView":
                            if (args.length == 1 && args[0] instanceof View) {
                                window.setContentView((View) args[0]);
                            } else if (args.length == 1 && args[0] instanceof Integer) {
                                window.setContentView((Integer) args[0]);
                            } else if (args.length == 2
                                    && args[0] instanceof View
                                    && args[1] instanceof ViewGroup.LayoutParams) {
                                window.setContentView((View) args[0], (ViewGroup.LayoutParams) args[1]);
                            } else {
                                return;
                            }
                            callFrame.setResult(null);
                            break;

                        case "findViewById":
                            if (args.length == 1 && args[0] instanceof Integer) {
                                callFrame.setResult(window.findViewById((Integer) args[0]));
                            }
                            break;

                        case "addContentView":
                            if (args.length == 2
                                    && args[0] instanceof View
                                    && args[1] instanceof ViewGroup.LayoutParams) {
                                window.addContentView((View) args[0], (ViewGroup.LayoutParams) args[1]);
                                callFrame.setResult(null);
                            }
                            break;

                        case "getMenuInflater":
                            callFrame.setResult(LayoutInflater.from(activity));
                            break;

                        case "onPostCreate":
                            // Suppress the AppCompat override entirely. Reflection-invoking
                            // Activity#onPostCreate would virtually dispatch back into this
                            // same hook (infinite recursion), so instead satisfy the
                            // Instrumentation contract by flipping mCalled directly.
                            if (sActivityMCalled != null) {
                                sActivityMCalled.setBoolean(activity, true);
                            }
                            callFrame.setResult(null);
                            break;

                        case "onPostResume":
                            // Activity.onPostResume's super impl calls window.makeActive()
                            // (required so input dispatching works) and sets mCalled = true.
                            // Replicate both, then suppress the AppCompat override.
                            if (window != null) {
                                window.makeActive();
                            }
                            if (sActivityMCalled != null) {
                                sActivityMCalled.setBoolean(activity, true);
                            }
                            callFrame.setResult(null);
                            break;

                        // Lifecycle hooks that the framework checks mCalled on after dispatch.
                        // The AppCompat override calls delegate.onX() which trips applyDayNight
                        // → ensureSubDecor → theme assertion. Suppress the override entirely
                        // and flip mCalled so Instrumentation doesn't throw SuperNotCalled.
                        case "onStop":
                        case "onDestroy":
                        case "onConfigurationChanged":
                            if (sActivityMCalled != null) {
                                sActivityMCalled.setBoolean(activity, true);
                            }
                            callFrame.setResult(null);
                            break;

                        // No mCalled check on these - just suppress the AppCompat path.
                        case "onTitleChanged":
                        case "onMenuOpened":
                        case "onPanelClosed":
                            callFrame.setResult(null);
                            break;
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Bypass for " + name + " failed", t);
                }
            }
        });
        Log.i(TAG, "Hooked " + declaringClass.getName() + "#" + method.getName());
    }
}