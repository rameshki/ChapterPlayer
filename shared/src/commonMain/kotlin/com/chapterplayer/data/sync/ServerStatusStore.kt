package com.chapterplayer.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chapterplayer.domain.plex.PlexCandidate
import com.chapterplayer.domain.plex.PlexConnectionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A connection as stored: the address and its kind's name. */
@Serializable
data class StoredCandidate(val uri: String, val kind: String) {
    fun toCandidate() = PlexCandidate(uri, PlexConnectionKind.entries.firstOrNull { it.name == kind } ?: PlexConnectionKind.SAVED)

    companion object {
        fun of(candidate: PlexCandidate) = StoredCandidate(candidate.uri, candidate.kind.name)
    }
}

/** One failed try of the last probe: the address, its kind and the reason in words (never a token). */
@Serializable
data class StoredProbeFailure(val uri: String, val kind: String, val reason: String)

/**
 * What the app remembers about one server's connection and sync, apart from the server row: for Plex the connections
 * plex.tv listed ([candidates], in rank order), the one in use ([chosenUri]) and the last probe; for every server the
 * last successful sync and the last error. Nothing here is a secret: no token is ever stored in it.
 */
@Serializable
data class ServerStatusRecord(
    val candidates: List<StoredCandidate> = emptyList(),
    val chosenUri: String? = null,
    val lastProbeAtMs: Long? = null,
    val probeFailures: List<StoredProbeFailure> = emptyList(),
    val probeNote: String? = null,
    val lastSuccessAtMs: Long? = null,
    val lastErrorText: String? = null,
    val lastErrorAtMs: Long? = null,
)

/** Per-server connection and sync state, kept across restarts. */
interface ServerStatusStore {
    /** Every server's record, keyed by server id. */
    val all: Flow<Map<String, ServerStatusRecord>>
    suspend fun get(serverId: String): ServerStatusRecord
    suspend fun update(serverId: String, transform: (ServerStatusRecord) -> ServerStatusRecord)
    suspend fun remove(serverId: String)
}

/** Keeps nothing; for code paths (and tests) that have no store. */
object NoServerStatusStore : ServerStatusStore {
    override val all: Flow<Map<String, ServerStatusRecord>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    override suspend fun get(serverId: String) = ServerStatusRecord()
    override suspend fun update(serverId: String, transform: (ServerStatusRecord) -> ServerStatusRecord) {}
    override suspend fun remove(serverId: String) {}
}

private val RecordJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

private const val KEY_PREFIX = "server."

/** One JSON record per server in a Preferences DataStore (key `server.<id>`); a damaged record reads as empty. */
class DataStoreServerStatusStore(private val store: DataStore<Preferences>) : ServerStatusStore {

    private val prefs: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val all: Flow<Map<String, ServerStatusRecord>> = prefs.map { p ->
        p.asMap().entries
            .filter { it.key.name.startsWith(KEY_PREFIX) }
            .associate { (key, value) -> key.name.removePrefix(KEY_PREFIX) to decode(value as? String) }
    }

    override suspend fun get(serverId: String): ServerStatusRecord = decode(prefs.first()[key(serverId)])

    override suspend fun update(serverId: String, transform: (ServerStatusRecord) -> ServerStatusRecord) {
        store.edit { p ->
            p[key(serverId)] = RecordJson.encodeToString(ServerStatusRecord.serializer(), transform(decode(p[key(serverId)])))
        }
    }

    override suspend fun remove(serverId: String) {
        store.edit { it.remove(key(serverId)) }
    }

    private fun key(serverId: String) = stringPreferencesKey(KEY_PREFIX + serverId)

    private fun decode(json: String?): ServerStatusRecord {
        if (json == null) return ServerStatusRecord()
        return try {
            RecordJson.decodeFromString(ServerStatusRecord.serializer(), json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServerStatusRecord()
        }
    }
}
