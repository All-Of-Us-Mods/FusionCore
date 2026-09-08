package dev.allofus.fusioncore.data

import android.os.Build
import android.os.Environment
import android.util.Log

object PermissionsManager {

    private const val TAG = "PermissionsManager"

    fun hasExternalStorageManagerAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "Has external storage manager access: SDK_INT < R")
            return true
        }

        return Environment.isExternalStorageManager()
    }
}