package dev.allofus.fusioncore;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Utilities {
    private static final String TAG = "FusionCore";

    public static Method findOnCreateMethod(Class<?> clazz) throws NoSuchMethodException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredMethod("onCreate", Bundle.class);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }

        String className = clazz != null ? clazz.getName() : "<unknown>";
        throw new NoSuchMethodException("onCreate(Bundle) not found for " + className);
    }

    public static void applyWindowInsets(View root, int basePadding) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int insetTop;
            int insetBottom;
            int insetLeft;
            int insetRight;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                insetTop = bars.top;
                insetBottom = bars.bottom;
                insetLeft = bars.left;
                insetRight = bars.right;
            } else {
                insetTop = insets.getSystemWindowInsetTop();
                insetBottom = insets.getSystemWindowInsetBottom();
                insetLeft = insets.getSystemWindowInsetLeft();
                insetRight = insets.getSystemWindowInsetRight();
            }

            v.setPadding(
                    basePadding + insetLeft,
                    basePadding + insetTop,
                    basePadding + insetRight,
                    basePadding + insetBottom
            );
            return insets;
        });
        root.requestApplyInsets();
    }

    public static String formatVersionText(String versionName, long versionCode) {
        if (versionCode > 0L) {
            return "v" + versionName + " (" + versionCode + ")";
        }
        return "v" + versionName;
    }

    public static void extractZipFromAssets(Context context, String assetName, File outputFolder) {
        try {
            if (!outputFolder.exists() && !outputFolder.mkdirs()) {
                throw new IOException("Failed to create output directory: " + outputFolder.getAbsolutePath());
            }

            String outputRoot = outputFolder.getCanonicalPath() + File.separator;
            byte[] buffer = new byte[8192];

            try (InputStream is = context.getAssets().open(assetName);
                 ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is))) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    String entryName = ze.getName();
                    if (entryName == null || entryName.isEmpty()) {
                        zis.closeEntry();
                        continue;
                    }

                    File target = new File(outputFolder, entryName);
                    String targetPath = target.getCanonicalPath();

                    if (!targetPath.startsWith(outputRoot)) {
                        throw new IOException("Blocked zip entry outside output folder: " + entryName);
                    }

                    if (ze.isDirectory()) {
                        if (!target.exists() && !target.mkdirs()) {
                            throw new IOException("Failed to create directory: " + targetPath);
                        }
                    } else {
                        File parent = target.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw new IOException("Failed to create parent directory: " + parent.getAbsolutePath());
                        }

                        try (FileOutputStream fos = new FileOutputStream(target)) {
                            int count;
                            while ((count = zis.read(buffer)) != -1) {
                                fos.write(buffer, 0, count);
                            }
                        }
                    }

                    zis.closeEntry();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract " + assetName + " from assets!", e);
        }
    }

    public static boolean copyAssets(AssetManager gameAssets, String assetPath, File outputFolder) {
        deleteRecursive(outputFolder);

        try {
            if (copyAssetEntry(gameAssets, assetPath, outputFolder)) {
                Log.i(TAG, "Successfully copied Unity Data assets to: " + outputFolder.getAbsolutePath());
            } else {
                Log.e(TAG, "Could not find Unity Data assets!");
                return false;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy Unity Data assets!", e);
            return false;
        }

        return true;
    }

    public static boolean copyAssetEntry(AssetManager gameAssets, String assetPath, File outputTarget) throws IOException {
        String[] children = gameAssets.list(assetPath);
        if (children == null) {
            return false;
        }

        if (children.length > 0) {
            if (!outputTarget.exists() && !outputTarget.mkdirs()) {
                return false;
            }

            for (String child : children) {
                File childTarget = new File(outputTarget, child);
                String childPath = assetPath + "/" + child;
                if (!copyAssetEntry(gameAssets, childPath, childTarget)) {
                    return false;
                }
            }
            return true;
        }

        File parent = outputTarget.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }

        byte[] buffer = new byte[8192];
        try (InputStream is = gameAssets.open(assetPath);
             OutputStream os = new FileOutputStream(outputTarget)) {
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        }

        return true;
    }

    public static boolean deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!deleteRecursive(f)) {
                        return false;
                    }
                }
            }
        }

        return file.delete();
    }
}
