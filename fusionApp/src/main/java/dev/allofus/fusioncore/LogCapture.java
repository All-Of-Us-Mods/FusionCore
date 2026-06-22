package dev.allofus.fusioncore;

import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class LogCapture {

    private static final String TAG = "FusionCore";

    private static volatile boolean started;

    public static synchronized void start(File logDir) {
        if (started) {
            return;
        }
        if (!logDir.exists() && !logDir.mkdirs()) {
            Log.w(TAG, "Could not create log directory: " + logDir);
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File logFile = new File(logDir, "fusion-" + stamp + ".log");

        Process logcat;
        try {
            logcat = new ProcessBuilder(
                    "logcat", "-v", "threadtime", "--pid=" + android.os.Process.myPid())
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start logcat capture", e);
            return;
        }

        Thread pump = new Thread(() -> pumpToFile(logcat, logFile), "fusion-log-capture");
        pump.setDaemon(true);
        pump.start();

        started = true;
        Log.i(TAG, "Capturing logs to " + logFile.getAbsolutePath());
    }

    private static void pumpToFile(Process logcat, File logFile) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(logcat.getInputStream(), StandardCharsets.UTF_8));
             Writer writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(logFile), StandardCharsets.UTF_8))) {

            writer.write("FusionCore log\n");
            writer.write("device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
            writer.write("android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
            writer.write("abis: " + TextUtils.join(",", Build.SUPPORTED_ABIS) + "\n\n");
            writer.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "Log capture stopped", e);
        }
    }

    private LogCapture() {
    }
}
