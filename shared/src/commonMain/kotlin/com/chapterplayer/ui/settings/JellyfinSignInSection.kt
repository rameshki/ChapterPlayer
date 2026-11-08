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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.TextSecondary

/** The Jellyfin add flow: address, username and password, then the choice of Books libraries. */
@Composable
internal fun JellyfinAddSection(
    state: JellyfinSetupState,
    onAddressChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSignIn: () -> Unit,
    onLibraryToggled: (String) -> Unit,
    onSave: () -> Unit,
) {
    when (state.step) {
        JellyfinStep.SIGN_IN -> JellyfinSignInForm(state, onAddressChanged, onUsernameChanged, onPasswordChanged, onSignIn)
        JellyfinStep.LIBRARIES -> JellyfinLibraryChoice(state, onLibraryToggled, onSave)
    }
}

/**
 * Edit mode: sign in again to replace the stored sign-in (the password is never stored). The address is edited above,
 * in the shared form; an address that now belongs to another Jellyfin server is refused.
 */
@Composable
internal fun JellyfinSignInAgainSection(
    state: JellyfinSetupState,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Sign in again",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Needed when this server asks you to sign in again, or after you change its address. " +
                "Your libraries and choices stay as they are.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        JellyfinSignInForm(
            state = state,
            onAddressChanged = {},
            onUsernameChanged = onUsernameChanged,
            onPasswordChanged = onPasswordChanged,
            onSignIn = onSignIn,
            showAddress = false,
            buttonLabel = "Sign in again"
        )
    }
}

@Composable
private fun JellyfinSignInForm(
    state: JellyfinSetupState,
    onAddressChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSignIn: () -> Unit,
    showAddress: Boolean = true,
    buttonLabel: String = "Sign in",
) {
    val submit = { if (!state.isSigningIn) onSignIn() }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showAddress) ChapterTextField(
            value = state.address,
            onValueChange = onAddressChanged,
            label = "Server address",
            placeholder = "http://192.168.1.10:8096",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next
        )
        ChapterTextField(
            value = state.username,
            onValueChange = onUsernameChanged,
            label = "Username",
            placeholder = "Your Jellyfin username",
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

        state.signInError?.let { JellyfinErrorText(it) }

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
                    modifier = Modifier.size(20.dp).semantics { contentDescription = "Signing in" },
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(text = buttonLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun JellyfinLibraryChoice(
    state: JellyfinSetupState,
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
            text = "Only Books libraries are listed. Books libraries added later show up automatically. " +
                "You can change this in Settings later.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        state.saveError?.let { JellyfinErrorText(it) }
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
                    modifier = Modifier.size(20.dp).semantics { contentDescription = "Saving" },
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
private fun JellyfinErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}
