package com.chapterplayer.data

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.dto.AbsLoginRequest
import com.chapterplayer.data.remote.abs.dto.AbsUserDto
import com.chapterplayer.ui.settings.PlexServerOption
import com.chapterplayer.ui.settings.ServerSetupUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretsToStringTest {

    @Test
    fun `a login request does not print the password`() {
        val text = AbsLoginRequest("reader", "pw-secret-made-up").toString()
        assertFalse("pw-secret-made-up" in text)
        assertTrue("reader" in text)
    }

    @Test
    fun `a user dto does not print its tokens`() {
        val text = AbsUserDto("u1", "reader", "user", "legacy-secret", "access-secret", "refresh-secret").toString()
        assertFalse("legacy-secret" in text || "access-secret" in text || "refresh-secret" in text)
    }

    @Test
    fun `a server config does not print its tokens`() {
        val text = ServerConfig(
            "s1", ServerSource.ABS, "Home", "http://abs.test", "token-secret",
            refreshToken = "refresh-secret", excludedLibraryIds = setOf("lib-private-id")
        ).toString()
        assertFalse("token-secret" in text || "refresh-secret" in text)
        assertTrue("Home" in text)
    }

    @Test
    fun `the setup state does not print its tokens`() {
        val text = ServerSetupUiState(token = "token-secret", baseUrl = "http://abs.test").toString()
        assertFalse("token-secret" in text)
    }

    @Test
    fun `a plex server option does not print its access token`() {
        val text = PlexServerOption("Plex", "http://plex.test", "plex-secret").toString()
        assertFalse("plex-secret" in text)
    }
}
