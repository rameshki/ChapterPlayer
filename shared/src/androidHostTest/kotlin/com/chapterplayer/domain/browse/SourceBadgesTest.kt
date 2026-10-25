package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceBadgesTest {
    @Test
    fun `no servers or one kind of server shows no badge`() {
        assertFalse(showSourceBadges(emptyList()))
        assertFalse(showSourceBadges(listOf(ServerSource.ABS)))
        assertFalse(showSourceBadges(listOf(ServerSource.ABS, ServerSource.ABS)))
        assertFalse(showSourceBadges(listOf(ServerSource.PLEX)))
    }

    @Test
    fun `an Audiobookshelf and a Plex server together show the badge`() {
        assertTrue(showSourceBadges(listOf(ServerSource.ABS, ServerSource.PLEX)))
        assertTrue(showSourceBadges(listOf(ServerSource.PLEX, ServerSource.ABS, ServerSource.ABS)))
    }
}
