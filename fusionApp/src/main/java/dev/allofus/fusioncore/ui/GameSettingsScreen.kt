package dev.allofus.fusioncore.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.allofus.fusioncore.ui.icons.android
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import dev.allofus.fusioncore.R
import dev.allofus.fusioncore.data.AppInfo
import dev.allofus.fusioncore.data.GameSettingsData
import dev.allofus.fusioncore.ui.icons.play_arrow
import dev.allofus.fusioncore.viewmodels.GameSettingsUiState
import dev.allofus.fusioncore.viewmodels.GameSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSettingsScreen(
    gameSettingsViewModel: GameSettingsViewModel,
    onLaunchApp: (String) -> Unit,
    onBack: () -> Unit
) {
    val uiState by gameSettingsViewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                { Text(stringResource(R.string.game_settings_title)) },
                navigationIcon = {
                    BackButton(onBack)
                }
            )
        }
    ) { padding ->
        when (uiState) {
            GameSettingsUiState.Loading -> {
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }

            is GameSettingsUiState.Error -> {
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    Text((uiState as GameSettingsUiState.Error).message, Modifier.align(Alignment.Center))
                }
            }

            is GameSettingsUiState.Success -> {
                val appInfo = (uiState as GameSettingsUiState.Success).appInfo
                val settings = (uiState as GameSettingsUiState.Success).settings

                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppMetaCard(appInfo, onLaunchApp)
                    Card {
                        LazyColumn(
                            Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                SectionHeader(stringResource(R.string.low_level))

                                SwitchSetting(
                                    stringResource(R.string.settings_libunity_title),
                                    stringResource(R.string.settings_libunity_desc),
                                    settings.useUnstrippedUnity
                                ) {
                                    gameSettingsViewModel.updateSettings(
                                        settings.copy(
                                            useUnstrippedUnity = !settings.useUnstrippedUnity
                                        )
                                    )
                                }
                            }

                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
fun SwitchSetting(text: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onCheckedChange)
    }
}

@Composable
fun AppMetaCard(appInfo: AppInfo, onLaunchApp: (String) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberDrawablePainter(drawable = appInfo.icon),
                contentDescription = appInfo.label,
                modifier = Modifier.size(96.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(text = appInfo.label, style = MaterialTheme.typography.titleLarge)
                Text(text = appInfo.packageName, style = MaterialTheme.typography.bodyMedium)
                Text(text = appInfo.packageVersion, style = MaterialTheme.typography.bodySmall)
            }
            FilledIconButton({
                onLaunchApp(appInfo.packageName)
            }, Modifier.size(48.dp)) {
                Icon(play_arrow, contentDescription = null)
            }
        }
    }
}
