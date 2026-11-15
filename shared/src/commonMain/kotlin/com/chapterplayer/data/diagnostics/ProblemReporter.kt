package com.chapterplayer.data.diagnostics

import com.chapterplayer.data.AppInfo
import com.chapterplayer.data.local.dao.PendingProgressDao
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.data.sync.ServerSyncPhase
import com.chapterplayer.domain.browse.serverKindName
import com.chapterplayer.domain.diagnostics.ProblemReportInput
import com.chapterplayer.domain.diagnostics.ProblemServerLine
import com.chapterplayer.domain.diagnostics.buildProblemReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Builds the redacted "Report a problem" text from what the app knows. It reads only; it never contacts a server. */
class ProblemReporter(
    private val appInfo: AppInfo,
    private val servers: ServerRepository,
    private val sync: LibrarySyncCoordinator,
    private val pending: PendingProgressDao,
    private val crashes: CrashStore,
    private val platform: () -> String = ::platformDescription,
) {
    suspend fun build(): String {
        val list = try { servers.observeAll().first() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        val status = sync.serverStatus.value
        val lines = list.map { server ->
            ProblemServerLine(serverKindName(server.source), !server.needsSignIn, syncLine(status[server.id]?.phase))
        }
        val queued = try { pending.getAll().size } catch (e: CancellationException) { throw e } catch (_: Exception) { -1 }
        return buildProblemReport(
            ProblemReportInput(appInfo.versionName, platform(), lines, queued, crashes.load(), ProblemLog.snapshot())
        )
    }
}

/**
 * How a server's sync stands, in fixed words. A failed sync's own message can quote a library name or a piece of the
 * server's answer, so it is never copied into the report (the recent events and the crash trace carry the detail).
 */
internal fun syncLine(phase: ServerSyncPhase?): String = when (phase) {
    is ServerSyncPhase.Failed -> "last sync failed"
    is ServerSyncPhase.Syncing -> "syncing now"
    is ServerSyncPhase.Idle -> if (phase.lastSuccessAtMs == null) "never synced" else "last sync fine, ${phase.bookCount} books"
    null -> "no status yet"
}
