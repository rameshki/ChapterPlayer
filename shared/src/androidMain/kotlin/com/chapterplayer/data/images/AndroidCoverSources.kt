package com.chapterplayer.data.images

import com.chapterplayer.data.downloads.AndroidDownloadFiles
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.DownloadDao
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.sync.PlexConnectionManager

/** [CoverSources] on the phone: the book table, the server list, the download folder's `cover.jpg`, the Plex address check and `BitmapFactory`. */
class AndroidCoverSources(
    private val books: BookDao,
    private val servers: ServerRepository,
    private val downloads: DownloadDao,
    private val connections: PlexConnectionManager,
) : CoverSources {
    override suspend fun coverOf(bookId: String): BookCover? = books.getById(bookId)?.let { BookCover(it.serverId, it.coverUrl) }

    override suspend fun server(serverId: String): ServerConfig? = servers.getById(serverId)

    override suspend fun localCoverPath(bookId: String): String? = downloadedCoverPath(downloads, AndroidDownloadFiles, bookId)

    override fun readLocalCover(path: String): ByteArray? = AndroidCoverScaler.readSmallFile(path)

    override suspend fun forTokenRequest(server: ServerConfig): ServerConfig? = connections.forCoverRequest(server)

    override fun downscale(bytes: ByteArray): ByteArray? = AndroidCoverScaler.downscale(bytes)
}
