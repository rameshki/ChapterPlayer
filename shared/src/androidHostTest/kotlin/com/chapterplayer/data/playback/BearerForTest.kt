package com.chapterplayer.data.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BearerForTest {

    private val a = ServerBearer("http://abs.example:13378", "tokenA")
    private val b = ServerBearer("https://books.example.org/abs", "tokenB")

    @Test
    fun `the server whose base address the url starts with gives its token`() {
        assertEquals("tokenA", bearerFor("http://abs.example:13378/api/items/1/file/2", listOf(a, b)))
        assertEquals("tokenB", bearerFor("https://books.example.org/abs/api/items/1/file/2", listOf(a, b)))
    }

    @Test
    fun `no matching server gives null`() {
        assertNull(bearerFor("http://other.example/api/items/1", listOf(a, b)))
        assertNull(bearerFor("http://abs.example:13378/api", emptyList()))
    }

    @Test
    fun `the longest matching base wins when one base is a prefix of another`() {
        val root = ServerBearer("http://host.example", "root")
        val sub = ServerBearer("http://host.example/abs", "sub")
        assertEquals("sub", bearerFor("http://host.example/abs/api/x", listOf(root, sub)))
        assertEquals("sub", bearerFor("http://host.example/abs/api/x", listOf(sub, root)))
        assertEquals("root", bearerFor("http://host.example/other/api/x", listOf(sub, root)))
    }

    @Test
    fun `a base that is only a text prefix of a path segment does not match`() {
        val sub = ServerBearer("http://host.example/abs", "sub")
        assertNull(bearerFor("http://host.example/absolute/api/x", listOf(sub)))
    }

    @Test
    fun `the host is compared without regard to case`() {
        assertEquals("tokenA", bearerFor("http://ABS.Example:13378/api/items/1", listOf(a)))
        assertEquals("tokenA", bearerFor("HTTP://abs.example:13378/api/items/1", listOf(a)))
    }

    @Test
    fun `trailing slashes on the base are tolerated`() {
        val slashed = ServerBearer("http://abs.example:13378/", "tokenA")
        assertEquals("tokenA", bearerFor("http://abs.example:13378/api/items/1", listOf(slashed)))
        assertEquals("tokenA", bearerFor("http://abs.example:13378", listOf(slashed)))
        assertEquals("tokenA", bearerFor("http://abs.example:13378/", listOf(a)))
    }

    @Test
    fun `a different port is a different server`() {
        assertNull(bearerFor("http://abs.example:8080/api/items/1", listOf(a)))
        assertNull(bearerFor("http://abs.example/api/items/1", listOf(a)))
        val other = ServerBearer("http://abs.example:8080", "tokenC")
        assertEquals("tokenC", bearerFor("http://abs.example:8080/api/items/1", listOf(a, other)))
    }

    @Test
    fun `a server without a token is skipped`() {
        val none = ServerBearer("http://abs.example:13378", "")
        assertNull(bearerFor("http://abs.example:13378/api/items/1", listOf(none)))
    }

    @Test
    fun `a host that merely starts with the base host does not match`() {
        assertNull(bearerFor("http://abs.example:13378.evil.test/api", listOf(a)))
        assertNull(bearerFor("http://abs.example:133789/api", listOf(a)))
    }

    @Test
    fun `safe error text never carries details`() {
        assertEquals("Sign in again in Settings", safePlaybackError(errorCode = 2004, httpStatus = 401, signInNeeded = false))
        assertEquals("Sign in again in Settings", safePlaybackError(errorCode = 0, httpStatus = null, signInNeeded = true))
        assertEquals("The audio was not found on the server", safePlaybackError(2004, 404, false))
        assertEquals("Cannot reach the server", safePlaybackError(2001, null, false))
        assertEquals("Cannot reach the server", safePlaybackError(2002, null, false))
        assertEquals("Playback failed", safePlaybackError(1000, null, false))
    }
}
