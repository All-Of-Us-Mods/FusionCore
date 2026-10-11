package dev.allofus.fusioncore.tools;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UnityBaseLibraries {
    private static final String TAG = "UnityBaseLibraries";
    private static final String SERVER = "https://unity.bepinex.dev/libraries/";
    private static final String DEFAULT_SOURCE = SERVER + "{VERSION}.zip";
    private static final String KEY = "UnityBaseLibrariesSource";
    private static final String AUTO_MARKER = ".fusion_base_libraries";
    private static final int TIMEOUT_MS = 5000;

    private UnityBaseLibraries() {}

    public static void resolve(File bepInExDir, String unityVersion) {
        Matcher match = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)").matcher(unityVersion);
        if (!match.find()) return;
        String majorMinor = match.group(1) + "." + match.group(2);
        int patch = Integer.parseInt(match.group(3));
        String exact = majorMinor + "." + patch;

        File config = new File(bepInExDir, "config/BepInEx.cfg");
        File marker = new File(config.getParentFile(), AUTO_MARKER);
        try {
            String text = readText(config);
            Matcher line = Pattern.compile("(?m)^" + KEY + "\\s*=\\s*(.*)$").matcher(text);
            if (!line.find()) return;
            String current = line.group(1).trim();
            String auto = marker.isFile() ? readText(marker).trim() : null;
            if (!current.equals(DEFAULT_SOURCE) && !current.equals(auto)) return;

            String source = DEFAULT_SOURCE;
            boolean exactAvailable = new File(bepInExDir, "unity-libs/" + exact + ".zip").isFile()
                    || exists(SERVER + exact + ".zip");
            if (!exactAvailable) {
                String fallback = findFallback(majorMinor, patch);
                if (fallback == null) {
                    Log.w(TAG, "No Unity base libraries available for " + exact);
                    return;
                }
                source = SERVER + fallback + ".zip";
                Log.i(TAG, "No Unity base libraries for " + exact + ", using " + fallback);
            }

            if (!source.equals(current)) {
                writeText(config, text.substring(0, line.start(1)) + source + text.substring(line.end(1)));
            }
            if (source.equals(DEFAULT_SOURCE)) {
                //noinspection ResultOfMethodCallIgnored
                marker.delete();
            } else {
                writeText(marker, source);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to resolve Unity base libraries for " + exact, e);
        }
    }

    private static boolean exists(String url) throws IOException {
        HttpURLConnection connection = open(url);
        connection.setRequestMethod("HEAD");
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) return true;
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return false;
            throw new IOException("Unexpected response " + code + " for " + url);
        } finally {
            connection.disconnect();
        }
    }

    private static String findFallback(String majorMinor, int patch) throws IOException {
        HttpURLConnection connection = open(SERVER);
        String listing;
        try (InputStream in = connection.getInputStream()) {
            listing = new String(readAll(in), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }

        Matcher entry = Pattern.compile("href=\"\\./" + Pattern.quote(majorMinor) + "\\.(\\d+)\\.zip\"").matcher(listing);
        int best = -1;
        while (entry.find()) {
            int candidate = Integer.parseInt(entry.group(1));
            if (candidate < patch && candidate > best) best = candidate;
        }
        return best < 0 ? null : majorMinor + "." + best;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        return connection;
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}