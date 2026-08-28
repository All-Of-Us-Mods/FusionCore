package dev.allofus.fusioncore;

import android.content.Context;
import android.content.SharedPreferences;

public final class FusionSettings {
    private static final String PREFS_NAME = "fusion_settings";
    private static final String KEY_DOWNLOAD_UNSTRIPPED_LIBUNITY = "download_unstripped_libunity";
    private static final String KEY_ACTIVITY_OVERRIDE = "activity_override";

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

    public static String getActivityOverrideForGame(Context context, String targetPackage) {
        return prefs(context).getString(targetPackage + ":" + KEY_ACTIVITY_OVERRIDE, context.getString(R.string.settings_automatic));
    }

    public static void setActivityOverrideForGame(Context context, String targetPackage, String activityName) {
        prefs(context).edit().putString(targetPackage + ":" + KEY_ACTIVITY_OVERRIDE, activityName).apply();
    }
}
