package dev.allofus.fusioncore.tools;

import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Array;
import java.io.File;
import java.util.Collection;
import java.util.List;
import android.system.Os;
import dalvik.system.BaseDexClassLoader;
import java.util.ArrayList;
import java.util.Objects;

import dev.allofus.fusioncore.BootstrapActivity;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class NativeLibraryManager {
    private static final String TAG = "NativeLibraryManager";

    private static final ArrayList<String> FusionLibraries = new ArrayList<>();

    private static final ArrayList<String> GameLibraries = new ArrayList<>();

    private static final ArrayList<String> CacheLibraries = new ArrayList<>();

    public static void addFusionLibrary(String fusionLibName)
    {
        FusionLibraries.add(fusionLibName);
    }

    public static void addGameLibrary(String gameLibName)
    {
        GameLibraries.add(gameLibName);
    }

    public static void addCacheLibrary(String dataLibName)
    {
        CacheLibraries.add(dataLibName);
    }

    // this redirects library loading to the libraries we want the game to use
    public static void setupLibraryHooks(FusionConfig config) {
        if (NativePlatform.isArmTranslation()) {
            setupTranslatedLibraries(config);
            return;
        }
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

                for (String fusionLib : FusionLibraries) {
                    if (Objects.equals(libName, fusionLib)) {
                        callFrame.setResult(config.appLibraryDirectory + "/lib" + libName + ".so");
                        return;
                    }
                }

                for (String dataLib : CacheLibraries) {
                    if (Objects.equals(libName, dataLib)) {
                        callFrame.setResult(config.codeCacheDirectory + "/lib" + libName + ".so");
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

    private static void setupTranslatedLibraries(FusionConfig config) {
        try {
            File overrides = new File(config.codeCacheDirectory, "native-overrides");
            if (!overrides.isDirectory() && !overrides.mkdirs()) {
                throw new IllegalStateException("Cannot create native library overrides");
            }
            for (String name : CacheLibraries) linkLibrary(overrides, name, config.codeCacheDirectory);
            for (String name : FusionLibraries) linkLibrary(overrides, name, config.appLibraryDirectory);

            Field pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(BootstrapActivity.class.getClassLoader());
            Method addNativePath = pathList.getClass().getDeclaredMethod("addNativePath", Collection.class);
            addNativePath.setAccessible(true);
            prependNativePath(pathList, addNativePath, config.gameLibraryDirectory);
            prependNativePath(pathList, addNativePath, overrides.getAbsolutePath());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot configure libraries", e);
        }
    }

    private static void linkLibrary(File directory, String name, String source) throws Exception {
        File link = new File(directory, "lib" + name + ".so");
        if (!link.delete()) {
            try {
                Os.lstat(link.getAbsolutePath());
                throw new IllegalStateException("Cannot replace " + link);
            } catch (android.system.ErrnoException e) {
                if (e.errno != android.system.OsConstants.ENOENT) throw e;
            }
        }
        Os.symlink(source + "/lib" + name + ".so", link.getAbsolutePath());
    }

    private static void prependNativePath(Object pathList, Method addNativePath, String path)
            throws ReflectiveOperationException {
        Field elementsField = pathList.getClass().getDeclaredField("nativeLibraryPathElements");
        elementsField.setAccessible(true);
        int previousCount = Array.getLength(elementsField.get(pathList));
        addNativePath.invoke(pathList, List.of(path));
        Object elements = elementsField.get(pathList);
        int count = Array.getLength(elements);
        if (count == previousCount) return;
        Object added = Array.get(elements, count - 1);
        for (int i = count - 1; i > 0; i--) Array.set(elements, i, Array.get(elements, i - 1));
        Array.set(elements, 0, added);
    }

    private static Method findLibraryMethodViaReflection() {
        Method findLibraryMethod = null;
        Class<?> clazz = Objects.requireNonNull(BootstrapActivity.class.getClassLoader()).getClass();

        while (findLibraryMethod == null && clazz != null) {
            try {
                try {
                    Class.forName(clazz.getName(), true, BootstrapActivity.class.getClassLoader());
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
