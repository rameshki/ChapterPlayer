package com.chapterplayer.domain.settings

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.SourceChoice
import com.chapterplayer.domain.browse.resolvedAgainst
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
}

private fun lineLabel(choice: SourceChoice, servers: List<ServerConfig>): String = when (choice) {
    SourceChoice.Ask -> "Ask every time"
    is SourceChoice.OfType -> choice.source.longName()
    is SourceChoice.OfServer -> servers.firstOrNull { it.id == choice.serverId }
        ?.let { "${it.source.longName()}: ${it.name}" } ?: "Ask every time"
}

private fun lineDetail(choice: SourceChoice, servers: List<ServerConfig>, purpose: SourcePurpose): String {
    val name = when (choice) {
        SourceChoice.Ask -> null
        is SourceChoice.OfType -> choice.source.longName()
        is SourceChoice.OfServer -> servers.firstOrNull { it.id == choice.serverId }?.name ?: "that server"
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
 * type, with a line per server under a type that has two or more servers (or under the type of the chosen server, so
 * a saved choice always has its line). A choice of a removed server reads as Ask and says so.
 */
fun sourceChoiceCard(stored: SourceChoice, servers: List<ServerConfig>, purpose: SourcePurpose): SourceChoiceCard {
    val effective = stored.resolvedAgainst(servers.map { it.id })
    val lines = mutableListOf<SourceChoice>(SourceChoice.Ask)
    for (type in ServerSource.entries) {
        lines += SourceChoice.OfType(type)
        val ofType = servers.filter { it.source == type }
        val chosenHere = effective is SourceChoice.OfServer && ofType.any { it.id == effective.serverId }
        if (ofType.size >= 2 || chosenHere) ofType.forEach { lines += SourceChoice.OfServer(it.id) }
    }
    return SourceChoiceCard(
        title = if (purpose == SourcePurpose.PLAY) "Play from (when not downloaded)" else "Download from",
        label = lineLabel(effective, servers),
        detail = lineDetail(effective, servers, purpose),
        note = if (purpose == SourcePurpose.PLAY) PLAY_DOWNLOADED_FIRST_TEXT else null,
        removedNote = if (effective != stored) CHOSEN_SERVER_REMOVED_TEXT else null,
        lines = lines.map { choice ->
            SourceChoiceLine(
                choice = choice,
                label = lineLabel(choice, servers),
                detail = lineDetail(choice, servers, purpose),
                indented = choice is SourceChoice.OfServer,
                selected = choice == effective,
            )
        },
    )
}

private fun shortName(choice: SourceChoice, servers: List<ServerConfig>): String = when (val c = choice.resolvedAgainst(servers.map { it.id })) {
    SourceChoice.Ask -> "ask"
    is SourceChoice.OfType -> c.source.shortLabel()
    is SourceChoice.OfServer -> servers.first { it.id == c.serverId }.name
}

/** "Download: ask · Play: Plex" for the hub row; null with fewer than two servers (no book can be on several then). */
fun sourceChoicesSummary(download: SourceChoice, play: SourceChoice, servers: List<ServerConfig>): String? {
    if (servers.size < 2) return null
    return "Download: ${shortName(download, servers)} · Play: ${shortName(play, servers)}"
}
