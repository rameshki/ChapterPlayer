package com.chapterplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.FieldBorder
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.SurfaceSecondary
import com.chapterplayer.ui.theme.TextSecondary

// ─────────────────────────────────────────────────────────────────────────────
// Settings Screen — server list
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onAddServer: () -> Unit,
    onEditServer: (String) -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val uiState by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDeep,
        topBar = {
            TopAppBar(
                title = {
                    Text("Settings", style = MaterialTheme.typography.headlineSmall)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDeep)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddServer,
                containerColor = AmberGold,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add server")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 88.dp)
        ) {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            AppearanceCard(selected = themeMode, onSelect = viewModel::setThemeMode)
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Connected Servers",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            if (uiState.servers.isEmpty()) {
                Text(
                    text = "No servers added yet. Tap + to connect to Audiobookshelf or Plex.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                uiState.servers.forEach { server ->
                    Spacer(Modifier.height(8.dp))
                    ServerRow(
                        server = server,
                        onEdit = { onEditServer(server.id) },
                        onDelete = { viewModel.deleteServer(server.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceCard(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit
) {
    val options = listOf(
        ThemeMode.DARK to "Dark",
        ThemeMode.LIGHT to "Light",
        ThemeMode.SYSTEM to "Follow system"
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.selectableGroup()) {
            options.forEach { (mode, label) ->
                val isSelected = selected == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelect(mode) }
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = AmberGold)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else TextSecondary,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${server.source.name} · ${server.baseUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (server.libraryId != null) {
                    Text(
                        text = "Library: ${server.libraryId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = AmberGold)
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Server Setup Screen — add / edit
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSetupScreen(
    serverId: String?,
    onDone: () -> Unit,
    viewModel: ServerSetupViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    // Track whether we've already launched the browser for the current pin,
    // so a recomposition doesn't open it again.
    val browserLaunched = remember { mutableStateOf(false) }

    // Load existing server if editing
    LaunchedEffect(serverId) {
        serverId?.let { viewModel.loadServer(it) }
    }

    // Navigate back after successful save
    LaunchedEffect(uiState.savedSuccessfully) {
        if (uiState.savedSuccessfully) onDone()
    }

    // Open the Plex auth page in the system browser when a new pin code is ready
    LaunchedEffect(uiState.plexPinCode) {
        val code = uiState.plexPinCode
        if (code == null) {
            browserLaunched.value = false   // Reset so next OAuth attempt works
            return@LaunchedEffect
        }
        if (browserLaunched.value) return@LaunchedEffect
        browserLaunched.value = true
        val authUrl = buildPlexAuthUrl(code, uiState.plexClientId)
        uriHandler.openUri(authUrl)
    }

    // Server picker dialog — shown after OAuth completes
    if (uiState.plexOAuthStep == PlexOAuthStep.PICKING_SERVER) {
        PlexServerPickerDialog(
            servers = uiState.availablePlexServers,
            onSelect = { viewModel.onPlexServerSelected(it) },
            onDismiss = { viewModel.cancelPlexOAuth() }
        )
    }

    Scaffold(
        containerColor = BackgroundDeep,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (serverId == null) "Add Server" else "Edit Server",
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancelPlexOAuth()
                        onDone()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDeep)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Server type selector
            Text(
                text = "Server Type",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ServerSource.entries.forEach { source ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = uiState.source == source,
                            onClick = { viewModel.onSourceChanged(source) },
                            colors = RadioButtonDefaults.colors(selectedColor = AmberGold)
                        )
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            // Display name — shared for both server types
            ChapterTextField(
                value = uiState.name,
                onValueChange = viewModel::onNameChanged,
                label = "Display Name",
                placeholder = if (uiState.source == ServerSource.ABS) "e.g. Home ABS" else "e.g. My Plex"
            )

            // ── ABS fields ────────────────────────────────────────────────
            if (uiState.source == ServerSource.ABS) {
                ChapterTextField(
                    value = uiState.baseUrl,
                    onValueChange = viewModel::onBaseUrlChanged,
                    label = "Server URL",
                    placeholder = "http://192.168.1.10:8080",
                    keyboardType = KeyboardType.Uri
                )
                ChapterTextField(
                    value = uiState.token,
                    onValueChange = viewModel::onTokenChanged,
                    label = "API Token",
                    placeholder = "Paste your ABS token here",
                    isPassword = true
                )
                ChapterTextField(
                    value = uiState.libraryId,
                    onValueChange = viewModel::onLibraryIdChanged,
                    label = "Library ID (optional)",
                    placeholder = "Leave blank to load all libraries"
                )
            }

            // ── Plex OAuth flow ───────────────────────────────────────────
            if (uiState.source == ServerSource.PLEX) {
                PlexAuthSection(
                    uiState = uiState,
                    onSignIn = viewModel::startPlexOAuth,
                    onCancel = viewModel::cancelPlexOAuth
                )

                // Library section ID — shown after server is selected
                if (uiState.baseUrl.isNotBlank()) {
                    ChapterTextField(
                        value = uiState.libraryId,
                        onValueChange = viewModel::onLibraryIdChanged,
                        label = "Music Section ID (optional)",
                        placeholder = "e.g. 1  — leave blank to load all music"
                    )
                }
            }

            // Error message
            val errorMsg = uiState.saveError ?: uiState.plexOAuthError
            if (errorMsg != null) {
                Text(
                    text = errorMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Save button
            Button(
                onClick = { viewModel.saveServer(serverId) },
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmberGold,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (serverId == null) "Add Server" else "Save Changes",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Plex auth section — replaces the URL/token fields for Plex servers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlexAuthSection(
    uiState: ServerSetupUiState,
    onSignIn: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                // Already signed in and server selected
                uiState.baseUrl.isNotBlank() && uiState.plexOAuthStep == PlexOAuthStep.IDLE -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AmberGold,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Connected to Plex",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = uiState.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedButton(
                        onClick = onSignIn,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberGold)
                    ) {
                        Text("Sign in with a different account")
                    }
                }

                // Waiting for user to sign in via browser
                uiState.plexOAuthStep == PlexOAuthStep.AWAITING_AUTH -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = AmberGold,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Waiting for sign-in in browser…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "Complete sign-in on the plex.tv page that opened. Come back here when done.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                }

                // Not started yet — show the sign-in button
                else -> {
                    Text(
                        text = "Sign in with your Plex account to automatically discover your servers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Button(
                        onClick = onSignIn,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AmberGold,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            text = "Sign in with Plex",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Server picker dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlexServerPickerDialog(
    servers: List<PlexServerOption>,
    onSelect: (PlexServerOption) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated,
        title = {
            Text(
                text = "Choose a Server",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    text = "Select which Plex Media Server to connect to:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                servers.forEachIndexed { index, server ->
                    if (index > 0) HorizontalDivider(color = SurfaceSecondary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(server) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = server.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = server.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared text field component
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ChapterTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = TextSecondary) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (isPassword) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AmberGold,
            unfocusedBorderColor = FieldBorder,
            focusedLabelColor = AmberGold,
            unfocusedLabelColor = TextSecondary,
            cursorColor = AmberGold,
            focusedTextColor = MaterialTheme.colorScheme.onBackground,
            unfocusedTextColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun buildPlexAuthUrl(pinCode: String, clientId: String): String {
    return "https://app.plex.tv/auth#?" +
        "clientID=$clientId" +
        "&code=$pinCode" +
        "&context[device][product]=$PLEX_PRODUCT" +
        "&context[device][version]=$PLEX_VERSION" +
        "&context[device][platform]=Android"
}

private const val PLEX_PRODUCT = "ChapterPlayer"
private const val PLEX_VERSION = "0.1.0"
