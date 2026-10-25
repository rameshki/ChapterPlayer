package com.chapterplayer.domain.settings

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.domain.downloads.GIGABYTE
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsSummaryTest {

    private fun server(source: ServerSource, token: String = "t") =
        ServerConfig(id = "s-$source-$token", source = source, name = "Home", baseUrl = "http://host:1", token = token)

    @Test
    fun `no servers reads as none yet`() {
        assertEquals("No servers yet", serversSummary(emptyList()))
    }

    @Test
    fun `one server names its kind and sign-in`() {
        assertEquals("1 Audiobookshelf · signed in", serversSummary(listOf(server(ServerSource.ABS))))
        assertEquals("1 Plex · signed in", serversSummary(listOf(server(ServerSource.PLEX))))
        assertEquals("1 Audiobookshelf · sign in again", serversSummary(listOf(server(ServerSource.ABS, token = ""))))
    }

    @Test
    fun `several servers are counted and a lost sign-in is flagged`() {
        val two = listOf(server(ServerSource.ABS), server(ServerSource.PLEX))
        assertEquals("2 servers", serversSummary(two))
        assertEquals("3 servers · sign in again", serversSummary(two + server(ServerSource.ABS, token = "")))
    }

    @Test
    fun `appearance names the theme`() {
        assertEquals("Dark", appearanceSummary(ThemeMode.DARK))
        assertEquals("Light", appearanceSummary(ThemeMode.LIGHT))
        assertEquals("Follow system", appearanceSummary(ThemeMode.SYSTEM))
    }

    @Test
    fun `downloads summary joins network, location and limit`() {
        assertEquals("Wi-Fi only · Internal storage · No limit", downloadsSummary(true, "Internal storage", null))
        assertEquals("Any network · SD card · 5 GB", downloadsSummary(false, "SD card", 5 * GIGABYTE))
        assertEquals("Wi-Fi only · Internal storage · No limit", downloadsSummary(true, null, null))
    }

    @Test
    fun `car and about summaries`() {
        assertEquals("Resume on connect: on", carSummary(true))
        assertEquals("Resume on connect: off", carSummary(false))
        assertEquals("Version 0.1.0", aboutSummary("0.1.0"))
    }

    @Test
    fun `a row reads as one label`() {
        assertEquals(
            "Downloads and offline, Wi-Fi only, Internal storage, No limit",
            rowDescription("Downloads and offline", "Wi-Fi only · Internal storage · No limit")
        )
        assertEquals("Servers, 2 servers", rowDescription("Servers", "2 servers"))
    }
}
