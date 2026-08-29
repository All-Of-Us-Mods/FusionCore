package dev.allofus.fusioncore.viewmodels

import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.allofus.fusioncore.data.AppInfo
import dev.allofus.fusioncore.data.GameSettingsData
import dev.allofus.fusioncore.data.GameSettingsRepo
import dev.allofus.fusioncore.ui.GameSettingsRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface GameSettingsUiState {
    data object Loading : GameSettingsUiState
    data class Success(
        val appInfo: AppInfo,
        val settings: GameSettingsData
    ) : GameSettingsUiState
    data class Error(val message: String) : GameSettingsUiState
}

@HiltViewModel(assistedFactory = GameSettingsViewModel.Factory::class)
class GameSettingsViewModel @AssistedInject constructor(
    private val repository: GameSettingsRepo,
    private val packageManager: PackageManager,
    @Assisted private val navKey: GameSettingsRoute
) : ViewModel() {

    private val packageId = navKey.targetPackage

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<GameSettingsUiState> = flow {
        val appInfo = loadAppInfo(packageId)
        emit(appInfo)
    }
        .flatMapLatest { appInfoResult ->
            if (appInfoResult == null) {
                flowOf(GameSettingsUiState.Error("Package not found: $packageId"))
            } else {
                repository.getSettingsFlowForPackage(packageId).map { savedSettings ->
                    GameSettingsUiState.Success(
                        appInfo = appInfoResult,
                        settings = savedSettings ?: GameSettingsData(packageId)
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = GameSettingsUiState.Loading
        )

    private suspend fun loadAppInfo(packageId: String): AppInfo? = withContext(Dispatchers.IO) {
        try {
            val packageInfo = packageManager.getPackageInfo(packageId, 0)
            val appInfo = packageInfo.applicationInfo
            val longVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

            AppInfo(
                packageName = packageInfo.packageName,
                packageVersion = "${packageInfo.versionName} ($longVersionCode)",
                label = appInfo?.loadLabel(packageManager)?.toString() ?: packageId,
                icon = appInfo?.loadIcon(packageManager)
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun updateSettings(newSettings: GameSettingsData) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveSettings(newSettings)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(navKey: GameSettingsRoute): GameSettingsViewModel
    }
}