package dev.allofus.fusioncore.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.allofus.fusioncore.data.GameSettingsData
import dev.allofus.fusioncore.data.GameSettingsRepo
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LaunchViewModel @Inject constructor(
    private val repository: GameSettingsRepo
) : ViewModel() {

    fun launchBootstrapApp(appId: String, onIntentReady: (GameSettingsData) -> Unit) {
        viewModelScope.launch {
            val settings = repository.getSettingsForPackage(appId)
            onIntentReady(settings ?: GameSettingsData(appId))
        }
    }
}