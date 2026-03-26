package dev.allofus.fusioncore;

import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Objects;

import bitter.jnibridge.JNIBridge;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class ClassLoaderHooks {

    public static final String TAG = "ClassLoaderHooks";

    private static Method loadClassMethodViaReflection() {
        Method loadClassMethod = null;
        Class<?> clazz = Objects.requireNonNull(JNIBridge.class.getClassLoader()).getClass();

        while (loadClassMethod == null && clazz != null) {
            try {
                try {
                    Class.forName(clazz.getName(), true, JNIBridge.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    Log.wtf(TAG, "Class not found: " + clazz.getName(), e);
                }

                loadClassMethod = clazz.getDeclaredMethod("loadClass", String.class, boolean.class);
                loadClassMethod.setAccessible(true);
                Log.d(TAG, "Found loadClass method in class: " + clazz.getName());
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }

        return loadClassMethod;
    }

    public static void installHooks(ClassLoader gameClassLoader) {

        Method loadClassMethod = loadClassMethodViaReflection();

        Pine.hook(loadClassMethod, new MethodHook() {
            @Override
            public void afterCall(Pine.CallFrame callFrame) {
                try {
                    // Only attempt fallback if class was not found
                    if (callFrame.getThrowable() instanceof ClassNotFoundException) {
                        String className = (String) callFrame.args[0];
                        Log.d("ClassLoaderHooks", "afterLoadClass: class not found in default loader: " + className);

                        // Skip fallback if this is already the game classloader
                        if (callFrame.thisObject == gameClassLoader) {
                            Log.d("ClassLoaderHooks", "Skipping fallback: loader is already game classloader");
                            return;
                        }

                        // Attempt to load from game classloader, but don't let exceptions propagate to JNI
                        try {
                            Class<?> gameClass = gameClassLoader.loadClass(className);
                            callFrame.setResult(gameClass);
                            Log.d("ClassLoaderHooks", "Successfully loaded from game classloader: " + className);
                        } catch (ClassNotFoundException e) {
                            Log.d("ClassLoaderHooks", "Class not found in game classloader either: " + className);
                        } catch (Exception e) {
                            // Catch any other exceptions to prevent them from propagating to JNI
                            Log.w("ClassLoaderHooks", "Unexpected error loading class from game classloader: " + className, e);
                        }
                    }
                } catch (Exception e) {
                    Log.e("ClassLoaderHooks", "Unexpected error in afterCall (CRITICAL)", e);
                }
            }
        });
    }
}
