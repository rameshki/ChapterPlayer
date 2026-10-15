package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.ExcludedLibraryDao
import com.chapterplayer.data.local.dao.ServerConfigDao
import com.chapterplayer.data.local.entity.ExcludedLibraryEntity
import com.chapterplayer.data.local.entity.ServerConfigEntity
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Server list in Room, tokens in the encrypted [CredentialStore]: the token under the server id,
 * the refresh token under "<id>.refresh". Turned-off libraries are rows in Room. Installs from
 * before the move still hold tokens in the database; the first use moves them.
 */
class ServerRepositoryImpl(
    private val serverConfigDao: ServerConfigDao,
    private val excludedLibraryDao: ExcludedLibraryDao,
    private val credentials: CredentialStore
) : ServerRepository {

    private val migrationLock = Mutex()
    private var migrated = false

    override fun observeAll(): Flow<List<ServerConfig>> = flow {
        ensureTokensMigrated()
        emitAll(
            combine(serverConfigDao.observeAll(), excludedLibraryDao.observeAll()) { rows, excluded -> rows to excluded }
                .map { (rows, excluded) ->
                    val byServer = excluded.groupBy({ it.serverId }, { it.libraryId })
                    rows.map { it.withToken(byServer[it.id].orEmpty().toSet()) }
                }
        )
    }

    override suspend fun getById(id: String): ServerConfig? {
        ensureTokensMigrated()
        val row = serverConfigDao.getById(id) ?: return null
        return row.withToken(excludedLibraryDao.getAll().filter { it.serverId == id }.map { it.libraryId }.toSet())
    }

    override suspend fun save(config: ServerConfig) {
        ensureTokensMigrated()
        migrationLock.withLock {
            val isNew = serverConfigDao.getById(config.id) == null
            try {
                credentials.put(config.id, config.token)
                if (config.refreshToken != null) {
                    credentials.put(refreshKey(config.id), config.refreshToken)
                } else {
                    credentials.remove(refreshKey(config.id))
                }
                serverConfigDao.insert(ServerConfigEntity.fromDomain(config))
            } catch (e: Throwable) {
                if (isNew) {
                    // Don't leave tokens behind for a row that was never written.
                    try { credentials.remove(config.id) } catch (_: Exception) {}
                    try { credentials.remove(refreshKey(config.id)) } catch (_: Exception) {}
                }
                throw e
            }
            // Replace, not append. Two steps, not atomic: a failure between them leaves the
            // server with no turned-off libraries, which only shows more libraries.
            excludedLibraryDao.deleteForServer(config.id)
            if (config.excludedLibraryIds.isNotEmpty()) {
                excludedLibraryDao.insertAll(config.excludedLibraryIds.map { ExcludedLibraryEntity(config.id, it) })
            }
        }
    }

    override suspend fun delete(id: String) {
        ensureTokensMigrated()
        // Token first: a failure leaves a visible row the user can delete again.
        credentials.remove(id)
        credentials.remove(refreshKey(id))
        serverConfigDao.deleteById(id)
        excludedLibraryDao.deleteForServer(id)
    }

    // A row that still holds a token (not migrated yet) wins over the store.
    private suspend fun ServerConfigEntity.withToken(excludedLibraryIds: Set<String>): ServerConfig =
        toDomain(
            token = token.ifEmpty { credentials.get(id).orEmpty() },
            refreshToken = readRefreshToken(id),
            excludedLibraryIds = excludedLibraryIds
        )

    // A missing or unreadable entry means no refresh token; the user signs in again if needed.
    private suspend fun readRefreshToken(id: String): String? =
        try {
            credentials.get(refreshKey(id))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

    private fun refreshKey(id: String) = "$id.refresh"

    /**
     * Copies each token from the database into the store, then blanks the column. Safe to
     * repeat: a row is only blanked after its token has been stored, so an interruption
     * loses nothing and the next run finishes the job.
     */
    private suspend fun ensureTokensMigrated() {
        if (migrated) return
        migrationLock.withLock {
            if (migrated) return
            var failed = false
            serverConfigDao.getAll().filter { it.token.isNotEmpty() }.forEach { row ->
                // A failed row keeps its token in the database (withToken falls back to it).
                try {
                    credentials.put(row.id, row.token)
                    serverConfigDao.insert(row.copy(token = ""))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failed = true
                }
            }
            if (!failed) migrated = true
        }
    }
}
