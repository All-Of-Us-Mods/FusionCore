package dev.allofus.fusioncore;

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
        Log.i(TAG, "installHooks called, gameClassLoader: " + gameClassLoader);
    }
}