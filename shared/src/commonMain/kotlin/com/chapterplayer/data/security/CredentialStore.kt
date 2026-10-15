package com.chapterplayer.data.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** Holds the secret that signs the app in to each server, apart from the server list itself. */
interface CredentialStore {
    /** The token for [serverId], or null if none is stored or it can no longer be decrypted. */
    suspend fun get(serverId: String): String?
    suspend fun put(serverId: String, token: String)
    suspend fun remove(serverId: String)
}

class DataStoreCredentialStore(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher
) : CredentialStore {

    private fun key(serverId: String) = stringPreferencesKey("token_$serverId")

    override suspend fun get(serverId: String): String? {
        val encoded = store.data.first()[key(serverId)] ?: return null
        return try {
            cipher.decrypt(encoded)
        } catch (_: TokenCipherException) {
            null // the key was lost or the value is damaged: the user signs in again
        }
    }

    override suspend fun put(serverId: String, token: String) {
        val encoded = cipher.encrypt(token)
        store.edit { it[key(serverId)] = encoded }
    }

    override suspend fun remove(serverId: String) {
        store.edit { it.remove(key(serverId)) }
    }
}
