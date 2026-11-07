package com.chapterplayer.domain.browse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** The version written with the stored filters; a reader reads every field it knows whatever the version. */
const val BROWSE_QUERY_FORMAT_VERSION = 1

/**
 * The filters and sort as stored between launches (`AppSettings.browseFilters`): a small JSON object with a version.
 * The search text is not part of [BrowseQuery], so it is never stored.
 */
fun encodeBrowseQuery(query: BrowseQuery): String = buildJsonObject {
    put("v", BROWSE_QUERY_FORMAT_VERSION)
    put("progress", query.progress.name)
    put("download", query.download.name)
    put("sort", query.sort.name)
    putJsonArray("servers") { query.serverIds.sorted().forEach { add(it) } }
    put("favouritesOnly", query.favouritesOnly)
}.toString()

/**
 * The stored filters, field by field: nothing stored, garbage or another shape reads as the default query, and a field
 * that is missing, of the wrong type or names a value this version does not know (a removed sort, say) takes its
 * default while the others are kept. Server ids are taken as stored; the store drops the ones that no longer exist.
 */
fun decodeBrowseQuery(stored: String?): BrowseQuery {
    if (stored.isNullOrBlank()) return BrowseQuery()
    val root = try {
        Json.parseToJsonElement(stored) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return BrowseQuery()
    val defaults = BrowseQuery()
    return BrowseQuery(
        progress = enumOf(root["progress"], ProgressFilter.entries) ?: defaults.progress,
        download = enumOf(root["download"], DownloadFilter.entries) ?: defaults.download,
        sort = enumOf(root["sort"], BrowseSort.entries) ?: defaults.sort,
        serverIds = (root["servers"] as? JsonArray)
            ?.mapNotNullTo(mutableSetOf()) { element -> stringOf(element)?.takeIf { it.isNotBlank() } }
            ?: defaults.serverIds,
        favouritesOnly = (root["favouritesOnly"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: defaults.favouritesOnly,
    )
}

private fun stringOf(element: JsonElement?): String? =
    (element as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun <E : Enum<E>> enumOf(element: JsonElement?, entries: List<E>): E? {
    val name = stringOf(element) ?: return null
    return entries.firstOrNull { it.name == name }
}
