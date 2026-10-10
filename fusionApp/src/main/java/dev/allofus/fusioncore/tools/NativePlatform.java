package dev.allofus.fusioncore.tools;

import android.os.Build;

public final class NativePlatform {
    private NativePlatform() {}

    public static boolean isArmTranslation() {
        if (Build.SUPPORTED_ABIS.length == 0) return false;
        String hostAbi = Build.SUPPORTED_ABIS[0];
        return hostAbi.equals("x86_64") || hostAbi.equals("x86");
    }
}