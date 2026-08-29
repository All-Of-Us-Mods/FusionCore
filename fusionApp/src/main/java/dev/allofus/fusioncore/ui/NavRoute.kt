package dev.allofus.fusioncore.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable object SelectorRoute : NavKey

@Serializable object AppSettingsRoute : NavKey

@Serializable class GameSettingsRoute(val targetPackage: String) : NavKey

@Serializable class BootstrapRoute(val targetPackage: String) : NavKey
