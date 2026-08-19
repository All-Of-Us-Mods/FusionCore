package dev.allofus.fusioncore.tools;

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

public final class LibUnityDownloader {
    private static final String TAG = "FusionCore";
    private static final String LIBUNITY_DOWNLOAD_URL = "https://github.com/All-Of-Us-Mods/FusionCore.UnityDependencies/releases/download/";
    private static final String LIBUNITY_CACHE_META_FILE = "libunity.cache.properties";

    public interface DownloadProgressListener {
        void onDownloadStarted(String url, long totalBytes);
        void onDownloadProgress(long downloadedBytes, long totalBytes);
        void onDownloadFinished(boolean success, boolean usedCache);
    }

    public static boolean downloadAndCacheSafely(File outputDir,
                                                 String version,
                                                 String targetGameAbi,
                                                 DownloadProgressListener progressListener) {
        FutureTask<Boolean> task = new FutureTask<>(() -> downloadAndCache(outputDir, version, targetGameAbi, progressListener));
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

    public static boolean downloadAndCache(File outputDir,
                                           String version,
                                           String targetGameAbi,
                                           DownloadProgressListener progressListener) {
        if (outputDir == null || version == null || version.trim().isEmpty()) {
            Log.e(TAG, "downloadAndCache called with invalid arguments");
            notifyDownloadFinished(progressListener, false, false);
            return false;
        }

        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Log.e(TAG, "Failed to create output directory: " + outputDir.getAbsolutePath());
            notifyDownloadFinished(progressListener, false, false);
            return false;
        }

        String currentAbi = normalizeAbiForDownload(targetGameAbi);
        if (currentAbi == null) {
            Log.e(TAG, "Target game ABI is missing or unsupported: " + targetGameAbi);
            notifyDownloadFinished(progressListener, false, false);
            return false;
        }

        File outputLibUnity = new File(outputDir, "libunity.so");
        File outputLibUnitySym = new File(outputDir, "libunity.sym.so");
        File cacheMetaFile = new File(outputDir, LIBUNITY_CACHE_META_FILE);
        
        String downloadVersion = version.trim();
        String cacheKey = downloadVersion + "|" + currentAbi;

        if (isCachedLibUnityValid(outputLibUnity, outputLibUnitySym, cacheMetaFile, cacheKey)) {
            Log.i(TAG, "Using cached libunity and symbols for " + cacheKey + " at " + outputDir.getAbsolutePath());
            notifyDownloadFinished(progressListener, true, true);
            return true;
        }

        String baseUrl = LIBUNITY_DOWNLOAD_URL + downloadVersion + "/";
        String libUrl = baseUrl + "libunity.so." + currentAbi;
        String symUrl = baseUrl + "libunity.sym.so." + currentAbi;

        Log.i(TAG, "Downloading libunity and symbols from " + baseUrl);

        boolean libUnityDownloaded = downloadUrlToFile(libUrl, outputLibUnity, progressListener);
        if (!libUnityDownloaded) {
            notifyDownloadFinished(progressListener, false, false);
            return false;
        }

        boolean libUnitySymDownloaded = downloadUrlToFile(symUrl, outputLibUnitySym, progressListener);
        if (!libUnitySymDownloaded) {
            notifyDownloadFinished(progressListener, false, false);
            return false;
        }

        if (!writeLibUnityCacheMeta(cacheMetaFile, cacheKey, outputLibUnity.length(), outputLibUnitySym.length())) {
            Log.w(TAG, "Downloaded files but failed to update cache metadata");
        }

        Log.i(TAG, "Successfully downloaded libunity and symbols to " + outputDir.getAbsolutePath());
        notifyDownloadFinished(progressListener, true, false);
        return true;
    }

    private static boolean downloadUrlToFile(String urlString, File outputFile, DownloadProgressListener progressListener) {
        HttpURLConnection connection = null;
        File tempFile = new File(outputFile.getParentFile(), outputFile.getName() + ".download");
        
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                Log.e(TAG, "Failed to download file from " + urlString + ", HTTP " + statusCode);
                return false;
            }

            long totalBytes = connection.getContentLengthLong();
            notifyDownloadStarted(progressListener, urlString, totalBytes);

            byte[] buffer = new byte[8192];
            long downloadedBytes = 0L;
            long lastProgressDispatchMs = 0L;

            try (InputStream is = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream fos = new FileOutputStream(tempFile, false)) {
                int count;
                while ((count = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, count);
                    downloadedBytes += count;

                    long now = System.currentTimeMillis();
                    if (now - lastProgressDispatchMs >= 120L) {
                        notifyDownloadProgress(progressListener, downloadedBytes, totalBytes);
                        lastProgressDispatchMs = now;
                    }
                }
            }

            notifyDownloadProgress(progressListener, downloadedBytes, totalBytes);

            if (outputFile.exists() && !outputFile.delete()) {
                Log.e(TAG, "Failed to replace existing file: " + outputFile.getAbsolutePath());
                return false;
            }

            if (!tempFile.renameTo(outputFile)) {
                Log.e(TAG, "Failed to move downloaded file into place: " + outputFile.getAbsolutePath());
                return false;
            }

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to download " + urlString, e);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (tempFile.exists() && !tempFile.delete()) {
                Log.w(TAG, "Failed to clean temporary file: " + tempFile.getAbsolutePath());
            }
        }
    }

    private static void notifyDownloadStarted(DownloadProgressListener listener, String url, long totalBytes) {
        if (listener != null) {
            listener.onDownloadStarted(url, totalBytes);
        }
    }

    private static void notifyDownloadProgress(DownloadProgressListener listener, long downloadedBytes, long totalBytes) {
        if (listener != null) {
            listener.onDownloadProgress(downloadedBytes, totalBytes);
        }
    }

    private static void notifyDownloadFinished(DownloadProgressListener listener, boolean success, boolean usedCache) {
        if (listener != null) {
            listener.onDownloadFinished(success, usedCache);
        }
    }

    private static boolean isCachedLibUnityValid(File outputLibUnity, File outputLibUnitySym, File cacheMetaFile, String expectedCacheKey) {
        if (!outputLibUnity.exists() || !outputLibUnity.isFile() || outputLibUnity.length() <= 0) {
            return false;
        }
        if (!outputLibUnitySym.exists() || !outputLibUnitySym.isFile() || outputLibUnitySym.length() <= 0) {
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

        try {
            long expectedSize = Long.parseLong(meta.getProperty("libunitySize", "0"));
            long expectedSymSize = Long.parseLong(meta.getProperty("libunitySymSize", "0"));
            return expectedSize > 0 && expectedSize == outputLibUnity.length() &&
                   expectedSymSize > 0 && expectedSymSize == outputLibUnitySym.length();
        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid libunity cache metadata size", e);
            return false;
        }
    }

    private static boolean writeLibUnityCacheMeta(File cacheMetaFile, String cacheKey, long libunitySize, long libunitySymSize) {
        Properties meta = new Properties();
        meta.setProperty("cacheKey", cacheKey);
        meta.setProperty("libunitySize", Long.toString(libunitySize));
        meta.setProperty("libunitySymSize", Long.toString(libunitySymSize));

        try (FileOutputStream fos = new FileOutputStream(cacheMetaFile, false)) {
            meta.store(fos, "libunity cache metadata");
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Failed writing libunity cache metadata", e);
            return false;
        }
    }

    private static String normalizeAbiForDownload(String abiValue) {
        if (abiValue == null) {
            return null;
        }

        String normalized = abiValue.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return null;
        }

        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash < normalized.length() - 1) {
            normalized = normalized.substring(slash + 1);
        }

        int backslash = normalized.lastIndexOf('\\');
        if (backslash >= 0 && backslash < normalized.length() - 1) {
            normalized = normalized.substring(backslash + 1);
        }

        return switch (normalized) {
            case "arm64", "aarch64", "arm64-v8a" -> "arm64-v8a";
            case "armeabi-v7a", "armeabi", "armv7" -> "armeabi-v7a";
            case "x86" -> "x86";
            case "x86_64", "x64" -> "x86_64";
            default -> null;
        };
    }
}
