package com.chapterplayer.data.local.entity

import androidx.room.Entity
import com.chapterplayer.data.model.LibraryInfo

/** A library of a server, kept so screens can name and group books by library. */
@Entity(tableName = "libraries", primaryKeys = ["serverId", "id"])
data class LibraryEntity(
    val serverId: String,
    val id: String,
    val name: String
) {
    fun toDomain() = LibraryInfo(serverId = serverId, id = id, name = name)

    companion object {
        fun fromDomain(info: LibraryInfo) = LibraryEntity(serverId = info.serverId, id = info.id, name = info.name)
    }
}
