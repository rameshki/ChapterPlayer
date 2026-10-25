package com.chapterplayer.data.sync

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.plex.PlexConnectionKind
import com.chapterplayer.domain.plex.classifyConnectionError
import com.chapterplayer.domain.plex.ProblemKind

/** Where one server's sync stands. */
sealed interface ServerSyncPhase {
    /** Not syncing; [lastSuccessAtMs] is null before the first successful sync. */
    data class Idle(val lastSuccessAtMs: Long?, val bookCount: Int) : ServerSyncPhase
    data object Syncing : ServerSyncPhase
    /** The last sync failed (after the last success): [message] is short and holds no token. */
    data class Failed(val message: String, val atMs: Long) : ServerSyncPhase
}

/** One failed try of the last probe, for the Servers page: the address, its kind ("Local") and the reason. */
data class ProbeFailureInfo(val uri: String, val kindLabel: String, val reason: String)

/** What the Servers page shows for one server: the sync, the connection in use and, for Plex, the last probe. */
data class ServerSyncStatus(
    val serverId: String,
    val phase: ServerSyncPhase,
    val connectionLabel: String,
    val connectionNote: String? = null,
    val probeFailures: List<ProbeFailureInfo> = emptyList(),
)

const val ABS_CONNECTION_LABEL = "Audiobookshelf address"

/** The status of [server] from its stored [record], its cached [bookCount] and whether it is [syncing] now. */
fun serverSyncStatus(server: ServerConfig, record: ServerStatusRecord, bookCount: Int, syncing: Boolean): ServerSyncStatus {
    val errorAt = record.lastErrorAtMs
    val success = record.lastSuccessAtMs
    val phase = when {
        syncing -> ServerSyncPhase.Syncing
        errorAt != null && (success == null || errorAt >= success) ->
            ServerSyncPhase.Failed(record.lastErrorText ?: "Unknown error", errorAt)
        else -> ServerSyncPhase.Idle(success, bookCount)
    }
    if (server.source != ServerSource.PLEX) return ServerSyncStatus(server.id, phase, ABS_CONNECTION_LABEL)
    val base = server.baseUrl.trimEnd('/')
    val kind = record.candidates.firstOrNull { it.uri.trimEnd('/') == base }?.toCandidate()?.kind ?: PlexConnectionKind.SAVED
    return ServerSyncStatus(
        serverId = server.id,
        phase = phase,
        connectionLabel = kind.label,
        connectionNote = record.probeNote,
        probeFailures = record.probeFailures.map { f ->
            ProbeFailureInfo(f.uri, StoredCandidate(f.uri, f.kind).toCandidate().kind.label, f.reason)
        },
    )
}

/**
 * A short reason for a failed sync: a refused sign-in and a connection failure in fixed words (the platform's text for
 * those names the host and is long), anything else as its message (the app's own messages hold no token).
 */
fun syncFailureReason(error: Throwable): String {
    if (error is SignInRequiredException) return "Sign in again in Settings"
    val problem = classifyConnectionError(error)
    if (problem.kind != ProblemKind.OTHER) return "Could not reach the server: ${problem.text}"
    return error.message?.takeIf { it.isNotBlank() } ?: "Unknown error"
}
