package com.chapterplayer.domain.jellyfin

import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinItemDto
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinPublicInfoDto
import kotlin.test.Test
import kotlin.test.assertEquals

class JellyfinServerCheckTest {

    private fun info(version: String?, product: String? = "Jellyfin Server", id: String? = "36b7674f7149d16771400fae9b9eb1f9") =
        JellyfinPublicInfoDto(productName = product, version = version, id = id, serverName = "Example")

    @Test
    fun `a 12 server has every feature`() {
        assertEquals(JellyfinServerCheck.Ok("12.4.0", "36b7674f7149d16771400fae9b9eb1f9", fullFeatures = true), checkJellyfinServer(info("12.4.0")))
    }

    @Test
    fun `a 10-10 server works without chapters and narrators`() {
        assertEquals(JellyfinServerCheck.Ok("10.10.7", "36b7674f7149d16771400fae9b9eb1f9", fullFeatures = false), checkJellyfinServer(info("10.10.7")))
        assertEquals(false, (checkJellyfinServer(info("10.11.3")) as JellyfinServerCheck.Ok).fullFeatures)
        assertEquals(true, (checkJellyfinServer(info("12.0.0")) as JellyfinServerCheck.Ok).fullFeatures)
    }

    @Test
    fun `below 10-10 is too old`() {
        assertEquals(JellyfinServerCheck.TooOld("10.9.11"), checkJellyfinServer(info("10.9.11")))
        assertEquals(JellyfinServerCheck.TooOld("9.0.0"), checkJellyfinServer(info("9.0.0")))
        assertEquals(JellyfinServerCheck.TooOld(""), checkJellyfinServer(info("")))
    }

    @Test
    fun `a pre-release suffix is read by its numbers`() {
        assertEquals(true, (checkJellyfinServer(info("12.1.0-rc2")) as JellyfinServerCheck.Ok).fullFeatures)
    }

    @Test
    fun `another product or no answer is not jellyfin`() {
        assertEquals(JellyfinServerCheck.NotJellyfin, checkJellyfinServer(info("4.9.0", product = "Emby Server")))
        assertEquals(JellyfinServerCheck.NotJellyfin, checkJellyfinServer(info("12.4.0", product = null)))
        assertEquals(JellyfinServerCheck.NotJellyfin, checkJellyfinServer(info("12.4.0", id = "")))
        assertEquals(JellyfinServerCheck.NotJellyfin, checkJellyfinServer(null))
    }

    @Test
    fun `the product name is matched ignoring case`() {
        assertEquals(true, checkJellyfinServer(info("12.4.0", product = "JELLYFIN server")) is JellyfinServerCheck.Ok)
    }

    @Test
    fun `only books views are kept`() {
        val views = listOf(
            JellyfinItemDto(id = "v1", name = "Audiobooks", collectionType = "books"),
            JellyfinItemDto(id = "v2", name = "Music", collectionType = "music"),
            JellyfinItemDto(id = "v3", name = "Films", collectionType = "movies"),
            JellyfinItemDto(id = "v4", name = "More Books", collectionType = "Books"),
            JellyfinItemDto(id = null, name = "Broken", collectionType = "books"),
            JellyfinItemDto(id = "v6", name = null, collectionType = "books"),
        )
        assertEquals(
            listOf(LibraryInfo("row1", "v1", "Audiobooks"), LibraryInfo("row1", "v4", "More Books"), LibraryInfo("row1", "v6", "Books")),
            jellyfinBookViews(views, "row1"),
        )
    }
}
