package com.chapterplayer.data.repository

import com.chapterplayer.data.model.ServerConfig
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for CRUD operations on server configurations.
 * Implementations handle persistence (Room) and any validation logic.
 */
interface ServerRepository {
    fun observeAll(): Flow<List<ServerConfig>>
    suspend fun getById(id: String): ServerConfig?
    suspend fun save(config: ServerConfig)

    /**
     * Saves [config] after the USER typed a new address for it (Edit server), with its token dropped on purpose: unlike
     * [save], the old token is removed for sure even when the last credential read failed, so it can never come back
     * for the new address (a failed removal fails the save). Automatic address changes (Plex's connection switch) use
     * [save], which keeps the token. Implementations without a credential store save as usual.
     */
    suspend fun saveUserAddressChange(config: ServerConfig) = save(config)
    suspend fun delete(id: String)

    /**
     * Turns one library of a server on or off. Changes only the turned-off list: the server row, the
     * token and the refresh token are never touched. An unknown [serverId] does nothing.
     *
     * Turning a library off removes that library's cached books. Listening progress stays: the
     * `progress` table has no foreign keys (guarded by `RoomSchemaTest`). Turning it
     * on removes only the turned-off entry, so the caller must then sync the server
     * ([BookRepository.syncBooksFromServer] with the fresh [getById] config) to bring the books back.
     */
    suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean)
}
