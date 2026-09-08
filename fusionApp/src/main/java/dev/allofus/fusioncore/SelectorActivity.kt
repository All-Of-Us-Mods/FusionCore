package dev.allofus.fusioncore

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.highcapable.betterandroid.ui.extension.component.launch
import com.highcapable.betterandroid.ui.extension.insets.factory.handleOnWindowInsetsChanged
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.FrameLayout
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.android.widget.TextView
import com.highcapable.hikage.widget.android.widget.Toolbar
import com.highcapable.hikage.widget.androidx.recyclerview.widget.RecyclerView
import com.highcapable.hikage.widget.com.google.android.material.appbar.AppBarLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.button.MaterialButton
import dev.allofus.fusioncore.data.PermissionsManager
import dev.allofus.fusioncore.presentation.SelectorUiState
import dev.allofus.fusioncore.presentation.SelectorViewModel
import dev.allofus.fusioncore.tools.CrashDetector
import dev.allofus.fusioncore.ui.AppAdapter
import dev.allofus.fusioncore.ui.AppItemLayout.ID_PLAY_BTN

class SelectorActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SelectorActivity"
    }

    private val vm: SelectorViewModel by viewModels()

    private lateinit var adapter: AppAdapter

    private var pendingLaunchPackage: String? = null

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (PermissionsManager.hasExternalStorageManagerAccess()) {
            CrashDetector.init(this)
            pendingLaunchPackage?.let { packageName ->
                launchBootstrap(packageName)
            }
        } else {
            toast(getString(R.string.selector_storage_permission_required), Toast.LENGTH_LONG)
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(
            RequestMultiplePermissions()
        ) { result ->
            for (entry in result.entries) {
                val permission = entry.key
                val isGranted: Boolean = entry.value

                if (isGranted) {
                    Log.i(TAG, "Got permission: $permission")
                } else {
                    Log.e(TAG, "Permission denied: $permission")
                }

                pendingLaunchPackage?.let { packageName ->
                    launchBootstrap(packageName)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        adapter = AppAdapter(
            launch = { appInfo ->
                checkPermissionsAndLaunch(appInfo.packageName)
            },
            openSettings = {
                val intent = Intent(this, GameSettingsActivity::class.java).apply {
                    putExtra(GameSettingsActivity.EXTRA_PACKAGE_NAME, it.packageName)
                }
                startActivity(intent)
            }
        )

        val hikage = Hikagable(this) {
            LinearLayout(
                id = "root",
                lparams = LayoutParams(matchParent = true),
                init = { orientation = LinearLayout.VERTICAL }
            ) {
                AppBarLayout(
                    lparams = LayoutParams(widthMatchParent = true)
                ) {
                    MaterialToolbar(
                        lparams = LayoutParams(widthMatchParent = true)
                    ) {
                        title = stringResource(R.string.app_name)
                    }
                }

                FrameLayout(
                    lparams = LayoutParams(widthMatchParent = true, heightMatchParent = true)
                ) {
                    RecyclerView(
                        id = "recycler",
                        lparams = LayoutParams(matchParent = true)
                    ) {
                        layoutManager = LinearLayoutManager(context)
                        adapter = this@SelectorActivity.adapter
                        clipToPadding = false
                    }

                    TextView(
                        id = "loading",
                        lparams = LayoutParams(matchParent = true)
                    ) {
                        text = stringResource(R.string.loading)
                        gravity = Gravity.CENTER
                    }

                    TextView(
                        id = "error",
                        lparams = LayoutParams(matchParent = true)
                    ) {
                        textColor = Color.RED
                        gravity = Gravity.CENTER
                        visibility = View.GONE
                    }
                }
            }
        }

        setContentView(hikage.root)

        hikage.root.handleOnWindowInsetsChanged { view, insetsWrapper ->
            val bars = insetsWrapper.systemBars
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
        }

        if (!PermissionsManager.hasExternalStorageManagerAccess() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestManageExternalStorage()
        } else {
            CrashDetector.init(this)
        }

        launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    when (state) {
                        is SelectorUiState.Loading -> {
                            hikage.get<TextView>("loading").visibility = View.VISIBLE
                            hikage.get<TextView>("error").visibility = View.GONE
                        }
                        is SelectorUiState.Success -> {
                            hikage.get<TextView>("loading").visibility = View.GONE
                            hikage.get<TextView>("error").visibility = View.GONE
                            adapter.submitList(state.apps)
                        }
                        is SelectorUiState.Error -> {
                            hikage.get<TextView>("loading").visibility = View.GONE
                            hikage.get<TextView>("error").visibility = View.VISIBLE
                            hikage.get<TextView>("error").text = state.message
                        }
                    }
                }
            }
        }
    }

    private fun launchBootstrap(packageName: String?) {
        val useUnstrippedUnity = FusionSettings.getUseUnstrippedLibUnityForGame(this, packageName)
        val intent = Intent(this, BootstrapActivity::class.java).apply {
            putExtra(BootstrapActivity.EXTRA_TARGET_PACKAGE, packageName)
            putExtra(BootstrapActivity.EXTRA_USE_ORIGINAL_LIBUNITY, !useUnstrippedUnity)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun checkPermissionsAndLaunch(packageName: String) {
        if (!PermissionsManager.hasExternalStorageManagerAccess()
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingLaunchPackage = packageName
            requestManageExternalStorage()
            return
        }

        try {
            val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val perms = packageInfo.requestedPermissions
            if (perms != null) {
                val newPerms = perms.filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }.toTypedArray()

                if (!newPerms.isEmpty()) {
                    pendingLaunchPackage = packageName
                    requestPermissionsLauncher.launch(newPerms)
                    return
                }
            }
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "Failure getting package info for $packageName", e)
        }

        launchBootstrap(packageName)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestManageExternalStorage() {
        toast(getString(R.string.selector_storage_permission_prompt), Toast.LENGTH_LONG)

        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData("package:$packageName".toUri())

        launchManageStorage(intent)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun launchManageStorage(intent: Intent) {
        try {
            manageStorageLauncher.launch(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open app-specific all-files access screen, opening generic page", e)
            try {
                manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (inner: Exception) {
                Log.e(TAG, "Failed to open all-files access settings", inner)
                toast(
                    getString(R.string.selector_storage_permission_open_failed),
                    Toast.LENGTH_LONG
                )
            }
        }
    }
}