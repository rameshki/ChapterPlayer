package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.ReadingProgress

/*
 * Bringing every copy of a book to where listening is NOW, once, e.g. after a server is added: Jellyfin holds a newer place of a
 * book than Audiobookshelf and Plex, or the other way round, and the older copies (on the phone and on their servers) catch up.
 * It reuses the rules of the always-on mirroring ([mirroredProgress]: by fraction of the book, newest wins, an echo is kept) and
 * the same send queue ([PendingProgress] with `mirrorOf`, delivered by the flusher with its own newest-wins check against the
 * server), so a copy that is already newer, a book the person is playing and a finished mark made in this session are left alone.
 */

/**
 * Where listening is across a book's copies: the newest row that says anything (a place past the start, or finished). On the
 * same time a copy's own row beats a mirrored one (a mirror only repeats another row), then the first in the order given.
 * Null when no copy has been listened to.
 */
fun newestCopyProgress(rows: Collection<ReadingProgress>): ReadingProgress? =
    rows.filter { it.isFinished || it.positionMs > 0L }
        .fold(null as ReadingProgress?) { best, row ->
            when {
                best == null -> row
                row.lastPlayedAt > best.lastPlayedAt -> row
                row.lastPlayedAt == best.lastPlayedAt && best.mirrored && !row.mirrored -> row
                else -> best
            }
        }

/**
 * What the server of [peer] is told so that it holds [mirror] (the row [mirroredProgress] made for it from [source]): the mapped
 * position, finished when the source is, and the source's own time, so the flusher's newest-wins check leaves anything the peer's
 * server learned later alone. Listened time is 0: nothing was listened to here.
 */
fun crossSyncPending(source: ReadingProgress, peer: MirrorPeer, mirror: ReadingProgress): PendingProgress =
    PendingProgress(
        peer.bookId, peer.serverId, mirror.positionMs, peer.durationMs.takeIf { it > 0L } ?: mirror.durationMs,
        listenedMs = 0L, updatedAtMs = source.lastPlayedAt,
        markedFinished = if (source.isFinished) true else null, mirrorOf = source.bookId,
    )

/** What one run did, for the screen: the books looked at and the copies brought up to date. */
data class CopySyncResult(val booksChecked: Int = 0, val copiesUpdated: Int = 0) {
    /** The line the Settings button shows. */
    val text: String
        get() = when {
            booksChecked == 0 -> "No book is on more than one server."
            copiesUpdated == 0 -> "Every copy was already up to date ($booksChecked books on more than one server)."
            copiesUpdated == 1 -> "Brought 1 copy up to date."
            else -> "Brought $copiesUpdated copies up to date."
        }
}
