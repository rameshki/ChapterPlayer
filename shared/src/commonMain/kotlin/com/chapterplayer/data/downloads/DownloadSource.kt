package com.chapterplayer.data.downloads

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.downloads.PlannedFile

/**
 * One download request: the address under [server]'s address in use and the headers that carry the credential.
 * [headers] are secret: never log them or put them in a message. [server] is the configuration the request was built
 * from (its current address), so a failure can be judged against it.
 */
class FileFetch(val server: ServerConfig, val url: String, val headers: List<Pair<String, String>>)

/**
 * What a book's download consists of: its [files] in book order and the [chapters] the server knows (empty when it
 * names none). [failure] is set (fixed, safe text) when the book cannot be downloaded; the files are empty then.
 */
data class SourcePlan(val files: List<PlannedFile>, val chapters: List<Chapter>, val failure: String? = null)

/** A file of a planned download is no longer on the server: the book is planned again. */
class DownloadFileGoneException : Exception("The file is no longer on the server")

/**
 * How one kind of server downloads a book. The downloader ([BookDownloader], [FileDownloader]) is the same for every
 * kind; a source only says which files there are and how to ask for one. Rows in `download_files` keep each file's id
 * in the `ino` column: the Audiobookshelf file ino, or the Plex track's rating key.
 */
interface DownloadSource {
    val type: ServerSource

    /** The book's files and chapters. Throws when the server cannot be reached (the job tries again later). */
    suspend fun plan(server: ServerConfig, bookId: String): SourcePlan

    /**
     * Runs [fetch] with the request for the file [fileId] of [bookId]. [fetch] throws an
     * [com.chapterplayer.data.remote.HttpStatusException] 401 when the server refuses the credential; the source then
     * refreshes it once (Audiobookshelf) or gives up with a [com.chapterplayer.data.auth.SignInRequiredException].
     * Throws [DownloadFileGoneException] when the file is no longer in the book.
     */
    suspend fun <T> authorized(server: ServerConfig, bookId: String, fileId: String, fetch: suspend (FileFetch) -> T): T

    /** Like [authorized], for a small file (the cover) at [url]; the caller checks [url] is on the request's server. */
    suspend fun <T> authorizedSmall(server: ServerConfig, url: String, fetch: suspend (FileFetch) -> T): T

    /**
     * A request built from [used] failed before the server answered ([error]). Returns the server to try again with now
     * (its address changed, or a new one was chosen), or null to retry later as usual.
     */
    suspend fun afterConnectionFailure(used: ServerConfig, error: Throwable): ServerConfig? = null

    /** Each file's length in ms by file id, for the files the server knows a length of. */
    suspend fun fileLengths(server: ServerConfig, bookId: String): Map<String, Long>
}

/** The source for [server]'s kind, or null when that kind cannot download. */
fun List<DownloadSource>.forServer(server: ServerConfig): DownloadSource? = firstOrNull { it.type == server.source }
