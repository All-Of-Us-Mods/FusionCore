package dev.allofus.fusioncore.tools

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import android.util.Log
import android.view.View
import android.view.WindowInsets
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayList
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object Utilities {
    private const val TAG = "FusionCore"

    @JvmStatic
    fun getExternalFusionCoreDirectory(targetPackage: String?, Folder: String?): File {
        var fusionStorage = File(Environment.getExternalStorageDirectory(), "FusionCore")
        if (targetPackage != null) {
            fusionStorage = File(fusionStorage, targetPackage)
        }
        if (Folder != null) {
            fusionStorage = File(fusionStorage, Folder)
        }
        if (!fusionStorage.exists()) {
            fusionStorage.mkdirs()
        }
        return fusionStorage
    }

    @JvmStatic
    fun applyWindowInsets(root: View, basePadding: Int) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            val insetTop: Int
            val insetBottom: Int
            val insetLeft: Int
            val insetRight: Int

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                insetTop = bars.top
                insetBottom = bars.bottom
                insetLeft = bars.left
                insetRight = bars.right
            } else {
                @Suppress("DEPRECATION")
                insetTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                insetBottom = insets.systemWindowInsetBottom
                @Suppress("DEPRECATION")
                insetLeft = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                insetRight = insets.systemWindowInsetRight
            }

            v.setPadding(
                basePadding + insetLeft,
                basePadding + insetTop,
                basePadding + insetRight,
                basePadding + insetBottom
            )
            insets
        }
        root.requestApplyInsets()
    }

    @JvmStatic
    fun formatVersionText(versionName: String, versionCode: Long): String {
        return if (versionCode > 0L) {
            "v$versionName ($versionCode)"
        } else {
            "v$versionName"
        }
    }

    @JvmStatic
    @Throws(IOException)
    fun extractZipFromStream(zis: ZipInputStream, outputFolder: File) {
        extractZipFromStream(zis, outputFolder, null)
    }

    @JvmStatic
    @Throws(IOException)
    fun extractZipFromStream(zis: ZipInputStream, outputFolder: File, keepExistingPrefix: String?) {
        if (!outputFolder.exists() && !outputFolder.mkdirs()) {
            throw new IOException("Failed to create output directory: " + outputFolder.absolutePath)
        }
        val buffer = ByteArray(8192)
        val outputRoot = outputFolder.canonicalPath + File.separator
        var ze: ZipEntry?
        while (zis.nextEntry.also { ze = it } != null) {
            val entryName = ze?.name
            if (entryName.isNullOrEmpty()) {
                zis.closeEntry()
                continue
            }

            val target = File(outputFolder, entryName)
            val targetPath = target.canonicalPath

            if (!targetPath.startsWith(outputRoot)) {
                throw new IOException("Blocked zip entry outside output folder: $entryName")
            }

            if (ze!!.isDirectory) {
                if (!target.exists() && !target.mkdirs()) {
                    throw new IOException("Failed to create directory: $targetPath")
                }
            } else if (keepExistingPrefix != null && entryName.startsWith(keepExistingPrefix) && target.isFile) {
                Log.i(TAG, "Keeping existing $targetPath")
            } else {
                val parent = target.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Failed to create parent directory: " + parent.absolutePath)
                }

                try {
                    FileOutputStream(target).use { fos ->
                        var count: Int
                        while (zis.read(buffer).also { count = it } != -1) {
                            fos.write(buffer, 0, count)
                        }
                    }
                } catch (e: Exception) {
                    throw e
                }
            }
            zis.closeEntry()
        }
    }

    @JvmStatic
    fun extractZipFromAssets(context: Context, assetName: String, outputFolder: File) {
        extractZipFromAssets(context, assetName, outputFolder, null)
    }

    @JvmStatic
    fun extractZipFromAssets(context: Context, assetName: String, outputFolder: File, keepExistingPrefix: String?) {
        try {
            context.assets.open(assetName).use { `is` ->
                BufferedInputStream(`is`).use { bis ->
                    ZipInputStream(bis).use { zis ->
                        extractZipFromStream(zis, outputFolder, keepExistingPrefix)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract $assetName from assets!", e)
        }
    }

    @JvmStatic
    fun copyAssets(gameInfo: ApplicationInfo, outputFolder: File): Boolean {
        val marker = File(outputFolder, ".fusion_copied")
        if (marker.isFile) {
            return true
        }

        val apks = ArrayList<String>()
        if (gameInfo.sourceDir != null) apks.add(gameInfo.sourceDir)
        if (gameInfo.splitSourceDirs != null) Collections.addAll(apks, *gameInfo.splitSourceDirs)

        val outputRoot: String
        try {
            outputRoot = outputFolder.canonicalPath + File.separator
        } catch (e: IOException) {
            Log.e(TAG, "Failed to resolve $outputFolder", e)
            return false
        }

        var copied = 0
        val buffer = ByteArray(64 * 1024)
        for (apk in apks) {
            try {
                ZipFile(apk).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (entry.isDirectory || !name.startsWith("assets/")) continue

                        val target = File(outputFolder, name.substring("assets/".length))
                        if (!target.canonicalPath.startsWith(outputRoot)) continue
                        if (target.isFile && target.length() == entry.size) continue

                        val parent = target.parentFile
                        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                            throw new IOException("Failed to create $parent")
                        }
                        zip.getInputStream(entry).use { `in` ->
                            FileOutputStream(target).use { out ->
                                var count: Int
                                while (`in`.read(buffer).also { count = it } > 0) {
                                    out.write(buffer, 0, count)
                                }
                            }
                        }
                        copied++
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to copy Unity Data assets from $apk", e)
                return false
            }
        }

        try {
            if (!marker.createNewFile()) {
                Log.w(TAG, "Failed to create asset copy marker: " + marker.absolutePath)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to create asset copy marker", e)
        }
        Log.i(TAG, "Successfully copied $copied Unity Data assets to: " + outputFolder.absolutePath)
        return true
    }

    @JvmStatic
    fun deleteRecursive(file: File?): Boolean {
        if (file == null || !file.exists()) {
            return true
        }

        if (file.isDirectory) {
            val files = file.listFiles()
            if (files != null) {
                for (f in files) {
                    if (!deleteRecursive(f)) {
                        return false
                    }
                }
            }
        }
        return file.delete()
    }

    @JvmStatic
    fun extractZipFromInputStream(inputStream: InputStream, OutPutFolder: File) {
        try {
            BufferedInputStream(inputStream).use { bis ->
                ZipInputStream(bis).use { zis ->
                    extractZipFromStream(zis, OutPutFolder)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract zip from InputStream!", e)
        }
    }
}
