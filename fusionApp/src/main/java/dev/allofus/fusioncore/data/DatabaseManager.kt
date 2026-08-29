package dev.allofus.fusioncore.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.room3.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.allofus.fusioncore.AppDatabase
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val DATABASE_NAME = "dbfusion"
    }
    
    private var activeDb: AppDatabase? = null

    @Synchronized
    fun getDatabase(): AppDatabase {
        if (activeDb == null) {
            activeDb = buildDatabase(resolveDatabasePath())
        }
        return activeDb!!
    }

    @Synchronized
    fun switchToExternalStorage(): Boolean {
        if (!hasStoragePermission()) return false

        val targetFolder = File(Environment.getExternalStorageDirectory(), "FusionCore")
        if (!targetFolder.exists() && !targetFolder.mkdirs()) {
            return false
        }

        val targetPath = File(targetFolder, DATABASE_NAME).absolutePath

        try {
            activeDb?.close()
        } catch (_: Exception) {
        } finally {
            activeDb = null
        }

        copyInternalToExternal(targetFolder)

        activeDb = buildDatabase(targetPath)
        return true
    }

    private fun copyInternalToExternal(targetFolder: File) {
        val internalDb = context.getDatabasePath(DATABASE_NAME)
        if (!internalDb.exists()) return

        val internalDir = internalDb.parentFile ?: return

        val internalFiles = internalDir.listFiles { _, name ->
            name.startsWith(DATABASE_NAME)
        } ?: return

        for (file in internalFiles) {
            val targetFile = File(targetFolder, file.name)

            if (!targetFile.exists()) {
                file.copyTo(targetFile, overwrite = true)
            }
        }
    }

    private fun resolveDatabasePath(): String {
        val externalPath = getExternalDbPath()
        return if (hasStoragePermission() && externalPath != null) {
            externalPath
        } else {
            DATABASE_NAME // Internal fallback
        }
    }

    private fun getExternalDbPath(): String? {
        val folder = File(Environment.getExternalStorageDirectory(), "FusionCore")
        if (!folder.exists() && !folder.mkdirs()) {
            return null
        }
        copyInternalToExternal(folder)
        return File(folder, DATABASE_NAME).absolutePath
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun buildDatabase(path: String): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            path
        ).build()
    }
}