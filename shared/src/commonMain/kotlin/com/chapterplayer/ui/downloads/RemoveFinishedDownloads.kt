package com.chapterplayer.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.domain.downloads.REMOVE_FINISHED_TITLE
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import kotlinx.coroutines.flow.filterNotNull
import org.koin.compose.viewmodel.koinViewModel

/**
 * "Remove finished downloads": a card with the count and the space, the confirmation ("Remove 7 downloads and free
 * 3.4 GB?") and the result in [snackbar]. Shows nothing when no download qualifies.
 */
@Composable
fun RemoveFinishedDownloadsCard(
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: FinishedDownloadsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, snackbar) {
        viewModel.message.filterNotNull().collect { text ->
            viewModel.onMessageShown()
            snackbar.showSnackbar(text)
        }
    }
    state.question?.let { question ->
        AlertDialog(
            onDismissRequest = viewModel::onDismissed,
            containerColor = SurfaceElevated,
            title = { Text(question) },
            text = {
                Text("Downloads of books you have finished are deleted from this phone. The book in the player and the next book in Up next are kept. The books stay on their servers and in your library.")
            },
            confirmButton = {
                TextButton(onClick = viewModel::onConfirmed) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::onDismissed) { Text("Keep", color = TextSecondary) } },
        )
    }
    val summary = state.summary ?: return
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !state.removing, role = Role.Button, onClick = viewModel::onRemoveClicked),
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.removing) {
                CircularProgressIndicator(color = AmberGold, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            } else {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = AmberGold)
            }
            Column(Modifier.weight(1f)) {
                Text(REMOVE_FINISHED_TITLE, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}
