package com.chapterplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.TextSecondary

/** The Audiobookshelf add flow: the sign-in form, then the choice of libraries. */
@Composable
internal fun AbsAddSection(
    state: AbsSetupState,
    onAddressChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onUseApiKeyChanged: (Boolean) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onSignIn: () -> Unit,
    onLibraryToggled: (String) -> Unit,
    onSave: () -> Unit,
) {
    when (state.step) {
        AbsStep.SIGN_IN -> AbsSignInForm(
            state, onAddressChanged, onUsernameChanged, onPasswordChanged,
            onUseApiKeyChanged, onApiKeyChanged, onSignIn
        )
        AbsStep.LIBRARIES -> AbsLibraryChoice(state, onLibraryToggled, onSave)
    }
}

@Composable
private fun AbsSignInForm(
    state: AbsSetupState,
    onAddressChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onUseApiKeyChanged: (Boolean) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    val submit = { if (!state.isSigningIn) onSignIn() }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ChapterTextField(
            value = state.address,
            onValueChange = onAddressChanged,
            label = "Server address",
            placeholder = "http://192.168.1.10:8080",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next
        )
        if (!state.useApiKey) {
            ChapterTextField(
                value = state.username,
                onValueChange = onUsernameChanged,
                label = "Username",
                placeholder = "Your Audiobookshelf username",
                imeAction = ImeAction.Next
            )
            ChapterTextField(
                value = state.password,
                onValueChange = onPasswordChanged,
                label = "Password",
                placeholder = "Your password",
                keyboardType = KeyboardType.Password,
                isPassword = true,
                imeAction = ImeAction.Done,
                onImeAction = submit
            )
        }

        TextButton(
            // Opening the row switches to an API token; closing it goes back to a password.
            onClick = { onUseApiKeyChanged(!state.useApiKey) }
        ) {
            Text(
                text = if (state.useApiKey) "Advanced (hide)" else "Advanced",
                color = AmberGold
            )
        }
        if (state.useApiKey) {
            ChapterTextField(
                value = state.apiKey,
                onValueChange = onApiKeyChanged,
                label = "API token",
                placeholder = "Paste your API token",
                keyboardType = KeyboardType.Password,
                isPassword = true,
                imeAction = ImeAction.Done,
                onImeAction = submit
            )
        }

        state.signInError?.let { ErrorText(it) }

        Button(
            onClick = onSignIn,
            enabled = !state.isSigningIn,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = AmberGold,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            if (state.isSigningIn) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = if (state.useApiKey) "Connect" else "Sign in",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun AbsLibraryChoice(
    state: AbsSetupState,
    onLibraryToggled: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Choose libraries",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        state.libraries.forEach { library ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = library.enabled,
                        role = Role.Checkbox,
                        onValueChange = { onLibraryToggled(library.id) }
                    )
                    .heightIn(min = 48.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = library.enabled,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(
                        checkedColor = AmberGold,
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                Text(
                    text = library.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
        Text(
            text = "Libraries added to this server later show up automatically. " +
                "You can change this in Settings later.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        state.saveError?.let { ErrorText(it) }
        Button(
            onClick = onSave,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = AmberGold,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(text = "Save", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}
