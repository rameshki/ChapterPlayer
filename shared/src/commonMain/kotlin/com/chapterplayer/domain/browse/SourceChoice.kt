package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerSource

/*
 * Which copy of a book on several servers is used, set separately for downloading and for playing (Settings,
 * Downloads and offline, "Books on several servers"). A downloaded copy always plays first; that is a rule, not a
 * choice. A book on one server is always itself.
 */

/** Ask every time, a server type, or one particular server (offered when the user has two or more of its type). */
sealed interface SourceChoice {
    data object Ask : SourceChoice
    data class OfType(val source: ServerSource) : SourceChoice
    data class OfServer(val serverId: String) : SourceChoice
}

/** The choice as it is stored: `ask`, `type:ABS`, `type:PLEX` or `server:<id>`. */
fun SourceChoice.stored(): String = when (this) {
    SourceChoice.Ask -> "ask"
    is SourceChoice.OfType -> "type:${source.name}"
    is SourceChoice.OfServer -> "server:$serverId"
}

/** The stored [text] read back; nothing stored or anything unknown is [SourceChoice.Ask]. */
fun parseSourceChoice(text: String?): SourceChoice {
    if (text == null) return SourceChoice.Ask
    return when {
        text.startsWith("type:") -> ServerSource.entries.firstOrNull { it.name == text.removePrefix("type:") }
            ?.let { SourceChoice.OfType(it) } ?: SourceChoice.Ask
        text.startsWith("server:") -> text.removePrefix("server:").takeIf { it.isNotBlank() }
            ?.let { SourceChoice.OfServer(it) } ?: SourceChoice.Ask
        else -> SourceChoice.Ask
    }
}

/**
 * The choice the old single setting ("When a book is on several servers", stored as `source_preference`) becomes, for
 * both downloading and playing. "Prefer the downloaded copy" never asked; it becomes Ask (a downloaded copy plays
 * first anyway).
 */
fun migratedSourceChoice(oldPreference: String?): SourceChoice = when (oldPreference) {
    "PREFER_ABS" -> SourceChoice.OfType(ServerSource.ABS)
    "PREFER_PLEX" -> SourceChoice.OfType(ServerSource.PLEX)
    else -> SourceChoice.Ask
}

/** This choice with the servers that exist now: a removed server's choice is [SourceChoice.Ask]. */
fun SourceChoice.resolvedAgainst(serverIds: Collection<String>): SourceChoice =
    if (this is SourceChoice.OfServer && serverId !in serverIds) SourceChoice.Ask else this

/** Whether [member] is a copy this choice names (never for Ask). */
fun SourceChoice.names(member: BrowseItem): Boolean = when (this) {
    SourceChoice.Ask -> false
    is SourceChoice.OfType -> member.book.source == source
    is SourceChoice.OfServer -> member.book.serverId == serverId
}

private fun BrowseItem.isDownloaded() = downloadState == DownloadState.DOWNLOADED

/** The member of [members] played last (a copy's own row beats a mirrored one of the same time); null when none was played. */
internal fun playedLastOf(members: List<BrowseItem>): BrowseItem? = members.filter { it.progress != null }.sortedWith(
    compareByDescending<BrowseItem> { it.progress!!.lastPlayedAt }.thenBy { it.progress!!.mirrored }
).firstOrNull()

/**
 * The copy to play without asking, or null when the user has to choose. (1) A downloaded copy plays at once: of several,
 * the one played last (a copy's own row beats a mirrored one of the same time), else the first. (2) Otherwise the copy
 * [choice] names when it can play now (its server answers); with Ask, or when that copy cannot play, null: the sheet
 * asks and says why a copy is greyed.
 */
fun chosenPlaySource(item: BrowseItem, choice: SourceChoice, onlineServerIds: Set<String>): BrowseItem? {
    if (!item.isMerged) return item
    val members = item.sources
    val downloaded = members.filter { it.isDownloaded() }
    if (downloaded.isNotEmpty()) return playedLastOf(downloaded) ?: downloaded.first()
    return members.firstOrNull { choice.names(it) && it.book.serverId in onlineServerIds }
}

/**
 * The copy to download without asking, or null when the user has to choose: the copy [choice] names when its server
 * answers and it is not on the phone or on its way already; with Ask, or when that copy cannot be fetched, null.
 */
fun chosenDownloadSource(item: BrowseItem, choice: SourceChoice, onlineServerIds: Set<String>): BrowseItem? {
    if (!item.isMerged) return item
    return item.sources.firstOrNull {
        choice.names(it) && it.book.serverId in onlineServerIds && it.downloadState == DownloadState.NOT_DOWNLOADED
    }
}
