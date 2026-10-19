package com.chapterplayer.data

import com.chapterplayer.data.model.DownloadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Which books are on the phone, by book id. A book missing from the map is not downloaded. */
interface DownloadStates {
    fun observe(): Flow<Map<String, DownloadState>>
}

/** Stage 2: nothing can be downloaded yet, so every book is "not downloaded" until Stage 3. */
class NoDownloadStates : DownloadStates {
    override fun observe(): Flow<Map<String, DownloadState>> = flowOf(emptyMap())
}

fun Map<String, DownloadState>.stateOf(bookId: String): DownloadState =
    this[bookId] ?: DownloadState.NOT_DOWNLOADED
