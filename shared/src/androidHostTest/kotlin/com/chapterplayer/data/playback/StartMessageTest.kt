package com.chapterplayer.data.playback

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.remote.HttpStatusException
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class StartMessageTest {
    @Test
    fun `only fixed texts come out, never the raw exception text`() {
        assertEquals("Sign in again in Settings", safeStartMessage(SignInRequiredException("Home")))
        assertEquals("Sign in again in Settings", safeStartMessage(HttpStatusException(HttpStatusCode.Unauthorized)))
        assertEquals("This book is no longer on the server", safeStartMessage(HttpStatusException(HttpStatusCode.NotFound)))
        assertEquals("The server could not start playback", safeStartMessage(HttpStatusException(HttpStatusCode.InternalServerError)))
        assertEquals("Cannot reach the server", safeStartMessage(IOException("timeout for http://secret.example/x")))
        assertEquals("This server cannot play books yet", safeStartMessage(IllegalStateException("This server cannot play books yet")))
        assertEquals("Could not start playback", safeStartMessage(IllegalStateException("token=abc http://x")))
        assertEquals("Could not start playback", safeStartMessage(RuntimeException()))
    }
}
