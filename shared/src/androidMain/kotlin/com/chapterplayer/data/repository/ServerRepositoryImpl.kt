package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.ServerConfigDao
import com.chapterplayer.data.local.entity.ServerConfigEntity
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Server list in Room, tokens in the encrypted [CredentialStore]. Installs from before this
 * change still hold tokens in the database; the first use moves them.
 */
class ServerRepositoryImpl(
    private val serverConfigDao: ServerConfigDao,
    private val credentials: CredentialStore
) : ServerRepository {

    private val migrationLock = Mutex()
    private var migrated = false

    override fun observeAll(): Flow<List<ServerConfig>> = flow {
        ensureTokensMigrated()
        emitAll(serverConfigDao.observeAll().map { rows -> rows.map { it.withToken() } })
    }

    override suspend fun getById(id: String): ServerConfig? {
        ensureTokensMigrated()
        return serverConfigDao.getById(id)?.withToken()
    }

    override suspend fun save(config: ServerConfig) {
        ensureTokensMigrated()
        migrationLock.withLock {
            val isNew = serverConfigDao.getById(config.id) == null
            credentials.put(config.id, config.token)
            try {
                serverConfigDao.insert(ServerConfigEntity.fromDomain(config))
            } catch (e: Throwable) {
                if (isNew) {
                    // Don't leave a token behind for a row that was never written.
                    try { credentials.remove(config.id) } catch (_: Exception) {}
                }
                throw e
            }
        }
    }

    override suspend fun delete(id: String) {
        ensureTokensMigrated()
        // Token first: a failure leaves a visible row the user can delete again.
        credentials.remove(id)
        serverConfigDao.deleteById(id)
    }

    // A row that still holds a token (not migrated yet) wins over the store.
    private suspend fun ServerConfigEntity.withToken(): ServerConfig =
        toDomain(token.ifEmpty { credentials.get(id).orEmpty() })

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
