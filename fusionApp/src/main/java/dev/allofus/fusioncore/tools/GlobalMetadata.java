package dev.allofus.fusioncore.tools;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class GlobalMetadata {
    private static final String TAG = "GlobalMetadata";
    public static final String FILE_NAME = "global-metadata.dat";
    private static final String APK_ENTRY = "assets/bin/Data/Managed/Metadata/" + FILE_NAME;
    private static final int MAGIC = 0xFAB11BAF;

    private GlobalMetadata() {}

    public static boolean hasValidHeader(InputStream in) throws IOException {
        byte[] header = new byte[8];
        int read = 0;
        while (read < header.length) {
            int count = in.read(header, read, header.length - read);
            if (count < 0) return false;
            read += count;
        }
        int magic = (header[0] & 0xff) | (header[1] & 0xff) << 8 | (header[2] & 0xff) << 16 | (header[3] & 0xff) << 24;
        int version = (header[4] & 0xff) | (header[5] & 0xff) << 8 | (header[6] & 0xff) << 16 | (header[7] & 0xff) << 24;
        return magic == MAGIC && version >= 16 && version <= 64;
    }

    public static boolean isEncrypted(ApplicationInfo info) {
        List<String> apks = new ArrayList<>();
        if (info.sourceDir != null) apks.add(info.sourceDir);
        if (info.splitSourceDirs != null) Collections.addAll(apks, info.splitSourceDirs);

        for (String apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                ZipEntry entry = zip.getEntry(APK_ENTRY);
                if (entry == null) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    return !hasValidHeader(in);
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to read metadata from " + apk, e);
            }
        }
        return false;
    }

    public static File getOverrideFile(String packageName) {
        return new File(Utilities.getExternalFusionCoreDirectory(packageName, null), FILE_NAME);
    }

    public static boolean hasValidOverride(String packageName) {
        File file = getOverrideFile(packageName);
        if (!file.isFile()) return false;
        try (InputStream in = new FileInputStream(file)) {
            return hasValidHeader(in);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean installOverride(InputStream source, String packageName) throws IOException {
        File target = getOverrideFile(packageName);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Failed to create " + parent.getAbsolutePath());
        }

        File temp = new File(parent, FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = source.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }

        boolean valid;
        try (InputStream in = new FileInputStream(temp)) {
            valid = hasValidHeader(in);
        }
        if (!valid || !temp.renameTo(target)) {
            temp.delete();
            return false;
        }
        return true;
    }
}