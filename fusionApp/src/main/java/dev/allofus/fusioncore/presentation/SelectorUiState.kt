package dev.allofus.fusioncore.presentation

import dev.allofus.fusioncore.data.AppInfo

sealed interface SelectorUiState {
    object Loading : SelectorUiState
    data class Success(val apps: List<AppInfo>) : SelectorUiState
    data class Error(val message: String) : SelectorUiState
}