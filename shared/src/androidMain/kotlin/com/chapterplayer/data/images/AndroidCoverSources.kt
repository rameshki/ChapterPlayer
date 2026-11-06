package com.chapterplayer.data.images

import com.chapterplayer.data.downloads.AndroidDownloadFiles
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.DownloadDao
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.repository.ServerRepository

/** [CoverSources] on the phone: the book table, the server list, the download folder's `cover.jpg` and `BitmapFactory`. */
class AndroidCoverSources(
    private val books: BookDao,
    private val servers: ServerRepository,
    private val downloads: DownloadDao,
) : CoverSources {
    override suspend fun coverOf(bookId: String): BookCover? = books.getById(bookId)?.let { BookCover(it.serverId, it.coverUrl) }

    override suspend fun server(serverId: String): ServerConfig? = servers.getById(serverId)

    override suspend fun localCoverPath(bookId: String): String? = downloadedCoverPath(downloads, AndroidDownloadFiles, bookId)

    override fun readLocalCover(path: String): ByteArray? = AndroidCoverScaler.readSmallFile(path)

    override fun downscale(bytes: ByteArray): ByteArray? = AndroidCoverScaler.downscale(bytes)
}
