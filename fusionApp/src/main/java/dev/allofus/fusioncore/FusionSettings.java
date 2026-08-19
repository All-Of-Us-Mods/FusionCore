package dev.allofus.fusioncore;

import android.content.Context;
import android.content.SharedPreferences;

public final class FusionSettings {
    private static final String PREFS_NAME = "fusion_settings";
    private static final String KEY_DOWNLOAD_UNSTRIPPED_LIBUNITY = "download_unstripped_libunity";

    private FusionSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean getUseUnstrippedLibUnityForGame(Context context, String targetPackage) {
        return prefs(context).getBoolean(targetPackage + ":" + KEY_DOWNLOAD_UNSTRIPPED_LIBUNITY, true);
    }

    public static void setUseUnstrippedLibUnityForGame(Context context, String targetPackage, boolean enabled) {
        prefs(context).edit().putBoolean(targetPackage + ":" + KEY_DOWNLOAD_UNSTRIPPED_LIBUNITY, enabled).apply();
    }
}
