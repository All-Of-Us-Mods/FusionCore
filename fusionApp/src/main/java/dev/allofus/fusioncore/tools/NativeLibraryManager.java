package dev.allofus.fusioncore.tools;


import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Array;
import java.io.File;
import java.util.Collection;
import java.util.List;
import android.system.Os;
import dalvik.system.BaseDexClassLoader;
import java.util.ArrayList;

import dev.allofus.fusioncore.BootstrapActivity;

public class NativeLibraryManager {
    private static volatile FusionConfig runtimeConfig;

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
        runtimeConfig = config;
        setupNativeLibraries(config);
    }

    public static String findLibrary(String name) {
        FusionConfig config = runtimeConfig;
        if (config == null) return null;
        String directory;
        if (FusionLibraries.contains(name)) directory = config.appLibraryDirectory;
        else if (CacheLibraries.contains(name)) directory = config.codeCacheDirectory;
        else directory = config.gameLibraryDirectory;
        File library = new File(directory, "lib" + name + ".so");
        return library.isFile() ? library.getAbsolutePath() : null;
    }

    private static void setupNativeLibraries(FusionConfig config) {
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

}
