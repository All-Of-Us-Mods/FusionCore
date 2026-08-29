package dev.allofus.fusioncore.data

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val packageVersion: String,
    val label: String,
    val icon: Drawable?
)

sealed interface SelectorUiState {
    data object Loading : SelectorUiState
    data class Success(val apps: List<AppInfo>) : SelectorUiState
}