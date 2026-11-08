package com.chapterplayer.domain.jellyfin

import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinItemDto
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinPublicInfoDto

/** What `/System/Info/Public` says about an address. */
sealed interface JellyfinServerCheck {
    /** A Jellyfin server the app can use; [fullFeatures] (12.0+) means audiobook chapters and narrators. */
    data class Ok(val version: String, val serverId: String, val fullFeatures: Boolean) : JellyfinServerCheck
    data object NotJellyfin : JellyfinServerCheck
    data class TooOld(val version: String) : JellyfinServerCheck
}

private val MINIMUM = listOf(10, 10, 0)
private val FULL_FEATURES = listOf(12, 0, 0)

/**
 * Jellyfin when the product name contains "Jellyfin" (ignoring case) and the server has an `Id`; below 10.10.0 (or with
 * a version that cannot be read) it is too old.
 */
fun checkJellyfinServer(info: JellyfinPublicInfoDto?): JellyfinServerCheck {
    if (info == null) return JellyfinServerCheck.NotJellyfin
    if (info.productName?.contains("Jellyfin", ignoreCase = true) != true) return JellyfinServerCheck.NotJellyfin
    val serverId = info.id?.takeIf { it.isNotBlank() } ?: return JellyfinServerCheck.NotJellyfin
    val version = info.version.orEmpty().trim()
    val parts = versionParts(version) ?: return JellyfinServerCheck.TooOld(version)
    if (compare(parts, MINIMUM) < 0) return JellyfinServerCheck.TooOld(version)
    return JellyfinServerCheck.Ok(version, serverId, fullFeatures = compare(parts, FULL_FEATURES) >= 0)
}

/** "12.1.0-rc2" -> [12, 1, 0]; null when the first part is not a number. Missing parts are 0. */
private fun versionParts(version: String): List<Int>? {
    val numbers = version.split('.').take(3).map { part -> part.takeWhile { it in '0'..'9' }.toIntOrNull() }
    if (numbers.isEmpty() || numbers[0] == null) return null
    return (numbers.map { it ?: 0 } + listOf(0, 0, 0)).take(3)
}

private fun compare(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until 3) if (a[i] != b[i]) return a[i].compareTo(b[i])
    return 0
}

/** The user's views that are Books libraries (`CollectionType` "books", ignoring case), as libraries of the row [serverId]. */
fun jellyfinBookViews(views: List<JellyfinItemDto>, serverId: String): List<LibraryInfo> =
    views.filter { it.collectionType.equals("books", ignoreCase = true) && !it.id.isNullOrBlank() }
        .map { LibraryInfo(serverId, it.id!!, it.name?.takeIf { n -> n.isNotBlank() } ?: "Books") }
