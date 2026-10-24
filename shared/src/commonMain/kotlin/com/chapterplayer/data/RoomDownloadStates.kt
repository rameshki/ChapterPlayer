package com.chapterplayer.data

import com.chapterplayer.data.local.dao.DownloadDao
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.domain.downloads.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Download states from the `downloads` table: DONE is on the phone, any other status is in progress. */
class RoomDownloadStates(private val dao: DownloadDao) : DownloadStates {
    override fun observe(): Flow<Map<String, DownloadState>> = dao.observeAll().map { rows ->
        rows.associate { row ->
            row.bookId to if (row.status == DownloadStatus.DONE.name) DownloadState.DOWNLOADED else DownloadState.DOWNLOADING
        }
    }
}
