package com.chapterplayer.domain.diagnostics

import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.SourceChoice
import com.chapterplayer.domain.browse.chosenDownloadSource
import com.chapterplayer.domain.browse.chosenPlaySource

/** The grouping and source rules of [entry] right now, worded for the report. [serverNames] maps server ids to the names the user gave. */
fun describeMerge(entry: BrowseItem, context: MergeContext, serverNames: Map<String, String>): DiagMerge {
    val members = entry.allSources
    val ids = members.map { it.book.id }.toSet()
    fun nameOf(item: BrowseItem) = serverNames[item.book.serverId] ?: "unnamed server"
    fun choiceText(choice: SourceChoice) = when (choice) {
        SourceChoice.Ask -> "Ask every time"
        is SourceChoice.OfType -> if (choice.source == ServerSource.ABS) "Audiobookshelf" else "Plex"
        is SourceChoice.OfServer -> serverNames[choice.serverId] ?: "a removed server"
    }
    val playChosen = chosenPlaySource(entry, context.playChoice, context.onlineServerIds)
    val downloaded = members.filter { it.downloadState == DownloadState.DOWNLOADED }
    val playPick = when {
        !entry.isMerged -> "this copy (the only one)"
        playChosen != null && downloaded.isNotEmpty() -> "${nameOf(playChosen)} (a downloaded copy plays first)"
        playChosen != null -> "${nameOf(playChosen)} (the Play from choice names it and its server answers)"
        context.playChoice == SourceChoice.Ask -> "asks (Play from is Ask every time and no copy is downloaded)"
        else -> "asks (the chosen copy cannot play now: its server does not answer or the phone is offline)"
    }
    val downloadChosen = chosenDownloadSource(entry, context.downloadChoice, context.onlineServerIds)
    val downloadPick = when {
        !entry.isMerged -> "this copy (the only one)"
        downloadChosen != null -> "${nameOf(downloadChosen)} (the Download from choice names it, its server answers and it is not downloaded yet)"
        context.downloadChoice == SourceChoice.Ask -> "asks (Download from is Ask every time)"
        else -> "asks (the chosen copy is already downloaded or queued, or its server does not answer)"
    }
    return DiagMerge(
        memberBookIds = members.map { it.book.id },
        splits = context.overrides.splits.filter { it.first in ids || it.second in ids }.sortedBy { it.first + it.second },
        merges = context.overrides.merges.filter { it.first in ids || it.second in ids }.sortedBy { it.first + it.second },
        downloadChoice = choiceText(context.downloadChoice),
        playChoice = choiceText(context.playChoice),
        downloadPick = downloadPick,
        playPick = playPick,
        deviceOnline = context.deviceOnline,
    )
}

/** Whether the Diagnostics row is offered: debuggable builds only. */
fun showDiagnosticsRow(debuggable: Boolean): Boolean = debuggable
