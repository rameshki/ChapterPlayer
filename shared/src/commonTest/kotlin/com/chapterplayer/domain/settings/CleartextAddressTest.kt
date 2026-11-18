package com.chapterplayer.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleartextAddressTest {
    private fun kind(address: String) = addressExposure(address)

    @Test
    fun `https and things that are not an address have nothing to warn about`() {
        assertEquals(AddressExposure.ENCRYPTED, kind("https://books.example.org"))
        assertEquals(AddressExposure.ENCRYPTED, kind("HTTPS://books.example.org:13378/abs"))
        assertEquals(AddressExposure.ENCRYPTED, kind(""))
        assertEquals(AddressExposure.ENCRYPTED, kind("   "))
        assertEquals(AddressExposure.ENCRYPTED, kind("ftp://books.example.org"))
        assertFalse(isCleartextAddress("https://books.example.org"))
        assertFalse(needsCleartextConfirmation("not an address"))
    }

    @Test
    fun `http inside a home network is allowed without a warning`() {
        for (address in listOf(
            "http://192.168.1.10:8080", "http://10.0.0.5", "http://172.16.0.1", "http://172.31.255.255:13378", "http://127.0.0.1:8096",
            "http://169.254.10.20", "http://100.64.0.1", "http://100.127.255.254",
            "http://localhost:13378", "http://nas", "http://audiobookshelf:13378", "http://books.local", "http://books.lan:8096",
            "http://server.home.arpa", "http://nas.internal", "http://machine.tail1234.ts.net:8080",
            "http://[::1]:8080", "http://[fd12:3456:789a::1]:8080", "http://[fe80::1]",
        )) {
            assertEquals(AddressExposure.PRIVATE_NETWORK, kind(address), address)
            assertTrue(isCleartextAddress(address), address)
            assertFalse(needsCleartextConfirmation(address), address)
        }
    }

    @Test
    fun `an address with no scheme counts as http as it does when connecting`() {
        assertEquals(AddressExposure.PRIVATE_NETWORK, kind("192.168.1.10:8080"))
        assertEquals(AddressExposure.PUBLIC_CLEARTEXT, kind("books.example.org"))
    }

    @Test
    fun `http to a public host needs a confirmation`() {
        for (address in listOf(
            "http://books.example.org", "http://books.example.org:13378/abs", "http://8.8.8.8", "http://203.0.113.9:8080",
            "http://172.15.0.1", "http://172.32.0.1", "http://100.63.0.1", "http://100.128.0.1", "http://192.169.1.1",
            "http://[2001:db8::1]:8080", "http://my.server.com",
        )) {
            assertEquals(AddressExposure.PUBLIC_CLEARTEXT, kind(address), address)
            assertTrue(needsCleartextConfirmation(address), address)
        }
    }

    @Test
    fun `a name that only ends like a private suffix is still public`() {
        assertEquals(AddressExposure.PUBLIC_CLEARTEXT, kind("http://example.local.evil.com"))
        assertEquals(AddressExposure.PUBLIC_CLEARTEXT, kind("http://notlocal.com"))
        assertEquals(AddressExposure.PUBLIC_CLEARTEXT, kind("http://192.168.1.10.example.org"))
    }

    @Test
    fun `the path does not change the host`() {
        assertEquals(AddressExposure.PUBLIC_CLEARTEXT, kind("http://books.example.org/192.168.1.10"))
        assertEquals(AddressExposure.PRIVATE_NETWORK, kind("http://192.168.1.10/books.example.org"))
    }

    @Test
    fun `use https swaps only the scheme`() {
        assertEquals("https://books.example.org:13378/abs", withHttps("http://books.example.org:13378/abs"))
        assertEquals("https://books.example.org", withHttps("books.example.org"))
        assertEquals("https://books.example.org", withHttps("https://books.example.org"))
        assertEquals("not an address", withHttps("not an address"))
    }
}
