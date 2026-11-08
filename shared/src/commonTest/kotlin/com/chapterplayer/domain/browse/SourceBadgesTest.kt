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

    @Test
    fun `any two kinds of server show the badge`() {
        assertTrue(showSourceBadges(listOf(ServerSource.ABS, ServerSource.JELLYFIN)))
        assertTrue(showSourceBadges(listOf(ServerSource.PLEX, ServerSource.JELLYFIN)))
        assertTrue(showSourceBadges(listOf(ServerSource.ABS, ServerSource.PLEX, ServerSource.JELLYFIN)))
    }

    @Test
    fun `Jellyfin servers alone show no badge`() {
        assertFalse(showSourceBadges(listOf(ServerSource.JELLYFIN)))
        assertFalse(showSourceBadges(listOf(ServerSource.JELLYFIN, ServerSource.JELLYFIN)))
    }
}
