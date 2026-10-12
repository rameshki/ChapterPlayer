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
    suspend fun delete(id: String)
}
