package dev.allofus.fusioncore.tools;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class CrashDetector {
    public static final String TAG = "CrashDetector";

    public static void init(Context context) {
        setupUncaughtExceptionHandler(context);

        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        if (activityManager == null) {
            Log.e(TAG, "failed to get activity manager");
            return;
        }

        var fusionFolder = getStorageFolder();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            List<ApplicationExitInfo> exitInfos = activityManager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 1);

            for (var exitInfo : exitInfos) {
                if (exitInfo.getReason() != ApplicationExitInfo.REASON_CRASH &&
                        exitInfo.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE &&
                        exitInfo.getReason() != ApplicationExitInfo.REASON_ANR
                ) {
                    Log.i(TAG, "skipping exit info with reason " + exitInfo.getReason());
                    continue;
                }

                var outputFile = new File(fusionFolder, "exit_info_" + System.currentTimeMillis() + ".txt");
                writeExitInfo(context, exitInfo, outputFile);
                Log.i(TAG, "wrote exit info log to " + outputFile.getAbsolutePath());
            }
        }
    }

    private static File getStorageFolder() {
        var fusionFolder = new File(Environment.getExternalStorageDirectory(), "FusionCore");
        if (!fusionFolder.exists()) {
            fusionFolder.mkdirs();
        }
        return fusionFolder;
    }

    private static void setupUncaughtExceptionHandler(Context context) {
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                File fusionFolder = getStorageFolder();
                File outputFile = new File(fusionFolder, "java_crash_" + System.currentTimeMillis() + ".txt");

                try (FileWriter writer = new FileWriter(outputFile, false)) {
                    writer.write("FusionCore Java Crash Log:\n");
                    writer.write(buildDeviceData(context));
                    writer.write("=".repeat(50) + "\n");
                    writer.write("Thread: " + thread.getName() + " (ID: " + thread.getId() + ")\n");
                    writer.write("Exception Stack Trace:\n");
                    writer.write(Log.getStackTraceString(throwable));
                }
                Log.i(TAG, "Saved uncaught Java crash trace to " + outputFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Failed to write uncaught Java exception log", e);
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private static String buildDeviceData(Context context) {
        PackageInfo packageInfo = null;
        try {
            packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "failed to get packageinfo");
        }

        var sb = new StringBuilder()
                .append("API Level: ").append(Build.VERSION.SDK_INT).append("\n")
                .append("Android Version: ").append(Build.VERSION.RELEASE).append("\n")
                .append("Device Model: ").append(Build.MODEL).append("\n")
                .append("Manufacturer: ").append(Build.MANUFACTURER).append("\n")
                .append("Brand: ").append(Build.BRAND).append("\n")
                .append("Product: ").append(Build.PRODUCT).append("\n")
                .append("Device: ").append(Build.DEVICE).append("\n")
                .append("Hardware: ").append(Build.HARDWARE).append("\n")
                .append("Board: ").append(Build.BOARD).append("\n")
                .append("Bootloader: ").append(Build.BOOTLOADER).append("\n")
                .append("Type: ").append(Build.TYPE).append("\n")
                .append("Fingerprint: ").append(Build.FINGERPRINT).append("\n");

        if (packageInfo != null) {
            sb.append("Package Name: ").append(packageInfo.packageName).append("\n")
                    .append("Version Name: ").append(packageInfo.versionName).append("\n")
                    .append("Version Code: ").append(packageInfo.versionCode).append("\n");
        }

        return sb.toString();
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private static void writeExitInfo(Context context, ApplicationExitInfo exitInfo, File outputFile) {
        try (var writer = new FileWriter(outputFile, false)) {
            writer.write("FusionCore Exit Log:\n");
            writer.write(buildDeviceData(context));
            writer.write("=".repeat(50) + "\n");
            writer.write(exitInfo.toString() + "\n");

            // ApplicationExitInfo does NOT provide trace streams for REASON_CRASH (Java exceptions)
            if (exitInfo.getReason() == ApplicationExitInfo.REASON_CRASH) {
                writer.write("Note: Java runtime crashes do not populate ApplicationExitInfo trace streams.\n");
                writer.write("Description: " + exitInfo.getDescription() + "\n");
                return;
            }

            var inputStream = exitInfo.getTraceInputStream();
            if (inputStream == null) {
                writer.write("failed to get trace input stream\n");
                Log.e(TAG, "No trace input stream");
                return;
            }

            byte[] rawBytes;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                rawBytes = inputStream.readAllBytes();
            } else {
                rawBytes = new byte[inputStream.available()];
                var read = inputStream.read(rawBytes);
                if (read < 0) {
                    writer.write("failed to read trace input stream\n");
                    Log.e(TAG, "No trace input stream bytes");
                    return;
                }
            }
            if (rawBytes == null || rawBytes.length == 0) {
                writer.write("failed to read trace input stream\n");
                Log.e(TAG, "No trace input stream bytes");
                return;
            }

            writer.write("=".repeat(50) + "\n");
            writer.write("Tombstone / Trace Data:\n");

            var decoded = tryDecodeAsUtf8(rawBytes);
            if (decoded != null) {
                writer.write(decoded);
            } else {
                var tombstoneDecoded = dev.allofus.fusioncore.proto.TombstoneProtos.Tombstone.parseFrom(rawBytes);
                writer.write(tombstoneDecoded.toString());
            }

        } catch (Exception e) {
            Log.e(TAG, "failed to extract exit info data", e);
        }
    }

    private static String tryDecodeAsUtf8(byte[] bytes) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}