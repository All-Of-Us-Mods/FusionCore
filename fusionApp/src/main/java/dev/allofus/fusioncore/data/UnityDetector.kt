package dev.allofus.fusioncore.data

import android.content.pm.ApplicationInfo
import java.io.File
import java.util.zip.ZipFile

object UnityDetector {
    fun ApplicationInfo.isUnityApp(): Boolean {
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