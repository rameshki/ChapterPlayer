package com.chapterplayer.data.playback

import androidx.media3.common.MediaMetadata
import androidx.media3.session.SessionError
import com.chapterplayer.domain.car.CarFailure
import com.chapterplayer.domain.car.CarItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CarMediaMappingTest {

    private fun item(id: String, browsable: Boolean = false, playable: Boolean = true, dimmed: Boolean = false) =
        CarItem(id, "Title", "Jane Doe", "cover/x", browsable, playable, dimmed)

    @Test
    fun `a book is a playable audio book with its title and subtitle`() {
        assertEquals(
            CarMediaSpec("abs_1", "Title", "Jane Doe", browsable = false, playable = true, mediaType = MediaMetadata.MEDIA_TYPE_AUDIO_BOOK),
            carMediaSpec(item("abs_1")),
        )
    }

    @Test
    fun `a folder is browsable and not playable`() {
        val spec = carMediaSpec(item("car:series", browsable = true, playable = false))
        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, spec.mediaType)
        assertEquals(true, spec.browsable)
        assertEquals(false, spec.playable)
    }

    @Test
    fun `a chapter is an audio book chapter`() {
        assertEquals(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER, carMediaSpec(item("car:chapter:2")).mediaType)
    }

    @Test
    fun `a note is neither browsable nor playable`() {
        val spec = carMediaSpec(item("car:note", playable = false, dimmed = true))
        assertEquals(MediaMetadata.MEDIA_TYPE_MIXED, spec.mediaType)
        assertEquals(false, spec.playable)
    }

    @Test
    fun `a dimmed book cannot be played`() {
        assertEquals(false, carMediaSpec(item("abs_1", playable = true, dimmed = true)).playable)
        assertEquals(false, carMediaSpec(item("plex_1", playable = false, dimmed = true)).playable)
    }

    @Test
    fun `a book row points at the app's cover provider, never the server`() {
        val withCover = CarItem("abs_1", "Title", "Jane Doe", "https://abs.test/api/items/1/cover", false, true, false)
        assertEquals("content://com.chapterplayer.covers/cover/abs_1", carMediaSpec(withCover, "com.chapterplayer.covers").artworkUri)
        val plex = CarItem("plex_7", "Title", "", "https://plex.test:32400/library/metadata/7/thumb/1", false, true, false)
        assertEquals("content://com.chapterplayer.covers/cover/plex_7", carMediaSpec(plex, "com.chapterplayer.covers").artworkUri)
    }

    @Test
    fun `folders, notes and books without a cover have no artwork`() {
        val authority = "com.chapterplayer.covers"
        assertNull(carMediaSpec(item("car:series", browsable = true, playable = false), authority).artworkUri)
        assertNull(carMediaSpec(CarItem("car:note", "No downloaded books yet", "", null, false, false, true), authority).artworkUri)
        assertNull(carMediaSpec(CarItem("abs_1", "Title", "", null, false, true, false), authority).artworkUri)
        assertNull(carMediaSpec(item("abs_1")).artworkUri)
    }

    @Test
    fun `failures map to Media3 error codes`() {
        assertEquals(SessionError.ERROR_PERMISSION_DENIED, carErrorCode(CarFailure.REFUSED))
        assertEquals(SessionError.ERROR_BAD_VALUE, carErrorCode(CarFailure.UNKNOWN_BOOK))
        assertEquals(SessionError.ERROR_BAD_VALUE, carErrorCode(CarFailure.NO_MATCH))
        assertEquals(SessionError.ERROR_IO, carErrorCode(CarFailure.OFFLINE))
        assertEquals(SessionError.ERROR_INVALID_STATE, carErrorCode(CarFailure.NOTHING_TO_RESUME))
    }
}
