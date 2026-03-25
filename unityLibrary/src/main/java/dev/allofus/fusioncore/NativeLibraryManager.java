package dev.allofus.fusioncore;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

import bitter.jnibridge.JNIBridge;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class NativeLibraryManager {
    private static final String TAG = "NativeLibraryManager";

    private static final ArrayList<String> GameLibraries = new ArrayList<>();

    private static final ArrayList<String> FusionLibraries = new ArrayList<>();

    private static final ArrayList<String> DataLibraries = new ArrayList<>();

    public static void addGameLibrary(String gameLibName)
    {
        GameLibraries.add(gameLibName);
    }

    public static void addFusionLibrary(String fusionLibName)
    {
        FusionLibraries.add(fusionLibName);
    }

    public static void addDataLibrary(String dataLibName)
    {
        DataLibraries.add(dataLibName);
    }

    public static void setupLibraryHooks(FusionConfig config) {
        Method findLibraryMethod = findLibraryMethodViaReflection();

        if (findLibraryMethod == null) {
            Log.wtf(TAG, "unable to hook findLibrary method");
            return;
        }

        Pine.hook(findLibraryMethod, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                var libName = callFrame.args[0].toString();

                Log.i(TAG, "beforeFindLibrary " + libName);

                for (String dataLib : DataLibraries) {
                    if (Objects.equals(libName, dataLib)) {
                        callFrame.setResult(config.appInternalDataDirectory + "/lib" + libName + ".so");
                        return;
                    }
                }

                for (String fusionLib : FusionLibraries) {
                    if (Objects.equals(libName, fusionLib)) {
                        callFrame.setResult(config.appLibraryDirectory + "/lib" + libName + ".so");
                        return;
                    }
                }

                for (String gameLib : GameLibraries) {
                    if (Objects.equals(libName, gameLib)) {
                        callFrame.setResult(config.gameLibraryDirectory + "/lib" + libName + ".so");
                        return;
                    }
                }
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
