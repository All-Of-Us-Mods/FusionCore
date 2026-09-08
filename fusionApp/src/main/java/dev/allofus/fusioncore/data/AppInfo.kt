package dev.allofus.fusioncore.data

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val versionName: String?,
    val versionCode: Long
)
