package dev.allofus.fusioncore.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.test.settings
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import dev.allofus.fusioncore.R
import dev.allofus.fusioncore.data.AppInfo
import dev.allofus.fusioncore.data.SelectorUiState
import dev.allofus.fusioncore.ui.icons.play_arrow
import dev.allofus.fusioncore.viewmodels.SelectorViewModel

@Composable
fun SelectorScreen(
    selectorViewModel: SelectorViewModel,
    onAppSelected: (String) -> Unit,
    onLaunchApp: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val uiState by selectorViewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { SelectorTopBar(onOpenSettings) }
    ) { padding ->
        when (uiState) {
            is SelectorUiState.Loading -> {
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
            is SelectorUiState.Success -> {
                val apps = (uiState as SelectorUiState.Success).apps
                Column(
                    Modifier.padding(padding).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.selector_title), style = MaterialTheme.typography.titleLarge)
                    LazyVerticalGrid(
                        GridCells.Adaptive(minSize = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(apps) {
                            AppCard(appInfo = it, onClick = { onAppSelected(it.packageName) }) {
                                FilledIconButton({
                                    onLaunchApp(it.packageName)
                                }) {
                                    Icon(play_arrow, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectorTopBar(onOpenSettings: () -> Unit) {
    TopAppBar(
        title = { Text(text = stringResource(R.string.app_name)) },
        actions = {
            IconButton(onOpenSettings) {
                Icon(settings, contentDescription = stringResource(R.string.settings_title))
            }
        }
    )
}

@Composable
fun AppCard(appInfo: AppInfo, modifier: Modifier = Modifier, onClick: () -> Unit = {}, actions: @Composable () -> Unit = {}) {
    Card(modifier = modifier, onClick = onClick) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberDrawablePainter(drawable = appInfo.icon),
                contentDescription = appInfo.label,
                modifier = Modifier.size(64.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(text = appInfo.label, style = MaterialTheme.typography.titleMedium)
                Text(text = appInfo.packageName, style = MaterialTheme.typography.bodySmall)
                Text(text = appInfo.packageVersion, style = MaterialTheme.typography.bodySmall)
            }
            actions()
        }
    }
}