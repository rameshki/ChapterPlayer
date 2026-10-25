package com.chapterplayer.domain.car

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.formatClock
import com.chapterplayer.domain.browse.groupBySeries
import com.chapterplayer.domain.browse.isPlayable
import com.chapterplayer.domain.browse.resumeCandidate
import com.chapterplayer.domain.browse.entryContaining
import com.chapterplayer.domain.browse.member
import com.chapterplayer.domain.browse.resumeCopy
import com.chapterplayer.domain.playback.isFinishedAt

/**
 * What a controller of the playback service may do, from its package and whether the system vouched
 * for that package name (Media3's `ControllerInfo.isPackageNameVerified`).
 */
enum class CarAccess(
    /** Read the browse tree and search it. */
    val canBrowse: Boolean,
    /** Ask for a book of the library (by id or by a search), which the app then loads itself. */
    val canStartBooks: Boolean,
    /** Hand the player its own track items (only this app's controller does that). */
    val canSetOwnTracks: Boolean,
) {
    /** This app: the screens, the notification. */
    FULL(canBrowse = true, canStartBooks = true, canSetOwnTracks = true),

    /** Android Auto, the Assistant, the system, verified. */
    CAR(canBrowse = true, canStartBooks = true, canSetOwnTracks = false),

    /**
     * A car package the system could not vouch for: older Android versions do not check the name a
     * legacy controller gives. It may only start a book of the library, which is what Android Auto
     * does there; browsing (the library's titles) stays closed.
     */
    CAR_UNVERIFIED(canBrowse = false, canStartBooks = true, canSetOwnTracks = false),

    /** Anything else: play, pause and seek what is already loaded (a watch, a headset app), nothing more. */
    TRANSPORT_ONLY(canBrowse = false, canStartBooks = false, canSetOwnTracks = false),
}

/** Decides [CarAccess]; a package name the system did not verify is never trusted as this app. */
fun carAccess(callerPackage: String?, ownPackage: String, packageVerified: Boolean): CarAccess =
    when (isTrustedCarController(callerPackage, ownPackage)) {
        CarTrust.OWN -> if (packageVerified) CarAccess.FULL else CarAccess.TRANSPORT_ONLY
        CarTrust.CAR -> if (packageVerified) CarAccess.CAR else CarAccess.CAR_UNVERIFIED
        CarTrust.NONE -> CarAccess.TRANSPORT_ONLY
    }

/** The car itself (not the Assistant or the system): only its connecting starts a book by itself. */
val CAR_HEAD_UNIT_PACKAGES: Set<String> = setOf(
    "com.google.android.projection.gearhead",
    "com.google.android.autosimulator",
)

/** What a car asked to play. */
sealed interface CarRequest {
    data class Book(val id: String) : CarRequest
    data class Search(val query: String) : CarRequest
    data class Chapter(val index: Int) : CarRequest
    data object Refused : CarRequest
}

/**
 * Reads one requested item: a book id we handed out, a chapter of the loaded book, or a spoken search
 * (an empty media id with a query). Anything carrying an address, a folder or our own track ids is refused.
 */
fun parseCarRequest(mediaId: String, searchQuery: String?, hasUri: Boolean): CarRequest {
    if (hasUri) return CarRequest.Refused
    if (mediaId.isEmpty()) return searchQuery?.let { CarRequest.Search(it.trim()) } ?: CarRequest.Refused
    val node = parseCarNodeId(mediaId)
    if (node != null) {
        if (node.kind != CHAPTER_KIND) return CarRequest.Refused
        val index = node.arg?.takeIf { a -> a.isNotEmpty() && a.all { it in '0'..'9' } }?.toIntOrNull()
        return index?.let { CarRequest.Chapter(it) } ?: CarRequest.Refused
    }
    // The player's own items are "<bookId>/<file>"; book ids never hold a slash.
    if ('/' in mediaId) return CarRequest.Refused
    return CarRequest.Book(mediaId)
}

/** Why a car request cannot be played; the service turns it into a short spoken or shown error. */
enum class CarFailure { REFUSED, UNKNOWN_BOOK, OFFLINE, NO_MATCH, NOTHING_TO_RESUME, NO_CHAPTER }

/** What to do for a car request. */
sealed interface CarPlay {
    data class Start(val book: AudioBook) : CarPlay
    data class SeekChapter(val index: Int) : CarPlay
    data class Fail(val reason: CarFailure) : CarPlay
}

/** The book in the player, for the car's chapter list. */
data class CarNowPlaying(val bookId: String, val title: String, val chapters: List<Chapter>)

/**
 * Resolves [request] against the visible books [items]. A book (Audiobookshelf or Plex) plays when it
 * can be reached [online] (or is downloaded, or is the one already in the player).
 */
fun resolveCarRequest(request: CarRequest, items: List<BrowseItem>, online: Boolean, nowPlaying: CarNowPlaying?): CarPlay =
    when (request) {
        CarRequest.Refused -> CarPlay.Fail(CarFailure.REFUSED)
        is CarRequest.Book -> {
            // Any copy's id finds its entry, so an id handed out before the primary changed still plays.
            val entry = entryContaining(items, request.id)
            if (entry == null) CarPlay.Fail(CarFailure.UNKNOWN_BOOK) else startOrFail(pickCopy(entry, request.id, online, nowPlaying), online, nowPlaying)
        }
        is CarRequest.Chapter ->
            if (nowPlaying != null && request.index in nowPlaying.chapters.indices) CarPlay.SeekChapter(request.index)
            else CarPlay.Fail(CarFailure.NO_CHAPTER)
        is CarRequest.Search ->
            if (request.query.isEmpty()) resumeLast(items, online, nowPlaying)
            else searchAndPick(request.query, items, online, nowPlaying)
    }

/** The last played unfinished book, for "play something" and for resuming after the app was gone. */
fun resumeLast(items: List<BrowseItem>, online: Boolean, nowPlaying: CarNowPlaying?): CarPlay {
    val resume = resumeCandidate(items) ?: return CarPlay.Fail(CarFailure.NOTHING_TO_RESUME)
    return startOrFail(pickCopy(resume.entry, resume.item.book.id, online, nowPlaying), online, nowPlaying)
}

/**
 * Which copy of [entry] the car starts: the copy already in the player (never replaced by another copy), else
 * [requestedId] (the copy asked for, or the one played last) when it can play, else the entry's primary when it can,
 * else the requested copy (so the failure names it).
 */
internal fun pickCopy(entry: BrowseItem, requestedId: String?, online: Boolean, nowPlaying: CarNowPlaying?): BrowseItem {
    if (!entry.isMerged) return entry
    nowPlaying?.bookId?.let { id -> entry.member(id)?.let { return it } }
    val requested = requestedId?.let { entry.member(it) }
    if (requested != null && failureOf(requested, online, nowPlaying) == null) return requested
    val primary = entry.sources.first()
    if (failureOf(primary, online, nowPlaying) == null) return primary
    return requested ?: primary
}

private fun failureOf(item: BrowseItem, online: Boolean, nowPlaying: CarNowPlaying?): CarFailure? = when {
    item.book.id == nowPlaying?.bookId -> null
    !isPlayable(item.downloadState, online, downloadedOnly = false) -> CarFailure.OFFLINE
    else -> null
}

private fun startOrFail(item: BrowseItem, online: Boolean, nowPlaying: CarNowPlaying?): CarPlay =
    failureOf(item, online, nowPlaying)?.let { CarPlay.Fail(it) } ?: CarPlay.Start(item.book)

private fun isUnfinished(item: BrowseItem): Boolean {
    val p = item.progress ?: return true
    return !p.isFinished && !isFinishedAt(p.positionMs, item.book.durationMs)
}

/**
 * A title match plays the best title (exact, then starts with). A match on an author or a series plays the
 * book being listened to, else the first unfinished one in series order, else the first unfinished one.
 * Among equals a playable book wins; when none can play, the best one's reason is the answer.
 */
private fun searchAndPick(query: String, items: List<BrowseItem>, online: Boolean, nowPlaying: CarNowPlaying?): CarPlay {
    val byId = items.associateBy { it.book.id }
    val entries = items.map { BookSearchEntry(it.book.id, it.book.title, it.book.author, it.book.seriesName) }
    val matches = matchBooksForQuery(query, entries).mapNotNull { byId[it] }
    if (matches.isEmpty()) return CarPlay.Fail(CarFailure.NO_MATCH)
    val nq = normalize(query)
    val titleMatches = matches.filter { normalize(it.book.title).startsWith(nq) }
    val candidates = titleMatches.ifEmpty {
        val listening = matches
            .filter { isUnfinished(it) && (it.progress?.positionMs ?: 0L) > 0L }
            .sortedByDescending { it.progress?.lastPlayedAt }
        val inSeries = groupBySeries(matches).flatMap { group -> group.items.filter(::isUnfinished) }
        val unfinished = matches.filter(::isUnfinished)
        (listening + inSeries + unfinished + matches).distinctBy { it.book.id }
    }
    val copies = candidates.map { pickCopy(it, resumeCopy(it).book.id, online, nowPlaying) }
    val playable = copies.firstOrNull { failureOf(it, online, nowPlaying) == null }
    return playable?.let { CarPlay.Start(it.book) } ?: startOrFail(copies.first(), online, nowPlaying)
}

/** A book paused this recently is left alone when the car connects: the listener just stopped it. */
const val CAR_RESUME_PAUSE_GUARD_MS = 60_000L

/**
 * Counts the car's connections (Android Auto opens several), so it is tried once per drive: [connected] is
 * true only for the first verified head-unit connection since the car was last gone. Not thread-safe:
 * the session calls it on its one thread.
 */
class CarConnectionTracker<K> {
    private val open = mutableSetOf<K>()
    private var tried = false

    fun connected(key: K, callerPackage: String?, packageVerified: Boolean): Boolean {
        if (!packageVerified || callerPackage !in CAR_HEAD_UNIT_PACKAGES) return false
        open += key
        if (tried) return false
        tried = true
        return true
    }

    fun disconnected(key: K) {
        if (open.remove(key) && open.isEmpty()) tried = false
    }
}

/**
 * The book to start when the car connects, or null. Only the car itself (verified) starts one, with the
 * switch on, when the player has nothing loaded or playing ([playerBusy]); the book is the last played
 * unfinished one, not touched within [CAR_RESUME_PAUSE_GUARD_MS] (its last played time stands in for the
 * pause time), and playable now. [CarConnectionTracker] makes sure this is asked once per connection.
 */
fun carConnectResume(
    enabled: Boolean,
    callerPackage: String?,
    packageVerified: Boolean,
    playerBusy: Boolean,
    items: List<BrowseItem>,
    online: Boolean,
    nowMs: Long,
): AudioBook? {
    if (!enabled || playerBusy) return null
    if (!packageVerified || callerPackage !in CAR_HEAD_UNIT_PACKAGES) return null
    val resume = resumeCandidate(items) ?: return null
    // The guard and the start are about the same copy: the one played last.
    val last = resume.item.progress?.lastPlayedAt ?: return null
    if (nowMs - last < CAR_RESUME_PAUSE_GUARD_MS) return null
    return (startOrFail(pickCopy(resume.entry, resume.item.book.id, online, nowPlaying = null), online, nowPlaying = null) as? CarPlay.Start)?.book
}

/** One page of [list]; null when the page or its size makes no sense. */
fun <T> carPage(list: List<T>, page: Int, pageSize: Int): List<T>? {
    if (page < 0 || pageSize < 1) return null
    val from = page.toLong() * pageSize
    if (from >= list.size) return emptyList()
    val to = minOf(list.size.toLong(), from + pageSize).toInt()
    return list.subList(from.toInt(), to)
}

private const val CHAPTER_KIND = "chapter"
private const val CHAPTERS_KIND = "chapters"
const val CAR_CHAPTERS_TITLE = "Chapters"

private fun chapterRows(now: CarNowPlaying?): List<CarItem> =
    now?.chapters.orEmpty().mapIndexed { i, chapter ->
        CarItem(
            id = carNodeId(CHAPTER_KIND, "$i"),
            title = chapter.title.trim().ifEmpty { "Chapter ${i + 1}" },
            subtitle = formatClock(chapter.startMs),
            coverUrl = null,
            browsable = false,
            playable = true,
            dimmed = false,
        )
    }

private fun chaptersFolder(now: CarNowPlaying) =
    CarItem(carNodeId(CHAPTERS_KIND), CAR_CHAPTERS_TITLE, now.title, null, browsable = true, playable = false, dimmed = false)

/**
 * [carChildren] plus the chapters of the loaded book: Media3 cannot show the car a queue other than the
 * player's (one item per file), so Continue starts with a "Chapters" folder while a book with chapters is loaded.
 */
fun carChildrenFor(nodeId: String, data: CarData, online: Boolean, nowPlaying: CarNowPlaying?): List<CarItem>? {
    val node = parseCarNodeId(nodeId) ?: return null
    return when {
        node.kind == CHAPTERS_KIND && node.arg == null -> chapterRows(nowPlaying)
        node.kind == "continue" && node.arg == null -> {
            val rows = carChildren(nodeId, data, online) ?: return null
            if (nowPlaying != null && nowPlaying.chapters.isNotEmpty()) listOf(chaptersFolder(nowPlaying)) + rows else rows
        }
        else -> carChildren(nodeId, data, online)
    }
}

/** The root item itself. */
fun carRootItem(): CarItem = CarItem(carNodeId("root"), "", "", null, browsable = true, playable = false, dimmed = false)

/** The item behind [id] (a node, a book or a chapter), found in the list its parent shows; null when unknown. */
fun carItemFor(id: String, data: CarData, online: Boolean, nowPlaying: CarNowPlaying?): CarItem? {
    val node = parseCarNodeId(id)
    if (node == null) {
        // Any copy's id finds its entry; the item keeps the id it was asked for.
        val item = entryContaining(data.items, id) ?: return null
        return carBookItem(item, online).copy(id = id)
    }
    val parent = when (node.kind) {
        "root" -> return if (node.arg == null) carRootItem() else null
        "continue", "recent", "library", "downloaded" -> "root"
        "series", "all" -> if (node.arg == null) "library" else node.kind
        "authors", "collections" -> if (node.arg == null) "library" else return null
        "author" -> "authors"
        "collection" -> "collections"
        CHAPTERS_KIND -> "continue"
        CHAPTER_KIND -> CHAPTERS_KIND
        "note" -> "downloaded"
        else -> return null
    }
    return carChildrenFor(carNodeId(parent), data, online, nowPlaying)?.firstOrNull { it.id == id }
}

/** Search results for the car's search screen: matching books, best first; an empty query shows the book to resume. */
fun carSearchResults(query: String, data: CarData, online: Boolean): List<CarItem> {
    val items = data.items
    if (query.isBlank()) return listOfNotNull(resumeCandidate(items)?.entry?.let { carBookItem(it, online) })
    val byId = items.associateBy { it.book.id }
    val entries = items.map { BookSearchEntry(it.book.id, it.book.title, it.book.author, it.book.seriesName) }
    return matchBooksForQuery(query, entries).mapNotNull { byId[it] }.map { carBookItem(it, online) }
}

/** What the car shows or says when a request fails; plain words, never an address or a token. */
fun carFailureMessage(reason: CarFailure): String = when (reason) {
    CarFailure.REFUSED -> "Not allowed"
    CarFailure.UNKNOWN_BOOK -> "This book is no longer in your library"
    CarFailure.OFFLINE -> "This book needs a connection"
    CarFailure.NO_MATCH -> "No book matches that"
    CarFailure.NOTHING_TO_RESUME -> "Nothing to resume yet"
    CarFailure.NO_CHAPTER -> "That chapter is not available"
}

/** Whether the player's items ([mediaIds], "<bookId>/<file>") are the files of [bookId]. */
fun playerHoldsBook(mediaIds: List<String>, bookId: String): Boolean =
    mediaIds.isNotEmpty() && mediaIds.all { id ->
        id.startsWith("$bookId/") && id.length > bookId.length + 1 && id.substring(bookId.length + 1).all { it in '0'..'9' }
    }
