package dev.allofus.fusioncore.hooks;
import android.content.pm.PackageManager;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.Objects;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
public class PackageManagerHooks {
    private static final String TAG = "FusionCore";
    public static void installHooks(PackageManager manager) {
        try {
            hookSetComponentEnabledSetting(manager);
        } catch (Exception e) {
            Log.w(TAG, "Failed to install PackageManager hooks", e);
        }
    }

    public static void installTranslated(android.content.Context fusionContext,
                                         android.content.Context gameContext) throws Exception {
        PackageManager manager = fusionContext.getPackageManager();
        Class<?> managerClass = Class.forName("android.app.ApplicationPackageManager");
        java.lang.reflect.Field pmField = managerClass.getDeclaredField("mPM");
        pmField.setAccessible(true);
        Object original = pmField.get(manager);
        Class<?> pmInterface = Class.forName("android.content.pm.IPackageManager");
        java.util.Set<String> fusionComponents = new java.util.HashSet<>();
        android.content.pm.PackageInfo info = manager.getPackageInfo(fusionContext.getPackageName(),
                PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES |
                        PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);
        for (android.content.pm.ComponentInfo[] components : new android.content.pm.ComponentInfo[][] {
                info.activities, info.services, info.receivers, info.providers}) {
            if (components != null) for (android.content.pm.ComponentInfo component : components) {
                fusionComponents.add(component.name);
            }
        }
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(PackageManagerHooks.class.getClassLoader(),
                new Class<?>[] {pmInterface}, (object, method, args) -> {
                    if (method.getName().equals("setComponentEnabledSetting") && args != null &&
                            args[0] instanceof android.content.ComponentName component) {
                        boolean externalGame = component.getPackageName().equals(gameContext.getPackageName());
                        boolean missingFusion = component.getPackageName().equals(fusionContext.getPackageName()) &&
                                !fusionComponents.contains(component.getClassName());
                        if (externalGame || missingFusion) {
                            Log.d(TAG, "Skipping component enablement: " + component);
                            return null;
                        }
                    }
                    try {
                        return method.invoke(original, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        pmField.set(manager, proxy);
        pmField.set(gameContext.getPackageManager(), proxy);
        java.lang.reflect.Field cachedPm = Class.forName("android.app.ActivityThread")
                .getDeclaredField("sPackageManager");
        cachedPm.setAccessible(true);
        cachedPm.set(null, proxy);
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
                    // Check if this is a component we need to suppress
                    if (isKnownExternalComponent(componentName)) {
                        Log.d(TAG, "Suppressing setComponentEnabledSetting for external component: " + componentName);
                        // Prevent the call from executing by returning normally without invoking original
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
                        // Check if this is an IllegalArgumentException about a missing component
                        if (t instanceof IllegalArgumentException && t.getMessage() != null) {
                            String msg = t.getMessage();
                            if (msg.contains("does not exist") && msg.contains("Component class")) {
                                Log.d(TAG, "Suppressing component not found error: " + msg);
                                // Clear the exception so it doesn't propagate to JNI
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
        return true; //componentName != null && componentName.startsWith("com.google.android.play.core.assetpacks.");
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

                method = clazz.getDeclaredMethod("setComponentEnabledSetting", android.content.ComponentName.class, int.class, int.class);
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }

        return method;
    }
}
