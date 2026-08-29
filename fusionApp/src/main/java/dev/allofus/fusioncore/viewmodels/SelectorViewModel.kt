package dev.allofus.fusioncore.viewmodels

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.allofus.fusioncore.BuildConfig
import dev.allofus.fusioncore.data.AppInfo
import dev.allofus.fusioncore.data.GameSettingsRepo
import dev.allofus.fusioncore.data.SelectorUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

@HiltViewModel
class SelectorViewModel @Inject constructor(
    private val packageManager: PackageManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<SelectorUiState>(SelectorUiState.Loading)
    val uiState: StateFlow<SelectorUiState> = _uiState.asStateFlow()

    init {
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val apps = packageManager.queryIntentActivities(intent, 0).mapNotNull { resolveInfo ->
                try {
                    val packageInfo =
                        packageManager.getPackageInfo(resolveInfo.activityInfo.packageName, 0)

                    if (packageInfo.packageName == BuildConfig.APPLICATION_ID) {
                        return@mapNotNull null
                    }

                    if (packageInfo.applicationInfo?.hasIl2Cpp() == false) {
                        return@mapNotNull null
                    }

                    @Suppress("DEPRECATION")
                    AppInfo(
                        packageName = packageInfo.packageName,
                        packageVersion = "${packageInfo.versionName} (${packageInfo.versionCode})",
                        label = resolveInfo.loadLabel(packageManager).toString(),
                        icon = resolveInfo.loadIcon(packageManager)
                    )
                } catch (_: Exception) {
                    null
                }
            }.sortedBy { it.label }

            _uiState.value = SelectorUiState.Success(apps)
        }
    }


    private fun ApplicationInfo.hasIl2Cpp(): Boolean {
        val apkPaths = mutableListOf<String>()

        apkPaths.add(sourceDir)
        splitSourceDirs?.forEach { apkPaths.add(it) }

        for (apk in apkPaths) {
            if (apkContainsIl2Cpp(apk)) {
                return true
            }
        }

        val nativeDir = nativeLibraryDir
        if (nativeDir != null && !nativeDir.isEmpty()) {
            val dir = File(nativeDir)
            if (File(dir, "libil2cpp.so").exists()) {
                return true
            }
            val abiDirs = dir.listFiles()
            if (abiDirs != null) {
                for (abiDir in abiDirs) {
                    if (abiDir.isDirectory() && File(abiDir, "libil2cpp.so").exists()) {
                        return true
                    }
                }
            }
        }

        return false
    }


    private val unityABIs = arrayOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    private fun apkContainsIl2Cpp(apkPath: String): Boolean {
        try {
            ZipFile(apkPath).use {
                for (abi: String in unityABIs) {
                    if (it.getEntry("lib/$abi/libil2cpp.so") != null) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return false
    }
}