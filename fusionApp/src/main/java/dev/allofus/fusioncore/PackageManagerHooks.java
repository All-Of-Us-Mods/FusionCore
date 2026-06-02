package dev.allofus.fusioncore;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class PackageManagerHooks {
    private static final String TAG = "FusionCore";
    private static final String FAKE_PKG = "com.AnotherAxiom.GorillaTag";

    public static void installHooks(PackageManager manager) {
        try {
            hookSetComponentEnabledSetting(manager);
        } catch (Exception e) {
            Log.w(TAG, "Failed to install setComponentEnabledSetting hook", e);
        }
        try {
            hookGetPackageInfo(manager);
        } catch (Exception e) {
            Log.w(TAG, "Failed to install getPackageInfo hooks", e);
        }
    }

    private static void hookGetPackageInfo(PackageManager manager) {
        MethodHook patchPackageInfo = new MethodHook() {
            @Override
            public void afterCall(Pine.CallFrame callFrame) {
                PackageInfo info = (PackageInfo) callFrame.getResult();
                if (info != null) {
                    info.packageName = FAKE_PKG;
                    if (info.applicationInfo != null) {
                        info.applicationInfo.packageName = FAKE_PKG;
                    }
                }
            }
        };

        Class<?> clazz = manager.getClass();
        while (clazz != null && clazz != Object.class) {
            // Handle both variants (int flags and PackageInfoFlags)
            try {
                Method[] methods = clazz.getDeclaredMethods();
                for (Method m : methods) {
                    if (m.getName().equals("getPackageInfo")) {
                        // CRITICAL: Check if the method is abstract
                        if (Modifier.isAbstract(m.getModifiers())) {
                            continue;
                        }

                        // Check parameter counts to distinguish versions
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 2 && params[0] == String.class) {
                            Pine.hook(m, patchPackageInfo);
                            Log.i(TAG, "Hooked concrete getPackageInfo on " + clazz.getName());
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error while scanning " + clazz.getName(), e);
            }
            clazz = clazz.getSuperclass();
        }
    }

    // this prevents android from freaking out about components that only exist in the game manifest
    // without it, android wont allow those components to be used
    private static void hookSetComponentEnabledSetting(PackageManager manager) {
        Method method = findMethodViaReflection(manager);
        if (method == null) {
            Log.w(TAG, "Failed to find setComponentEnabledSetting method via reflection");
            return;
        }

        Pine.hook(method, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                try {
                    android.content.ComponentName component = (android.content.ComponentName) callFrame.args[0];
                    String componentName = component != null ? component.getClassName() : "unknown";
                    if (isKnownExternalComponent(componentName)) {
                        Log.d(TAG, "Suppressing setComponentEnabledSetting for external component: " + componentName);
                        callFrame.setResult(null);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in PackageManager hook beforeCall", e);
                }
            }

            @Override
            public void afterCall(Pine.CallFrame callFrame) {
                try {
                    if (callFrame.hasThrowable()) {
                        Throwable t = callFrame.getThrowable();
                        if (t instanceof IllegalArgumentException && t.getMessage() != null) {
                            String msg = t.getMessage();
                            if (msg.contains("does not exist") && msg.contains("Component class")) {
                                Log.d(TAG, "Suppressing component not found error: " + msg);
                                callFrame.setThrowable(null);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in PackageManager hook afterCall", e);
                }
            }
        });
    }

    // temp true for testing, should be replaced with actual component name checks
    private static boolean isKnownExternalComponent(String componentName) {
        return true;
    }

    private static Method findMethodViaReflection(PackageManager manager) {
        Method method = null;
        Class<?> clazz = Objects.requireNonNull(manager).getClass();

        while (method == null && clazz != null) {
            try {
                try {
                    Class.forName(clazz.getName(), true, clazz.getClassLoader());
                } catch (ClassNotFoundException e) {
                    Log.wtf(TAG, "Class not found: " + clazz.getName(), e);
                }
                method = clazz.getDeclaredMethod("setComponentEnabledSetting",
                        android.content.ComponentName.class, int.class, int.class);
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }

        return method;
    }
}