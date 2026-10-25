package com.chapterplayer.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chapterplayer.ui.components.EmptyState
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.TextSecondary

/** The messages of an empty Library or group screen. Each is scrollable so pull to refresh still works. */
@Composable
fun ScrollingEmpty(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun AmberButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = AmberGold,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) { Text(text) }
}

@Composable
fun NoServersState(onOpenServers: () -> Unit) = ScrollingEmpty {
    EmptyState(Icons.Default.LibraryBooks, "Add a server to see your audiobooks here.") {
        AmberButton("Add a server", onOpenServers)
    }
}

@Composable
fun NothingYetState() = ScrollingEmpty {
    EmptyState(Icons.Default.LibraryBooks, "Nothing here yet. Pull to refresh.")
}

@Composable
fun AllLibrariesHiddenState(onOpenLibraries: () -> Unit) = ScrollingEmpty {
    EmptyState(Icons.Default.LibraryBooks, "All libraries are hidden") {
        AmberButton("Choose libraries", onOpenLibraries)
    }
}

@Composable
fun SyncingState() = ScrollingEmpty {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        CircularProgressIndicator(color = AmberGold)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Syncing your libraries...",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary
        )
    }
}

@Composable
fun SignInNeededState(names: List<String>) = ScrollingEmpty {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Text(
            text = "Sign in again in Settings",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = names.joinToString("\n") { "$it: Sign in again in Settings" },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun FilteredOutState(onClear: () -> Unit) = ScrollingEmpty {
    EmptyState(Icons.Default.Warning, "No books match the current filters.") {
        AmberButton("Clear filters", onClear)
    }
}

@Composable
fun NothingDownloadedState() = ScrollingEmpty {
    EmptyState(Icons.Default.CloudDownload, "Nothing downloaded here yet")
}

/** A grouped view that has no groups and no filter to blame, such as Series when no book belongs to one. */
@Composable
fun EmptyViewState(view: LibraryView) = ScrollingEmpty {
    EmptyState(
        Icons.Default.LibraryBooks,
        when (view) {
            LibraryView.SERIES -> "None of your books belong to a series yet."
            LibraryView.COLLECTIONS -> "No collections yet. Collections you make on your Audiobookshelf server show up here."
            else -> "Nothing to show here yet."
        }
    )
}
