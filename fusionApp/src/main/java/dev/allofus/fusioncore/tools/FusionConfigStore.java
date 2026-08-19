package dev.allofus.fusioncore.tools;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public final class FusionConfigStore {
    private static final String STAGE_DIR = "bootstrap";
    private static final String ACTIVE_CONFIG_FILE = "active.cfg";

    private FusionConfigStore() {
    }

    public static File getConfigFile(Context context) {
        File dir = new File(context.getFilesDir(), STAGE_DIR);
        return new File(dir, ACTIVE_CONFIG_FILE);
    }

    public static File write(Context context, FusionConfig config) throws IOException {
        File configFile = getConfigFile(context);
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create config directory: " + parent);
        }

        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(configFile, false), StandardCharsets.UTF_8)) {
            writeLine(writer, "gameLibraryDirectory", config.gameLibraryDirectory);
            writeLine(writer, "appLibraryDirectory", config.appLibraryDirectory);
            writeLine(writer, "appDataDirectory", config.appDataDirectory);
            writeLine(writer, "bepInExDirectory", config.bepInExDirectory);
            writeLine(writer, "dotnetDirectory", config.dotnetDirectory);
            writeLine(writer, "unityDataDirectory", config.unityDataDirectory);
            writeLine(writer, "unityVersion", config.unityVersion);
            writeLine(writer, "useOriginalLibUnity", Boolean.toString(config.useOriginalLibUnity));
        }

        return configFile;
    }

    private static void writeLine(OutputStreamWriter writer, String key, String value) throws IOException {
        writer.write(key);
        writer.write('=');
        writer.write(value != null ? value.replace('\n', ' ').replace('\r', ' ') : "");
        writer.write('\n');
    }
}

