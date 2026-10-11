package dev.allofus.fusioncore.tools;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class Utilities {
    private static final String TAG = "FusionCore";

    public static File getExternalFusionCoreDirectory(@Nullable String targetPackage, @Nullable String Folder) {
        File fusionStorage = new File(Environment.getExternalStorageDirectory(), "FusionCore");
        if (targetPackage != null) {
            fusionStorage = new File(fusionStorage, targetPackage);
        }
        if(Folder != null){
            fusionStorage = new File(fusionStorage, Folder);
        }
        if (!fusionStorage.exists()) {
            fusionStorage.mkdirs();
        }
        return fusionStorage;
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
    public static void extractZipFromStream(ZipInputStream zis, File outputFolder) throws IOException {
        extractZipFromStream(zis, outputFolder, null);
    }

    public static void extractZipFromStream(ZipInputStream zis, File outputFolder, @Nullable String keepExistingPrefix) throws IOException {
        if (!outputFolder.exists() && !outputFolder.mkdirs()) {
            throw new IOException("Failed to create output directory: " + outputFolder.getAbsolutePath());
        }
        byte[] buffer = new byte[8192];
            String outputRoot = outputFolder.getCanonicalPath() + File.separator;
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
                } else if (keepExistingPrefix != null && entryName.startsWith(keepExistingPrefix) && target.isFile()) {
                    Log.i(TAG, "Keeping existing " + targetPath);
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
    public static void extractZipFromAssets(Context context, String assetName, File outputFolder) {
        extractZipFromAssets(context, assetName, outputFolder, null);
    }

    public static void extractZipFromAssets(Context context, String assetName, File outputFolder, @Nullable String keepExistingPrefix) {
        try {
            try (InputStream is = context.getAssets().open(assetName);
                 ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is))) {
                  extractZipFromStream(zis, outputFolder, keepExistingPrefix);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract " + assetName + " from assets!", e);
        }
    }

    public static boolean copyAssets(ApplicationInfo gameInfo, File outputFolder) {
        File marker = new File(outputFolder, ".fusion_copied");
        if (marker.isFile()) {
            return true;
        }

        List<String> apks = new ArrayList<>();
        if (gameInfo.sourceDir != null) apks.add(gameInfo.sourceDir);
        if (gameInfo.splitSourceDirs != null) Collections.addAll(apks, gameInfo.splitSourceDirs);

        String outputRoot;
        try {
            outputRoot = outputFolder.getCanonicalPath() + File.separator;
        } catch (IOException e) {
            Log.e(TAG, "Failed to resolve " + outputFolder, e);
            return false;
        }

        int copied = 0;
        byte[] buffer = new byte[64 * 1024];
        for (String apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.startsWith("assets/")) continue;

                    File target = new File(outputFolder, name.substring("assets/".length()));
                    if (!target.getCanonicalPath().startsWith(outputRoot)) continue;
                    if (target.isFile() && target.length() == entry.getSize()) continue;

                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create " + parent);
                    }
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream out = new FileOutputStream(target)) {
                        int count;
                        while ((count = in.read(buffer)) > 0) {
                            out.write(buffer, 0, count);
                        }
                    }
                    copied++;
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to copy Unity Data assets from " + apk, e);
                return false;
            }
        }

        try {
            if (!marker.createNewFile()) {
                Log.w(TAG, "Failed to create asset copy marker: " + marker.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to create asset copy marker", e);
        }
        Log.i(TAG, "Successfully copied " + copied + " Unity Data assets to: " + outputFolder.getAbsolutePath());
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

    public static void extractZipFromInputStream(InputStream inputStream, File OutPutFolder) {
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(inputStream))) {
                extractZipFromStream(zis, OutPutFolder);
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract zip from InputStream!", e);
        }
    }
}
