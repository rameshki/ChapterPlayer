package com.chapterplayer.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.ui.components.BookCoverPlaceholder
import com.chapterplayer.ui.components.EmptyState
import com.chapterplayer.ui.components.SettingsAction
import coil3.compose.AsyncImage
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel

/** Downloads tab: every download with its progress and buttons, and the storage they use. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onOpenSettings: () -> Unit,
    onBookClick: (bookId: String) -> Unit,
    viewModel: DownloadsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Downloads", style = MaterialTheme.typography.headlineSmall) },
            actions = { SettingsAction(onOpenSettings) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = BackgroundDeep,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            )
        )
        state.headerText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AmberGold)
            }
            state.items.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Default.Download,
                    message = "No downloads yet. Open a book and tap Download to keep it on this phone."
                )
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.bookId }) { item ->
                    DownloadItemRow(
                        item = item,
                        // A book that left the library has no details to open; its row only offers Remove or Cancel.
                        onClick = { if (item.opensDetails) onBookClick(item.bookId) },
                        onPause = { viewModel.pause(item.bookId) },
                        onResume = { viewModel.resume(item.bookId) },
                        onRetry = { viewModel.retry(item.bookId) },
                        onCancel = { viewModel.cancel(item.bookId) },
                        onRemove = { viewModel.askRemove(item.bookId) }
                    )
                }
            }
        }
    }
    state.confirmRemove?.let { request ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRemove,
            containerColor = SurfaceElevated,
            title = { Text("Remove download?") },
            text = { Text("${request.title} is deleted from this phone. It stays on the server and in your library.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRemove) { Text("Keep", color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun DownloadItemRow(
    item: DownloadItem,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .clickable(enabled = item.opensDetails, onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) {
            if (item.coverUrl != null) {
                AsyncImage(model = item.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
            } else {
                BookCoverPlaceholder(title = item.title, modifier = Modifier.size(56.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.author.isNotBlank()) {
                Text(
                    text = item.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.status != DownloadStatus.DONE) {
                LinearProgressIndicator(
                    progress = { item.percent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                    color = AmberGold,
                    trackColor = SurfaceElevated
                )
            }
            Text(
                text = item.statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else TextSecondary,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp)
            )
            item.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        when (item.status) {
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> {
                RowAction(Icons.Default.Pause, "Pause ${item.title}", AmberGold, onPause)
                RowAction(Icons.Default.Close, "Cancel ${item.title}", TextSecondary, onCancel)
            }
            DownloadStatus.PAUSED -> {
                RowAction(Icons.Default.PlayArrow, "Resume ${item.title}", AmberGold, onResume)
                RowAction(Icons.Default.Close, "Cancel ${item.title}", TextSecondary, onCancel)
            }
            DownloadStatus.FAILED -> {
                RowAction(Icons.Default.Refresh, "Try downloading ${item.title} again", AmberGold, onRetry, enabled = item.canRetry)
                RowAction(Icons.Default.Close, "Cancel ${item.title}", TextSecondary, onCancel)
            }
            DownloadStatus.DONE -> RowAction(Icons.Default.Delete, "Remove download ${item.title}", MaterialTheme.colorScheme.error, onRemove)
        }
    }
}

@Composable
private fun RowAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}
