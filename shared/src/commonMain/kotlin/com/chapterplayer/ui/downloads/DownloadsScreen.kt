package com.chapterplayer.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.chapterplayer.ui.components.EmptyState
import com.chapterplayer.ui.components.SettingsAction
import com.chapterplayer.ui.theme.BackgroundDeep

/** Downloads tab. Downloading arrives in Stage 3, so for now it only explains that. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onOpenSettings: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Downloads", style = MaterialTheme.typography.headlineSmall) },
            actions = { SettingsAction(onOpenSettings) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = BackgroundDeep,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            EmptyState(
                icon = Icons.Default.Download,
                message = "No downloads yet. Downloading arrives in a later update"
            )
        }
    }
}
