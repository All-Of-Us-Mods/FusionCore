package dev.allofus.fusioncore.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.allofus.fusioncore.ui.icons.folder_special

@Composable
fun StoragePermissionStartupHandler(
    onPermissionGranted: () -> Unit
) {
    val context = LocalContext.current

    val hasPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    if (hasPermissions) {
        onPermissionGranted()
        return
    }

    var launchPermissionRequest by remember { mutableStateOf(false) }

    // android 10 and older
    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onPermissionGranted()
        }
    }

    // android 11+ (I HATE ANDROID STORAGE)
    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            onPermissionGranted()
        }
    }

    if (!launchPermissionRequest) {
        AlertDialog(
            onDismissRequest = { launchPermissionRequest = true },
            icon = {
                Icon(
                    imageVector = folder_special,
                    contentDescription = "Storage Permission"
                )
            },
            title = {
                Text(text = "External Storage Access")
            },
            text = {
                Text(
                    text = "Fusion Core stores data in external storage to make mod management easier for you.\n\n" +
                            "To enable custom directory storage, please grant storage permissions."
                )
            },
            confirmButton = {
                TextButton(onClick = { launchPermissionRequest = true }) {
                    Text("Grant Access")
                }
            }
        )
    }

    LaunchedEffect(launchPermissionRequest) {
        if (launchPermissionRequest) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
                manageStorageLauncher.launch(intent)
            } else {
                legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}