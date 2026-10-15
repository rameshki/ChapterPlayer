package com.chapterplayer.data.security

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chapterplayer.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialStoreTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    // One fixed key per cipher. (A lambda that builds a new key on every call could never decrypt.)
    private fun cipher(key: SecretKey = newKey()) = AesGcmTokenCipher { key }

    @Test
    fun `a saved token can be read back`() = runTest {
        val store = DataStoreCredentialStore(InMemoryPreferencesDataStore(), cipher())

        store.put("srv-1", "tok-one")

        assertEquals("tok-one", store.get("srv-1"))
    }

    @Test
    fun `an unknown server has no token`() = runTest {
        val store = DataStoreCredentialStore(InMemoryPreferencesDataStore(), cipher())
        assertNull(store.get("nope"))
    }

    @Test
    fun `two servers with the same token keep separate entries`() = runTest {
        val store = DataStoreCredentialStore(InMemoryPreferencesDataStore(), cipher())

        store.put("a", "same")
        store.put("b", "same")
        store.remove("a")

        assertNull(store.get("a"))
        assertEquals("same", store.get("b"))
    }

    @Test
    fun `replacing a token keeps only the new one`() = runTest {
        val store = DataStoreCredentialStore(InMemoryPreferencesDataStore(), cipher())

        store.put("a", "old")
        store.put("a", "new")

        assertEquals("new", store.get("a"))
    }

    @Test
    fun `the stored value does not contain the token`() = runTest {
        val raw = InMemoryPreferencesDataStore()
        val store = DataStoreCredentialStore(raw, cipher())

        store.put("a", "plain-text-token-1234567890")

        val values = raw.data.first().asMap().values.map { it.toString() }
        assertEquals(1, values.size)
        assertTrue(values.none { it.contains("plain-text-token") })
    }

    @Test
    fun `a token that cannot be decrypted reads as missing`() = runTest {
        val raw = InMemoryPreferencesDataStore()
        DataStoreCredentialStore(raw, cipher()).put("a", "secret")

        // Same store, but the Keystore key has been lost and replaced.
        val afterKeyLoss = DataStoreCredentialStore(raw, cipher())

        assertNull(afterKeyLoss.get("a"))
    }

    @Test
    fun `a damaged stored value reads as missing`() = runTest {
        val raw = InMemoryPreferencesDataStore()
        raw.edit { it[stringPreferencesKey("token_a")] = "garbage!!" }

        assertNull(DataStoreCredentialStore(raw, cipher()).get("a"))
        assertEquals(1, raw.data.first().asMap().size)
    }
}
