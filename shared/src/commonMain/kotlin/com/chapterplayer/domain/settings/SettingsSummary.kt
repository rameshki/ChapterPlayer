package com.chapterplayer.domain.settings

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.domain.downloads.INTERNAL_VOLUME_ID
import com.chapterplayer.domain.downloads.storageLimitLabel

/** The one-line live summaries on the Settings home rows. Pure text rules. */

private const val SEPARATOR = " · "

fun serversSummary(servers: List<ServerConfig>): String {
    if (servers.isEmpty()) return "No servers yet"
    val lostSignIn = servers.any { it.needsSignIn }
    if (servers.size == 1) {
        val kind = if (servers[0].source == ServerSource.ABS) "Audiobookshelf" else "Plex"
        return "1 $kind$SEPARATOR${if (lostSignIn) "sign in again" else "signed in"}"
    }
    return "${servers.size} servers" + if (lostSignIn) "${SEPARATOR}sign in again" else ""
}

fun appearanceSummary(mode: ThemeMode): String = when (mode) {
    ThemeMode.DARK -> "Dark"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.SYSTEM -> "Follow system"
}

/** [locationLabel] is the chosen download location's name; null while the volumes are not read yet. */
fun downloadsSummary(wifiOnly: Boolean, locationLabel: String?, limitBytes: Long?): String =
    listOf(
        if (wifiOnly) "Wi-Fi only" else "Any network",
        locationLabel ?: "Internal storage",
        storageLimitLabel(limitBytes)
    ).joinToString(SEPARATOR)

/**
 * The name of the place new downloads go. [chosenId] is the saved choice (null is internal storage),
 * [labels] the volumes read so far by id. While they are not read yet an external choice reads as
 * "External storage" rather than wrongly as internal; once read, a missing volume falls back to internal.
 */
fun downloadLocationLabel(chosenId: String?, labels: Map<String, String>): String {
    val id = chosenId ?: INTERNAL_VOLUME_ID
    labels[id]?.let { return it }
    return when {
        id == INTERNAL_VOLUME_ID -> "Internal storage"
        labels.isEmpty() -> "External storage"
        else -> labels[INTERNAL_VOLUME_ID] ?: "Internal storage"
    }
}

fun carSummary(resumeOnConnect: Boolean): String = "Resume on connect: ${if (resumeOnConnect) "on" else "off"}"

fun aboutSummary(versionName: String): String = "Version $versionName"

/** What a screen reader says for a hub row: the title, then the summary parts separated by commas. */
fun rowDescription(title: String, summary: String): String = "$title, ${summary.replace(SEPARATOR, ", ")}"
