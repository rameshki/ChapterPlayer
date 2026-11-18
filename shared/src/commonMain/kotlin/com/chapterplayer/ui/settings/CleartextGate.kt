package com.chapterplayer.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chapterplayer.domain.settings.needsCleartextConfirmation
import com.chapterplayer.domain.settings.withHttps
import com.chapterplayer.ui.theme.AmberGold

/**
 * Stands between a sign-in and the network (review R11): a password sent with http to a public host travels unencrypted,
 * so the person is asked first. An address on the home network, or https, goes straight through. "Use https" changes the
 * address in the form and does not sign in; "Continue anyway" signs in as typed.
 */
class CleartextGate {
    private class Pending(val address: String, val setAddress: (String) -> Unit, val proceed: () -> Unit)

    private var pending by mutableStateOf<Pending?>(null)

    /** Runs [proceed] now, or after the person confirms when [address] is http to a public host. */
    fun guard(address: String, setAddress: (String) -> Unit, proceed: () -> Unit) {
        if (needsCleartextConfirmation(address)) pending = Pending(address, setAddress, proceed) else proceed()
    }

    @Composable
    fun Dialog() {
        val current = pending ?: return
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(CLEARTEXT_TITLE) },
            text = { Text(CLEARTEXT_TEXT, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { pending = null; current.setAddress(withHttps(current.address)) }) {
                    Text("Use https", color = AmberGold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null; current.proceed() }) { Text("Continue anyway", color = AmberGold) }
            },
        )
    }
}

@Composable
fun rememberCleartextGate(): CleartextGate = remember { CleartextGate() }

const val NOT_ENCRYPTED_NOTE = "Not encrypted (http)"

const val CLEARTEXT_TITLE = "This address is not encrypted"
const val CLEARTEXT_TEXT =
    "This server is on the public internet and the address starts with http, so your password and sign-in token would " +
        "travel without encryption and anyone on the way could read them. Use https if the server supports it. " +
        "Continue only if you are sure."
