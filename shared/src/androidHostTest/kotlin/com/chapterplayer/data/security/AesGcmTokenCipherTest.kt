package com.chapterplayer.data.security

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AesGcmTokenCipherTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = newKey()
    private val cipher = AesGcmTokenCipher { key }

    @Test
    fun `text comes back unchanged`() {
        val token = "abc.DEF-123_xyz"
        assertEquals(token, cipher.decrypt(cipher.encrypt(token)))
    }

    @Test
    fun `an empty token and unicode survive`() {
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
        assertEquals("tökén-日本語-🔑", cipher.decrypt(cipher.encrypt("tökén-日本語-🔑")))
    }

    @Test
    fun `a very long token survives`() {
        val long = "x".repeat(20_000)
        assertEquals(long, cipher.decrypt(cipher.encrypt(long)))
    }

    @Test
    fun `the stored text does not contain the token`() {
        val encoded = cipher.encrypt("super-secret-token-value")
        assertTrue("super-secret" !in encoded)
    }

    @Test
    fun `encrypting the same token twice gives different text`() {
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun `a different key cannot decrypt`() {
        val encoded = cipher.encrypt("secret")
        val other = AesGcmTokenCipher { newKey() }
        assertFailsWith<TokenCipherException> { other.decrypt(encoded) }
    }

    @Test
    fun `tampered or damaged text is rejected`() {
        val encoded = cipher.encrypt("secret")
        val tampered = encoded.dropLast(4) + if (encoded.endsWith("AAAA")) "BBBB" else "AAAA"
        assertFailsWith<TokenCipherException> { cipher.decrypt(tampered) }
        assertFailsWith<TokenCipherException> { cipher.decrypt("not base64 !!!") }
        assertFailsWith<TokenCipherException> { cipher.decrypt("") }
        assertFailsWith<TokenCipherException> { cipher.decrypt("AAAA") }
    }
}
