package com.chapterplayer.data.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerAddressTest {
    @Test
    fun `cleans up typed addresses`() {
        val table = mapOf(
            "abs.local:13378" to "http://abs.local:13378",
            "  HTTP://Abs.Example.com/  " to "http://abs.example.com",
            "https://host/audiobooks/" to "https://host/audiobooks",
            "http://host:13378/?x=1#f" to "http://host:13378",
            "https://Host/Audio/Books//" to "https://host/Audio/Books",
        )
        for ((input, expected) in table) assertEquals(expected, normalizeServerAddress(input), input)
    }

    @Test
    fun `rejects what is not an address`() {
        for (input in listOf("", "   ", "http://", "not a host", "ftp://host", "http://host:99999", "http://host:abc")) {
            assertNull(normalizeServerAddress(input), input)
        }
    }
}
