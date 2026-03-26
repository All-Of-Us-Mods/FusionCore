package dev.allofus.fusioncore;
import android.content.pm.PackageManager;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.Objects;

import bitter.jnibridge.JNIBridge;
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
    private static boolean isKnownExternalComponent(String componentName) {
        // Google Play AssetPackDelivery components
        return componentName != null && componentName.startsWith("com.google.android.play.core.assetpacks.");
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
