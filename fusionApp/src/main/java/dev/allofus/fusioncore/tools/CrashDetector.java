package dev.allofus.fusioncore.tools;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public class CrashDetector {
    public static final String TAG = "CrashDetector";


    public static void init(Context context) {
        ActivityManager activityManager = context.getApplicationContext().getSystemService(ActivityManager.class);
        if (activityManager == null) {
            Log.e(TAG, "failed to get activity manager");
            return;
        }

        var fusionFolder = new File(Environment.getExternalStorageDirectory(), "FusionCore");
        fusionFolder.mkdirs();

        var exitInfos = activityManager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 1);
        for (var exitInfo : exitInfos) {
            var outputFile = new File(fusionFolder, "crash_" + System.currentTimeMillis() + ".txt");
            writeExitInfo(context, exitInfo, outputFile);
            Log.i(TAG, "wrote crash log to " + outputFile.getAbsolutePath());
            Toast.makeText(context, "Crash log written to FusionCore folder", Toast.LENGTH_LONG).show();
        }
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
            sb.append("Package Name: ").append(packageInfo.packageName).append("\n").append("Version Name: ")
                    .append(packageInfo.versionName).append("\n")
                    .append("Version Code: ").append(packageInfo.getLongVersionCode()).append("\n");
        }

        return sb.toString();
    }

    private static void writeExitInfo(Context context, ApplicationExitInfo exitInfo, File outputFile) {
        try(var writer = new FileWriter(outputFile, false)) {
            writer.write("FusionCore Crash Log:\n");
            writer.write(buildDeviceData(context));
            writer.write("=".repeat(50));
            writer.write(exitInfo.toString());

            var inputStream = exitInfo.getTraceInputStream();
            if (inputStream == null) {
                writer.write("failed to get trace input stream");
                Log.e(TAG, "No trace input stream");
                return;
            }

            var rawBytes = inputStream.readAllBytes();
            if (rawBytes == null || rawBytes.length == 0) {
                writer.write("failed to read trace input stream");
                Log.e(TAG, "No trace input stream");
                return;
            }

            writer.write("=".repeat(50));
            writer.write("Tombstone Text:\n");

            var decoded = tryDecodeAsUtf8(rawBytes);
            if (decoded != null) {
                writer.write(decoded);
            } else {
                var tombstoneDecoded = dev.allofus.fusioncore.proto.TombstoneProtos.Tombstone.parseFrom(rawBytes);
                writer.write(tombstoneDecoded.toString());
            }

        } catch (Exception e) {
            Log.e(TAG, "failed to extract tombstone data", e);
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
