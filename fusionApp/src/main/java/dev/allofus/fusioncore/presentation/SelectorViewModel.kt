package dev.allofus.fusioncore.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.allofus.fusioncore.data.AppRepository
import dev.allofus.fusioncore.data.UnityDetector.isUnityApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SelectorViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<SelectorUiState>(SelectorUiState.Loading)
    val state: StateFlow<SelectorUiState> = _state

    private val _appRepo = AppRepository(app)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apps = _appRepo
                    .getInstalledPackages()
                    .filter { it.applicationInfo?.isUnityApp() == true }
                    .map { _appRepo.getAppInfo(it) }
                _state.value = SelectorUiState.Success(apps)
            } catch (e: Exception) {
                _state.value = SelectorUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}