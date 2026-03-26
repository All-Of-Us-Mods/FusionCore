package dev.allofus.fusioncore;

import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class LibUnityDownloader {
    private static final String TAG = "FusionCore";
    private static final String LIBUNITY_DOWNLOAD_URL = "https://unity.bepinex.dev/android/";
    private static final String LIBUNITY_CACHE_META_FILE = "libunity.cache.properties";
    private static final Pattern UNITY_BASE_VERSION_PATTERN = Pattern.compile("^(\\d+\\.\\d+\\.\\d+)");

    private LibUnityDownloader() {
    }

    public static boolean downloadAndCacheSafely(File outputDir, String version) {
        FutureTask<Boolean> task = new FutureTask<>(() -> downloadAndCache(outputDir, version));
        Thread worker = new Thread(task, "FusionCore-LibUnityDownload");
        worker.start();

        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Libunity download thread was interrupted", e);
            return false;
        } catch (ExecutionException e) {
            Log.e(TAG, "Libunity download failed", e.getCause() != null ? e.getCause() : e);
            return false;
        }
    }

    public static boolean downloadAndCache(File outputDir, String version) {
        if (outputDir == null || version == null || version.trim().isEmpty()) {
            Log.e(TAG, "downloadAndCache called with invalid arguments");
            return false;
        }

        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Log.e(TAG, "Failed to create output directory: " + outputDir.getAbsolutePath());
            return false;
        }

        if (Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0) {
            Log.e(TAG, "No supported ABIs detected on this device");
            return false;
        }

        File outputLibUnity = new File(outputDir, "libunity.so");
        File tempOutputLibUnity = new File(outputDir, "libunity.so.download");
        File cacheMetaFile = new File(outputDir, LIBUNITY_CACHE_META_FILE);
        String currentAbi = Build.SUPPORTED_ABIS[0];
        String trimmedVersion = version.trim();
        String downloadVersion = normalizeVersionForDownload(trimmedVersion);
        String cacheKey = downloadVersion + "|" + currentAbi;

        if (!trimmedVersion.equals(downloadVersion)) {
            Log.i(TAG, "Normalized Unity version for download URL: " + trimmedVersion + " -> " + downloadVersion);
        }

        if (isCachedLibUnityValid(outputLibUnity, cacheMetaFile, cacheKey)) {
            Log.i(TAG, "Using cached libunity for " + cacheKey + " at " + outputLibUnity.getAbsolutePath());
            return true;
        }

        String url = LIBUNITY_DOWNLOAD_URL + downloadVersion + "/" + currentAbi + ".zip";
        Log.i(TAG, "Downloading libunity from " + url);

        HttpURLConnection connection = null;
        boolean extracted = false;

        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                Log.e(TAG, "Failed to download libunity zip, HTTP " + statusCode);
                return false;
            }

            byte[] buffer = new byte[8192];
            try (InputStream is = new BufferedInputStream(connection.getInputStream());
                 ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        zis.closeEntry();
                        continue;
                    }

                    String entryName = entry.getName();
                    String fileName = entryName == null ? "" : new File(entryName).getName();
                    if (!"libunity.so".equals(fileName)) {
                        zis.closeEntry();
                        continue;
                    }

                    try (FileOutputStream fos = new FileOutputStream(tempOutputLibUnity, false)) {
                        int count;
                        while ((count = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, count);
                        }
                    }

                    extracted = true;
                    zis.closeEntry();
                    break;
                }
            }

            if (!extracted) {
                Log.e(TAG, "Downloaded zip did not contain libunity.so");
                return false;
            }

            if (outputLibUnity.exists() && !outputLibUnity.delete()) {
                Log.e(TAG, "Failed to replace existing libunity: " + outputLibUnity.getAbsolutePath());
                return false;
            }

            if (!tempOutputLibUnity.renameTo(outputLibUnity)) {
                Log.e(TAG, "Failed to move downloaded libunity into place");
                return false;
            }

            if (!writeLibUnityCacheMeta(cacheMetaFile, cacheKey, outputLibUnity.length())) {
                Log.w(TAG, "Downloaded libunity but failed to update cache metadata");
            }

            Log.i(TAG, "Successfully downloaded libunity to " + outputLibUnity.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to download libunity", e);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (tempOutputLibUnity.exists() && !outputLibUnity.exists() && !tempOutputLibUnity.delete()) {
                Log.w(TAG, "Failed to clean temporary libunity file: " + tempOutputLibUnity.getAbsolutePath());
            }
        }
    }

    private static boolean isCachedLibUnityValid(File outputLibUnity, File cacheMetaFile, String expectedCacheKey) {
        if (!outputLibUnity.exists() || !outputLibUnity.isFile() || outputLibUnity.length() <= 0) {
            return false;
        }
        if (!cacheMetaFile.exists() || !cacheMetaFile.isFile()) {
            return false;
        }

        Properties meta = new Properties();
        try (FileInputStream fis = new FileInputStream(cacheMetaFile)) {
            meta.load(fis);
        } catch (IOException e) {
            Log.w(TAG, "Failed reading libunity cache metadata", e);
            return false;
        }

        String actualKey = meta.getProperty("cacheKey", "");
        if (!expectedCacheKey.equals(actualKey)) {
            return false;
        }

        String sizeString = meta.getProperty("libunitySize", "0");
        try {
            long expectedSize = Long.parseLong(sizeString);
            return expectedSize > 0 && expectedSize == outputLibUnity.length();
        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid libunity cache metadata size", e);
            return false;
        }
    }

    private static boolean writeLibUnityCacheMeta(File cacheMetaFile, String cacheKey, long libunitySize) {
        Properties meta = new Properties();
        meta.setProperty("cacheKey", cacheKey);
        meta.setProperty("libunitySize", Long.toString(libunitySize));

        try (FileOutputStream fos = new FileOutputStream(cacheMetaFile, false)) {
            meta.store(fos, "libunity cache metadata");
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Failed writing libunity cache metadata", e);
            return false;
        }
    }

    private static String normalizeVersionForDownload(String version) {
        Matcher matcher = UNITY_BASE_VERSION_PATTERN.matcher(version);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return version;
    }
}

