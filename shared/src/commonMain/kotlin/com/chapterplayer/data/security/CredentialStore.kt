package com.chapterplayer.data.security

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Holds the secret that signs the app in to each server, apart from the server list itself. */
interface CredentialStore {
    /** The token for [serverId], or null if none is stored or it can no longer be decrypted. */
    suspend fun get(serverId: String): String?
    suspend fun put(serverId: String, token: String)
    suspend fun remove(serverId: String)
}

class DataStoreCredentialStore(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : CredentialStore {

    private fun key(serverId: String) = stringPreferencesKey("token_$serverId")

    override suspend fun get(serverId: String): String? {
        val encoded = try {
            store.data.first()[key(serverId)]
        } catch (_: IOException) {
            null // unreadable file: treat as no token, the user signs in again
        } ?: return null
        return try {
            withContext(ioDispatcher) { cipher.decrypt(encoded) }
        } catch (_: TokenCipherException) {
            null // the key was lost or the value is damaged: the user signs in again
        }
    }

    override suspend fun put(serverId: String, token: String) {
        val encoded = withContext(ioDispatcher) { cipher.encrypt(token) }
        store.edit { it[key(serverId)] = encoded }
    }

    override suspend fun remove(serverId: String) {
        store.edit { it.remove(key(serverId)) }
    }
}
