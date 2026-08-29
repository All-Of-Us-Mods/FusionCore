package dev.allofus.fusioncore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.allofus.fusioncore.data.DatabaseManager
import dev.allofus.fusioncore.ui.RootScreen
import dev.allofus.fusioncore.ui.StorageScreen
import dev.allofus.fusioncore.ui.theme.FusionCoreTheme
import dev.allofus.fusioncore.viewmodels.LaunchViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "MainActivity"
    }

    @Inject
    lateinit var databaseManager: DatabaseManager

    private val launchViewModel: LaunchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FusionCoreTheme {
                var hasPermissions by remember { mutableStateOf(false) }

                val checkPermissions = {
                    hasPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Environment.isExternalStorageManager()
                    } else {
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                    }
                }

                checkPermissions()

                if (hasPermissions) {
                    RootScreen { appId ->
                        launchApp(launchViewModel, appId)
                    }
                } else {
                    StorageScreen(
                        onPermissionGranted = {
                            databaseManager.switchToExternalStorage()
                            checkPermissions()
                        }
                    )
                }
            }
        }
    }

    fun launchApp(launchViewModel: LaunchViewModel, appId: String) {
        Log.i(TAG, "Received intent to launch app: $appId")

        launchViewModel.launchBootstrapApp(appId) { settings ->
            val intent = Intent(this, BootstrapActivity::class.java).apply {
                putExtra(BootstrapActivity.EXTRA_TARGET_PACKAGE, appId)
                putExtra(
                    BootstrapActivity.EXTRA_USE_ORIGINAL_LIBUNITY,
                    !settings.useUnstrippedUnity
                )
                addFlags(
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }

            Log.i(TAG, "Launching app: $appId")
            startActivity(intent)
            finish()
        }
    }
}