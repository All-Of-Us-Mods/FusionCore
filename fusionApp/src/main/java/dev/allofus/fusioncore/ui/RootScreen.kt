package dev.allofus.fusioncore.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.allofus.fusioncore.ui.icons.arrow_back
import dev.allofus.fusioncore.viewmodels.GameSettingsViewModel

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick) {
        Icon(arrow_back, contentDescription = null)
    }
}

@Composable
fun RootScreen(onLaunchApp: (String) -> Unit) {
    val backStack = rememberNavBackStack(SelectorRoute)
    Surface {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator()
            ),
            entryProvider = entryProvider {
                entry<SelectorRoute> {
                    SelectorScreen(
                        selectorViewModel = hiltViewModel(),
                        onAppSelected = { appId -> backStack.add(GameSettingsRoute(appId)) },
                        onLaunchApp = onLaunchApp,
                        onOpenSettings = { backStack.add(AppSettingsRoute) }
                    )
                }
                entry<GameSettingsRoute> { key ->
                    val viewmodel =
                        hiltViewModel<GameSettingsViewModel, GameSettingsViewModel.Factory>(
                            creationCallback = { factory ->
                                factory.create(key)
                            }
                        )
                    GameSettingsScreen(
                        gameSettingsViewModel = viewmodel,
                        onLaunchApp = onLaunchApp,
                        onBack = { backStack.removeLastOrNull() }
                    )
                }
                entry<AppSettingsRoute> { key ->
                    AppSettingsScreen { backStack.removeLastOrNull() }
                }
            }
        )
    }
}