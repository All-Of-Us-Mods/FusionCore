package dev.allofus.fusioncore;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.regex.Pattern;

// https://github.com/BepInEx/BepInEx/blob/3fab71a1914132a1ce3a545caf3192da603f2258/Runtimes/Unity/BepInEx.Unity.Common/UnityInfo.cs#L61
public class VersionLookup {

    private static final int MAX_VERSION_LENGTH = 32;
    private static final Pattern UNITY_VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:[abcfp]\\d+|rc\\d+)?$");

    private static final HashMap<String, int[]> lookupMap = new HashMap<>() {{
        put("globalgamemanagers", new int[]{0x14, 0x30});
        put("data.unity3d", new int[]{0x12});
        put("mainData", new int[]{0x14});
    }};

    public static String TryLookup(File dataFolder) {

        for (String fileName : lookupMap.keySet()) {
            if (!new File(dataFolder, fileName).exists()) {
                continue;
            }

            int[] offsets = lookupMap.get(fileName);
            if (offsets == null) {
                return null;
            }

            File file = new File(dataFolder, fileName);
            if (!file.exists() || !file.isFile()) {
                return null;
            }

            try (RandomAccessFile reader = new RandomAccessFile(file, "r")) {
                long length = reader.length();
                for (int offset : offsets) {
                    if (offset < 0 || offset >= length) {
                        continue;
                    }

                    reader.seek(offset);
                    String candidate = readAsciiString(reader, MAX_VERSION_LENGTH);
                    if (isValidUnityVersion(candidate)) {
                        return candidate;
                    }
                }
            } catch (IOException ignored) {
                return null;
            }
        }

        return null;
    }

    private static String readAsciiString(RandomAccessFile reader, int maxLength) throws IOException {
        StringBuilder builder = new StringBuilder(maxLength);

        for (int i = 0; i < maxLength; i++) {
            int b = reader.read();
            if (b == -1 || b == 0) {
                break;
            }

            // Unity version tokens are plain ASCII; stop if binary content starts.
            if (b < 0x20 || b > 0x7E) {
                break;
            }

            builder.append((char) b);
        }

        if (builder.length() == 0) {
            return null;
        }

        return builder.toString().trim();
    }

    private static boolean isValidUnityVersion(String value) {
        return value != null && UNITY_VERSION_PATTERN.matcher(value).matches();
    }
}
