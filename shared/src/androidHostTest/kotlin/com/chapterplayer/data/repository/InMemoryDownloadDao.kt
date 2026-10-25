package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.DownloadDao
import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.local.entity.DownloadFileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Download fake: REPLACE on the primary keys and the same ordering and conditions as the real queries. */
class InMemoryDownloadDao(
    initial: List<DownloadEntity> = emptyList(),
    initialFiles: List<DownloadFileEntity> = emptyList()
) : DownloadDao {
    val downloads = MutableStateFlow(initial)
    val files = MutableStateFlow(initialFiles)

    override fun observeAll(): Flow<List<DownloadEntity>> = downloads
    override fun observeFiles(): Flow<List<DownloadFileEntity>> =
        files.map { it.sortedWith(compareBy({ f -> f.bookId }, { f -> f.sortOrder })) }
    override suspend fun getFiles(bookId: String): List<DownloadFileEntity> =
        files.value.filter { it.bookId == bookId }.sortedBy { it.sortOrder }
    override suspend fun get(bookId: String): DownloadEntity? = downloads.value.firstOrNull { it.bookId == bookId }
    override suspend fun upsert(download: DownloadEntity) {
        downloads.value = downloads.value.filterNot { it.bookId == download.bookId } + download
    }
    override suspend fun upsertFiles(files: List<DownloadFileEntity>) {
        for (file in files) {
            this.files.value = this.files.value.filterNot { it.bookId == file.bookId && it.ino == file.ino } + file
        }
    }
    override suspend fun updateStatus(bookId: String, status: String, error: String?, updatedAt: Long) {
        downloads.value = downloads.value.map {
            if (it.bookId == bookId) it.copy(status = status, error = error, updatedAt = updatedAt) else it
        }
    }
    override suspend fun updateStatusIf(bookId: String, from: List<String>, status: String, error: String?, updatedAt: Long): Int {
        var changed = 0
        downloads.value = downloads.value.map {
            if (it.bookId == bookId && it.status in from) {
                changed++
                it.copy(status = status, error = error, updatedAt = updatedAt)
            } else it
        }
        return changed
    }
    override suspend fun updateFileProgress(bookId: String, ino: String, doneBytes: Long) {
        files.value = files.value.map { if (it.bookId == bookId && it.ino == ino) it.copy(doneBytes = doneBytes) else it }
    }
    override suspend fun fillFileDuration(bookId: String, ino: String, durationMs: Long) {
        files.value = files.value.map {
            if (it.bookId == bookId && it.ino == ino && it.durationMs <= 0L) it.copy(durationMs = durationMs) else it
        }
    }
    override suspend fun delete(bookId: String) { downloads.value = downloads.value.filterNot { it.bookId == bookId } }
    override suspend fun deleteFiles(bookId: String) { files.value = files.value.filterNot { it.bookId == bookId } }
    override suspend fun deleteBook(bookId: String) {
        deleteFiles(bookId)
        delete(bookId)
    }
}
