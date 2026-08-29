package dev.allofus.fusioncore

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.allofus.fusioncore.data.DatabaseManager
import dev.allofus.fusioncore.ui.RootScreen
import dev.allofus.fusioncore.ui.StoragePermissionStartupHandler
import dev.allofus.fusioncore.ui.theme.FusionCoreTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "MainActivity"
    }

    @Inject
    lateinit var databaseManager: DatabaseManager

    fun launchApp(appId: String) {
        Log.i(TAG, "Received intent to launch app: $appId")

        lifecycleScope.launch {
            val useUnstrippedUnity = withContext(Dispatchers.IO) {
                databaseManager.getDatabase().gameSettingsDao().getSettingsForApp(appId)?.useUnstrippedUnity ?: true
            }

            val intent = Intent(this@MainActivity, BootstrapActivity::class.java).apply {
                putExtra(BootstrapActivity.EXTRA_TARGET_PACKAGE, appId)
                putExtra(BootstrapActivity.EXTRA_USE_ORIGINAL_LIBUNITY, !useUnstrippedUnity)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            Log.i(TAG, "Launching app: $appId")
            startActivity(intent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FusionCoreTheme {
                var isPermissionChecked by remember { mutableStateOf(false) }

                StoragePermissionStartupHandler(
                    onPermissionGranted = {
                        databaseManager.switchToExternalStorage()
                        isPermissionChecked = true
                    }
                )

                if (isPermissionChecked) {
                    RootScreen { appId ->
                        launchApp(appId)
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}