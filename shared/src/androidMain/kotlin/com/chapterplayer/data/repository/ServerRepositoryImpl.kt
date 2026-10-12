package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.ServerConfigDao
import com.chapterplayer.data.local.entity.ServerConfigEntity
import com.chapterplayer.data.model.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val serverConfigDao: ServerConfigDao
) : ServerRepository {

    override fun observeAll(): Flow<List<ServerConfig>> =
        serverConfigDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun getById(id: String): ServerConfig? =
        serverConfigDao.getById(id)?.toDomain()

    override suspend fun save(config: ServerConfig) {
        serverConfigDao.insert(ServerConfigEntity.fromDomain(config))
    }

    override suspend fun delete(id: String) {
        serverConfigDao.deleteById(id)
    }
}
