package com.chapterplayer.data.sync

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerSyncStatusTest {

    private val abs = ServerConfig("a1", ServerSource.ABS, "ABS", "http://abs.test", "t")
    private val plex = ServerConfig("p1", ServerSource.PLEX, "Plex", "http://192.0.2.11:32400", "t")

    @Test
    fun `an audiobookshelf server uses its own address`() {
        val status = serverSyncStatus(abs, ServerStatusRecord(lastSuccessAtMs = 10), bookCount = 342, syncing = false)

        assertEquals(ServerSyncPhase.Idle(lastSuccessAtMs = 10, bookCount = 342), status.phase)
        assertEquals("Audiobookshelf address", status.connectionLabel)
    }

    @Test
    fun `a plex server names the kind of connection in use and the last probe`() {
        val record = ServerStatusRecord(
            candidates = listOf(
                StoredCandidate("https://192-0-2-11.abc.plex.direct:32400", "LOCAL"),
                StoredCandidate("http://192.0.2.11:32400", "LOCAL_ADDRESS"),
            ),
            probeFailures = listOf(StoredProbeFailure("https://192-0-2-11.abc.plex.direct:32400", "LOCAL", "name not found")),
            probeNote = "This network blocks the plex.direct name; using the local address",
        )

        val status = serverSyncStatus(plex, record, bookCount = 0, syncing = false)

        assertEquals("Local address", status.connectionLabel)
        assertEquals("This network blocks the plex.direct name; using the local address", status.connectionNote)
        assertEquals(listOf(ProbeFailureInfo("https://192-0-2-11.abc.plex.direct:32400", "Local", "name not found")), status.probeFailures)
        assertEquals(ServerSyncPhase.Idle(lastSuccessAtMs = null, bookCount = 0), status.phase)
    }

    @Test
    fun `a plex server whose address is not in the list uses the saved address label`() {
        assertEquals("Saved address", serverSyncStatus(plex, ServerStatusRecord(), 0, false).connectionLabel)
    }

    @Test
    fun `syncing wins, then a failure newer than the last success`() {
        val failed = ServerStatusRecord(lastSuccessAtMs = 10, lastErrorText = "timeout", lastErrorAtMs = 20)

        assertEquals(ServerSyncPhase.Syncing, serverSyncStatus(abs, failed, 1, syncing = true).phase)
        assertEquals(ServerSyncPhase.Failed("timeout", 20), serverSyncStatus(abs, failed, 1, syncing = false).phase)
        val recovered = failed.copy(lastSuccessAtMs = 30)
        assertEquals(ServerSyncPhase.Idle(30, 1), serverSyncStatus(abs, recovered, 1, syncing = false).phase)
    }

    @Test
    fun `failure reasons are short words`() {
        assertEquals("Could not reach the server: timeout", syncFailureReason(SocketTimeoutException("connect timed out")))
        assertEquals("Could not reach the server: name not found", syncFailureReason(UnknownHostException("x.plex.direct")))
        assertEquals("Sign in again in Settings", syncFailureReason(SignInRequiredException("x")))
        assertEquals("HTTP 500 Internal Server Error", syncFailureReason(IllegalStateException("HTTP 500 Internal Server Error")))
        assertEquals("Unknown error", syncFailureReason(IllegalStateException()))
    }
}
