package dev.allofus.fusioncore.data

import android.content.Context
import android.content.pm.PackageInfo

class AppRepository(private val context: Context) {

    fun getInstalledPackages(): List<PackageInfo> {
        return context.packageManager.getInstalledPackages(0)
    }

    fun getAppInfo(packageInfo: PackageInfo) : AppInfo {
        @Suppress("DEPRECATION")
        return AppInfo(
            packageName = packageInfo.packageName,
            label = packageInfo.applicationInfo?.loadLabel(context.packageManager).toString(),
            icon = packageInfo.applicationInfo?.loadIcon(context.packageManager),
            versionName = packageInfo.versionName,
            versionCode = packageInfo.versionCode.toLong()
        )
    }
}