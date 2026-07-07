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
        String requestedVersion = normalizeVersionForDownload(trimmedVersion);

        String downloadVersion = resolveBestAvailableVersion(requestedVersion);
        if (downloadVersion == null) {
            downloadVersion = requestedVersion;
        }
        String cacheKey = downloadVersion + "|" + currentAbi;

        if (!downloadVersion.equals(requestedVersion)) {
            Log.i(TAG, "libunity " + requestedVersion + " not hosted; using nearest available <= detected: " + downloadVersion);
        } else if (!trimmedVersion.equals(downloadVersion)) {
            Log.i(TAG, "Normalized Unity version for download URL: " + trimmedVersion + " -> " + downloadVersion);
        }

        if (isCachedLibUnityValid(outputLibUnity, cacheMetaFile, cacheKey)) {
            Log.i(TAG, "Using cached libunity for " + cacheKey + " at " + outputLibUnity.getAbsolutePath());
            patchLibUnityVersionIfNeeded(outputLibUnity, downloadVersion, requestedVersion, trimmedVersion);
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

            patchLibUnityVersionIfNeeded(outputLibUnity, downloadVersion, requestedVersion, trimmedVersion);

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

    private static final Pattern INDEX_VERSION_PATTERN =
            Pattern.compile("href=\"\\.?/?(\\d+)\\.(\\d+)\\.(\\d+)/\"");

    private static final String INTEROP_LIBRARIES_URL = "https://unity.bepinex.dev/libraries/";
    private static final int INTEROP_MAX_PROBES = 12;

    public static boolean ensureInteropBaseLibrariesSafely(File unityLibsDir, String gameVersion) {
        FutureTask<Boolean> task = new FutureTask<>(() -> ensureInteropBaseLibraries(unityLibsDir, gameVersion));
        Thread worker = new Thread(task, "FusionCore-InteropLibs");
        worker.start();

        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Interop base libraries thread was interrupted", e);
            return false;
        } catch (ExecutionException e) {
            Log.e(TAG, "Interop base libraries provisioning failed", e.getCause() != null ? e.getCause() : e);
            return false;
        }
    }

    public static boolean ensureInteropBaseLibraries(File unityLibsDir, String gameVersion) {
        if (unityLibsDir == null || gameVersion == null) {
            return false;
        }
        String gameBase = normalizeVersionForDownload(gameVersion.trim());
        File target = new File(unityLibsDir, gameBase + ".zip");
        if (target.isFile() && target.length() > 0) {
            Log.i(TAG, "BepInEx interop base libraries already present: " + target.getAbsolutePath());
            return true;
        }
        if (!unityLibsDir.exists() && !unityLibsDir.mkdirs()) {
            Log.e(TAG, "Failed to create unity-libs directory: " + unityLibsDir.getAbsolutePath());
            return false;
        }

        java.util.List<String> candidates = new java.util.ArrayList<>();
        candidates.add(gameBase);
        int[] triplet = parseVersionTriplet(gameBase);
        if (triplet != null) {
            for (int patch = triplet[2] - 1; patch >= 0 && candidates.size() < INTEROP_MAX_PROBES; patch--) {
                candidates.add(triplet[0] + "." + triplet[1] + "." + patch);
            }
        }
        String indexResolved = resolveBestAvailableVersion(gameVersion);
        if (indexResolved != null && !candidates.contains(indexResolved)) {
            candidates.add(indexResolved);
        }

        for (String candidate : candidates) {
            String url = INTEROP_LIBRARIES_URL + candidate + ".zip";
            if (!urlExists(url)) {
                continue;
            }
            Log.i(TAG, "Providing BepInEx interop base libraries " + url + " as " + target.getName());
            return downloadToFile(url, target);
        }
        Log.e(TAG, "No hosted interop base libraries found for " + gameVersion);
        return false;
    }

    private static boolean urlExists(String urlString) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            Log.w(TAG, "Probe failed for " + urlString, e);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean downloadToFile(String urlString, File target) {
        HttpURLConnection connection = null;
        File temp = new File(target.getAbsolutePath() + ".download");
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.e(TAG, "Failed to download " + urlString + ", HTTP " + status);
                return false;
            }
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(temp, false)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            if (target.exists() && !target.delete()) {
                Log.e(TAG, "Failed to replace existing file: " + target.getAbsolutePath());
                return false;
            }
            if (!temp.renameTo(target)) {
                Log.e(TAG, "Failed to move downloaded file into place: " + target.getAbsolutePath());
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error downloading " + urlString + ": " + e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    private static void patchLibUnityVersionIfNeeded(File libFile, String downloadVersion,
                                                     String requestedNormalized, String requestedFull) {
        if (downloadVersion == null || downloadVersion.equals(requestedNormalized)) {
            return;
        }
        if (requestedFull == null || !requestedFull.startsWith(requestedNormalized)) {
            return;
        }
        String suffix = requestedFull.substring(requestedNormalized.length());
        String downloadFull = downloadVersion + suffix;
        if (downloadFull.length() != requestedFull.length()) {
            return;
        }
        try {
            byte[] data = java.nio.file.Files.readAllBytes(libFile.toPath());
            byte[] from = downloadFull.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] to = requestedFull.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            boolean patched = false;
            int idx = indexOf(data, from, 0);
            while (idx >= 0) {
                System.arraycopy(to, 0, data, idx, to.length);
                patched = true;
                idx = indexOf(data, from, idx + from.length);
            }
            if (patched) {
                java.nio.file.Files.write(libFile.toPath(), data);
                Log.i(TAG, "Patched libunity version " + downloadFull + " -> " + requestedFull);
            } else {
                Log.w(TAG, "libunity did not contain version string " + downloadFull + " (already patched?)");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to patch libunity version: " + e.getMessage());
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int start) {
        outer:
        for (int i = start; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    public static String resolveBestAvailableVersion(String requestedVersion) {
        return resolveBestVersionFromIndex(LIBUNITY_DOWNLOAD_URL, INDEX_VERSION_PATTERN, requestedVersion);
    }

    private static String resolveBestVersionFromIndex(String indexUrl, Pattern entryPattern,
                                                      String requestedVersion) {
        int[] target = parseVersionTriplet(requestedVersion);
        if (target == null) {
            return null;
        }

        String index = fetchText(indexUrl);
        if (index == null) {
            return null;
        }

        int[] best = null;
        String bestStr = null;
        Matcher m = entryPattern.matcher(index);
        while (m.find()) {
            int[] candidate = {
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))
            };
            if (compareTriplets(candidate, target) <= 0
                    && (best == null || compareTriplets(candidate, best) > 0)) {
                best = candidate;
                bestStr = m.group(1) + "." + m.group(2) + "." + m.group(3);
            }
        }

        if (bestStr == null) {
            Log.e(TAG, "No hosted version <= " + requestedVersion + " found in " + indexUrl);
        }
        return bestStr;
    }

    private static int[] parseVersionTriplet(String version) {
        if (version == null) {
            return null;
        }
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(version);
        if (!m.find()) {
            return null;
        }
        return new int[] {
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))
        };
    }

    private static int compareTriplets(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static String fetchText(String urlString) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.e(TAG, "Failed to fetch libunity version index, HTTP " + status);
                return null;
            }

            InputStream in = connection.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Error fetching version index " + urlString, e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}

