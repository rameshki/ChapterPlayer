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
    /**
     * Empty. Tokens live in the encrypted credential store. The column stays (a user upgrading
     * straight from v0.1.0 may still have a token in it); a later migration drops it. Older installs still hold the token
     * here until [com.chapterplayer.data.repository.ServerRepositoryImpl] moves it.
     */
    val token: String,
    val libraryId: String?
) {
    fun toDomain(
        token: String,
        refreshToken: String? = null,
        excludedLibraryIds: Set<String> = emptySet()
    ): ServerConfig = ServerConfig(
        id = id,
        source = ServerSource.valueOf(source),
        name = name,
        baseUrl = baseUrl,
        token = token,
        libraryId = libraryId,
        refreshToken = refreshToken,
        excludedLibraryIds = excludedLibraryIds
    )

    companion object {
        /** The row for [config]. The token is deliberately left out. */
        fun fromDomain(config: ServerConfig): ServerConfigEntity = ServerConfigEntity(
            id = config.id,
            source = config.source.name,
            name = config.name,
            baseUrl = config.baseUrl,
            token = "",
            libraryId = config.libraryId
        )
    }
}
