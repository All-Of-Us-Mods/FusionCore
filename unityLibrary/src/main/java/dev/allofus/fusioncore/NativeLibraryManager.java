package dev.allofus.fusioncore;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Objects;

import bitter.jnibridge.JNIBridge;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class NativeLibraryManager {
    private static final String TAG = "NativeLibraryManager";

    // Libraries to get from our native library directory
    private static final String[] FusionLibraries = new String[]{
            // "unity",
            "main",
    };

    public static void setupLibraryHooks(FusionConfig config) {
        Method findLibraryMethod = findLibraryMethodViaReflection();

        if (findLibraryMethod == null) {
            Log.wtf(TAG, "unable to hook findLibrary method");
            return;
        }

        Pine.hook(findLibraryMethod, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                Log.i(TAG, "findLibrary called for " + callFrame.args[0]);

                for (String libName : FusionLibraries) {
                    if (Objects.equals(libName, callFrame.args[0])) {
                        callFrame.setResult(config.appLibraryDirectory + "/lib" + libName + ".so");
                        return;
                    }
                }

                callFrame.setResult(config.gameLibraryDirectory + "/lib" + callFrame.args[0] + ".so");
            }

            @Override
            public void afterCall(Pine.CallFrame callFrame) {
                if (callFrame.hasThrowable()) {
                    Log.wtf(TAG, "findLibrary threw an exception for " + callFrame.args[0], callFrame.getThrowable());
                }
            }
        });
    }

    private static Method findLibraryMethodViaReflection() {
        Method findLibraryMethod = null;
        Class<?> clazz = Objects.requireNonNull(JNIBridge.class.getClassLoader()).getClass();

        while (findLibraryMethod == null && clazz != null) {
            try {
                try {
                    Class.forName(clazz.getName(), true, JNIBridge.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    Log.wtf(TAG, "Class not found: " + clazz.getName(), e);
                }

                findLibraryMethod = clazz.getDeclaredMethod("findLibrary", String.class);
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }

        return findLibraryMethod;
    }
}
