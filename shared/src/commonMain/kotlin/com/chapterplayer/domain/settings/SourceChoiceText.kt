package com.chapterplayer.domain.settings

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.SourceChoice
import com.chapterplayer.domain.browse.resolvedAgainst
import com.chapterplayer.domain.browse.serverDisplayNames
import com.chapterplayer.domain.browse.shortLabel

/** The Settings wording of the two "Books on several servers" choices (Downloads and offline page and the hub). */

/** Which of the two choices a card is about. */
enum class SourcePurpose { DOWNLOAD, PLAY }

/** One radio line of a choice's dialog. */
data class SourceChoiceLine(
    val choice: SourceChoice,
    val label: String,
    val detail: String,
    /** A line for one server, shown under its type. */
    val indented: Boolean,
    val selected: Boolean,
)

/** A choice's card and dialog. */
data class SourceChoiceCard(
    val title: String,
    /** The choice in effect ("Ask every time", "Plex", "Audiobookshelf: Home"). */
    val label: String,
    val detail: String,
    /** A fixed line under the card (Play: a downloaded copy always plays first); null for none. */
    val note: String?,
    /** Set when the saved choice named a server that is gone. */
    val removedNote: String?,
    val lines: List<SourceChoiceLine>,
)

const val PLAY_DOWNLOADED_FIRST_TEXT = "A downloaded copy always plays first."
const val CHOSEN_SERVER_REMOVED_TEXT = "The server chosen before was removed, so it asks."

private fun ServerSource.longName(): String = when (this) {
    ServerSource.ABS -> "Audiobookshelf"
    ServerSource.PLEX -> "Plex"
    ServerSource.JELLYFIN -> "Jellyfin"
}

private fun lineLabel(choice: SourceChoice, servers: List<ServerConfig>, names: Map<String, String>): String = when (choice) {
    SourceChoice.Ask -> "Ask every time"
    is SourceChoice.OfType -> choice.source.longName()
    is SourceChoice.OfServer -> servers.firstOrNull { it.id == choice.serverId }
        ?.let { "${it.source.longName()}: ${names.getValue(it.id)}" } ?: "Ask every time"
}

private fun lineDetail(choice: SourceChoice, names: Map<String, String>, purpose: SourcePurpose): String {
    val name = when (choice) {
        SourceChoice.Ask -> null
        is SourceChoice.OfType -> choice.source.longName()
        is SourceChoice.OfServer -> names[choice.serverId] ?: "that server"
    }
    return when (purpose) {
        SourcePurpose.DOWNLOAD ->
            if (name == null) "Download asks which copy to fetch." else "Downloads from $name without asking; asks when it cannot."
        SourcePurpose.PLAY ->
            if (name == null) "Play asks which copy to stream." else "Streams from $name without asking; asks when it cannot play."
    }
}

/**
 * The card for [stored] (the saved choice) with the [servers] there are now. The lines: Ask every time, then each
 * type that has a server (or is the saved choice), with a line per server under a type that has two or more servers
 * (or under the type of the chosen server, so a saved choice always has its line). Servers go by
 * [serverDisplayNames]. A choice of a removed server reads as Ask and says so.
 */
fun sourceChoiceCard(stored: SourceChoice, servers: List<ServerConfig>, purpose: SourcePurpose): SourceChoiceCard {
    val effective = stored.resolvedAgainst(servers.map { it.id })
    val names = serverDisplayNames(servers)
    val lines = mutableListOf<SourceChoice>(SourceChoice.Ask)
    for (type in ServerSource.entries) {
        val ofType = servers.filter { it.source == type }
        if (ofType.isEmpty() && effective != SourceChoice.OfType(type)) continue
        lines += SourceChoice.OfType(type)
        val chosenHere = effective is SourceChoice.OfServer && ofType.any { it.id == effective.serverId }
        if (ofType.size >= 2 || chosenHere) ofType.forEach { lines += SourceChoice.OfServer(it.id) }
    }
    return SourceChoiceCard(
        title = if (purpose == SourcePurpose.PLAY) "Play from (when not downloaded)" else "Download from",
        label = lineLabel(effective, servers, names),
        detail = lineDetail(effective, names, purpose),
        note = if (purpose == SourcePurpose.PLAY) PLAY_DOWNLOADED_FIRST_TEXT else null,
        removedNote = if (effective != stored) CHOSEN_SERVER_REMOVED_TEXT else null,
        lines = lines.map { choice ->
            SourceChoiceLine(
                choice = choice,
                label = lineLabel(choice, servers, names),
                detail = lineDetail(choice, names, purpose),
                indented = choice is SourceChoice.OfServer,
                selected = choice == effective,
            )
        },
    )
}

/** Whether the several-servers choices apply at all: a book can only be on several servers with two or more. */
fun sourceChoicesApply(servers: List<ServerConfig>): Boolean = servers.size >= 2

private fun shortName(choice: SourceChoice, names: Map<String, String>): String? = when (choice) {
    SourceChoice.Ask -> null
    is SourceChoice.OfType -> choice.source.shortLabel()
    is SourceChoice.OfServer -> names[choice.serverId]
}

/**
 * The hub row's part for the choices, one short phrase with no separators of its own: "Download and play from Plex",
 * "Download from Work ABS, play asks"; null when both ask (the default) or the choices do not apply. Without a
 * downloader ([downloads] false) only the play choice is named.
 */
fun sourceChoicesSummary(download: SourceChoice, play: SourceChoice, servers: List<ServerConfig>, downloads: Boolean = true): String? {
    if (!sourceChoicesApply(servers)) return null
    val ids = servers.map { it.id }
    val names = serverDisplayNames(servers)
    val d = if (downloads) shortName(download.resolvedAgainst(ids), names) else null
    val p = shortName(play.resolvedAgainst(ids), names)
    return when {
        d == null && p == null -> null
        !downloads -> "Play from $p"
        d == p -> "Download and play from $d"
        d == null -> "Play from $p, download asks"
        p == null -> "Download from $d, play asks"
        else -> "Download from $d, play from $p"
    }
}
