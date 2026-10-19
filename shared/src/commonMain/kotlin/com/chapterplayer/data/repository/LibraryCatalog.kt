package com.chapterplayer.data.repository

import com.chapterplayer.data.model.LibraryToggle
import com.chapterplayer.data.model.ServerLibraries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Every server with its libraries and which of them are turned on. */
interface LibraryCatalog {
    /**
     * Servers ordered by name, each with all its known libraries (ordered by name, ignoring case)
     * and `enabled = id !in server.excludedLibraryIds`. A server whose libraries are not synced yet
     * has an empty list; a server with every library off stays listed.
     */
    fun observeServerLibraries(): Flow<List<ServerLibraries>>
}

class LibraryCatalogImpl(
    private val servers: ServerRepository,
    private val books: BookRepository,
) : LibraryCatalog {

    override fun observeServerLibraries(): Flow<List<ServerLibraries>> =
        combine(servers.observeAll(), books.observeLibraries()) { serverList, libraries ->
            val byServer = libraries.groupBy { it.serverId }
            serverList
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .map { server ->
                    ServerLibraries(
                        server,
                        byServer[server.id].orEmpty()
                            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                            .map { LibraryToggle(it.id, it.name, it.id !in server.excludedLibraryIds) }
                    )
                }
        }
}
