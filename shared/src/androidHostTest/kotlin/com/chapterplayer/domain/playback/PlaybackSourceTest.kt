package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.downloads.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSourceTest {

    private val whole = StoredFile(expectedBytes = 100, bytesOnDisk = 100)

    // -- What the phone holds ------------------------------------------------------------------

    @Test
    fun `a finished download with every file whole is complete`() {
        assertEquals(LocalCopy.COMPLETE, localCopyOf(DownloadStatus.DONE, listOf(whole, StoredFile(5, 5))))
    }

    @Test
    fun `a finished download with a file gone, cut short or grown is missing files`() {
        assertEquals(LocalCopy.MISSING_FILES, localCopyOf(DownloadStatus.DONE, listOf(whole, StoredFile(100, null))))
        assertEquals(LocalCopy.MISSING_FILES, localCopyOf(DownloadStatus.DONE, listOf(StoredFile(100, 99))))
        assertEquals(LocalCopy.MISSING_FILES, localCopyOf(DownloadStatus.DONE, listOf(StoredFile(100, 101))))
    }

    @Test
    fun `a finished download whose folder cannot be seen is unreachable, not missing files`() {
        assertEquals(LocalCopy.UNREACHABLE, localCopyOf(DownloadStatus.DONE, listOf(StoredFile(100, null)), folderPresent = false))
        assertEquals(LocalCopy.NONE, localCopyOf(DownloadStatus.PAUSED, listOf(whole), folderPresent = false))
        assertEquals(false, shouldMarkFailed(LocalCopy.UNREACHABLE))
    }

    @Test
    fun `an unreachable download plays like one that is not on the phone`() {
        assertEquals(PlaybackSource.Stream, choosePlaybackSource(ServerSource.ABS, LocalCopy.UNREACHABLE, online = true, downloadedOnly = false))
        assertEquals(
            PlaybackSource.Unavailable(NEEDS_CONNECTION_TO_PLAY_TEXT),
            choosePlaybackSource(ServerSource.ABS, LocalCopy.UNREACHABLE, online = false, downloadedOnly = false),
        )
    }

    @Test
    fun `a finished download without file rows is missing files`() {
        assertEquals(LocalCopy.MISSING_FILES, localCopyOf(DownloadStatus.DONE, emptyList()))
    }

    @Test
    fun `no download or one that is not finished is nothing on the phone`() {
        assertEquals(LocalCopy.NONE, localCopyOf(null, emptyList()))
        for (status in DownloadStatus.entries - DownloadStatus.DONE) {
            assertEquals(LocalCopy.NONE, localCopyOf(status, listOf(whole)), "$status")
        }
    }

    // -- Where the audio comes from ----------------------------------------------------------

    @Test
    fun `a downloaded book plays from the phone, online or not, whatever the downloaded-only setting`() {
        for (online in listOf(true, false)) for (only in listOf(true, false)) {
            assertEquals(PlaybackSource.Local, choosePlaybackSource(ServerSource.ABS, LocalCopy.COMPLETE, online, only), "online=$online only=$only")
        }
    }

    @Test
    fun `a book not on the phone streams while online`() {
        assertEquals(PlaybackSource.Stream, choosePlaybackSource(ServerSource.ABS, LocalCopy.NONE, online = true, downloadedOnly = false))
    }

    @Test
    fun `a book not on the phone needs a connection offline`() {
        assertEquals(
            PlaybackSource.Unavailable(NEEDS_CONNECTION_TO_PLAY_TEXT),
            choosePlaybackSource(ServerSource.ABS, LocalCopy.NONE, online = false, downloadedOnly = false),
        )
        assertEquals(
            PlaybackSource.Unavailable(NEEDS_CONNECTION_TO_PLAY_TEXT),
            choosePlaybackSource(ServerSource.ABS, LocalCopy.NONE, online = false, downloadedOnly = true),
        )
    }

    @Test
    fun `with downloaded only on, a book not on the phone does not stream`() {
        assertEquals(
            PlaybackSource.Unavailable(NOT_DOWNLOADED_TEXT),
            choosePlaybackSource(ServerSource.ABS, LocalCopy.NONE, online = true, downloadedOnly = true),
        )
    }

    @Test
    fun `a download with missing files counts as not downloaded`() {
        assertEquals(PlaybackSource.Stream, choosePlaybackSource(ServerSource.ABS, LocalCopy.MISSING_FILES, online = true, downloadedOnly = false))
        assertEquals(
            PlaybackSource.Unavailable(NEEDS_CONNECTION_TO_PLAY_TEXT),
            choosePlaybackSource(ServerSource.ABS, LocalCopy.MISSING_FILES, online = false, downloadedOnly = false),
        )
    }

    @Test
    fun `plex books stream online and need a connection offline`() {
        for (copy in LocalCopy.entries) {
            assertEquals(PlaybackSource.Stream, choosePlaybackSource(ServerSource.PLEX, copy, online = true, downloadedOnly = false))
            assertEquals(
                PlaybackSource.Unavailable(NEEDS_CONNECTION_TO_PLAY_TEXT),
                choosePlaybackSource(ServerSource.PLEX, copy, online = false, downloadedOnly = false),
            )
        }
    }

    @Test
    fun `plex books follow downloaded only like a book that is not downloaded`() {
        // Plex books cannot be downloaded (yet), so "Downloaded only" keeps them from streaming.
        assertEquals(
            PlaybackSource.Unavailable(NOT_DOWNLOADED_TEXT),
            choosePlaybackSource(ServerSource.PLEX, LocalCopy.NONE, online = true, downloadedOnly = true),
        )
    }

    @Test
    fun `only a finished download with missing files is marked failed`() {
        assertEquals(true, shouldMarkFailed(LocalCopy.MISSING_FILES))
        assertEquals(false, shouldMarkFailed(LocalCopy.COMPLETE))
        assertEquals(false, shouldMarkFailed(LocalCopy.NONE))
    }
}
