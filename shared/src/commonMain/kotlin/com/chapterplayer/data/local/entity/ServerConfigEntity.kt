package com.chapterplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource

@Entity(tableName = "server_configs")
data class ServerConfigEntity(
    @PrimaryKey val id: String,
    val source: String,           // ServerSource.name()
    val name: String,
    val baseUrl: String,
    val token: String,
    val libraryId: String?
) {
    fun toDomain(): ServerConfig = ServerConfig(
        id = id,
        source = ServerSource.valueOf(source),
        name = name,
        baseUrl = baseUrl,
        token = token,
        libraryId = libraryId
    )

    companion object {
        fun fromDomain(config: ServerConfig): ServerConfigEntity = ServerConfigEntity(
            id = config.id,
            source = config.source.name,
            name = config.name,
            baseUrl = config.baseUrl,
            token = config.token,
            libraryId = config.libraryId
        )
    }
}
