package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerSource
import kotlin.math.abs
import kotlin.math.max

/*
 * One entry for a book that several servers have. Matching is conservative: the same normalised title, the same
 * normalised first author and lengths within 2% or 5 minutes (an unknown length never matches). Two books of one
 * server never join, and a group holds at most one book per server. The user's corrections ([MergeOverrides]) win:
 * a split keeps a pair apart (also through a third member), a manual merge joins a pair whatever the titles say.
 * Everything is sorted by book id before grouping, so the groups do not depend on the input order.
 *
 * The members are ordered (and the first is the primary, the one the lists show) by: a downloaded copy first, a
 * reachable server before an unreachable one, then the server type the preference names, then Audiobookshelf, then
 * the book id. The entry's progress is the member progress with the newest `lastPlayedAt` (the copy listened to last;
 * progress is not mirrored between sources yet).
 */

/** What to do when a book is on several servers. The default prefers the downloaded copy, then Audiobookshelf. */
enum class SourcePreference { ASK, PREFER_ABS, PREFER_PLEX, PREFER_DOWNLOADED }

val DEFAULT_SOURCE_PREFERENCE = SourcePreference.PREFER_DOWNLOADED

/** The Settings name of [preference]. */
fun sourcePreferenceLabel(preference: SourcePreference): String = when (preference) {
    SourcePreference.ASK -> "Always ask"
    SourcePreference.PREFER_ABS -> "Prefer Audiobookshelf"
    SourcePreference.PREFER_PLEX -> "Prefer Plex"
    SourcePreference.PREFER_DOWNLOADED -> "Prefer the downloaded copy"
}

/** The line under each choice in Settings. */
fun sourcePreferenceDetail(preference: SourcePreference): String = when (preference) {
    SourcePreference.ASK -> "Play and Download always ask which copy to use."
    SourcePreference.PREFER_ABS -> "Uses Audiobookshelf when it can play; asks otherwise."
    SourcePreference.PREFER_PLEX -> "Uses Plex when it can play; asks otherwise."
    SourcePreference.PREFER_DOWNLOADED -> "The copy on the phone, otherwise Audiobookshelf; asks otherwise."
}

/**
 * Everything the merging needs besides the books: the user's corrections, the Settings choice, the servers that can
 * stream now ([onlineServerIds]) and whether the phone has a network at all (for the reason a source cannot play).
 */
data class MergeContext(
    val overrides: MergeOverrides = MergeOverrides(),
    val preference: SourcePreference = DEFAULT_SOURCE_PREFERENCE,
    val onlineServerIds: Set<String> = emptySet(),
    val deviceOnline: Boolean = true,
)

/**
 * The servers that can stream now: none without a network or with Downloaded only on; otherwise every server whose
 * last sync did not fail (an error newer than the last success counts as unreachable until a sync works again).
 */
fun reachableServerIds(
    serverIds: Collection<String>,
    records: Map<String, com.chapterplayer.data.sync.ServerStatusRecord>,
    online: Boolean,
    downloadedOnly: Boolean,
): Set<String> {
    if (!online || downloadedOnly) return emptySet()
    return serverIds.filterTo(LinkedHashSet()) { id ->
        val record = records[id] ?: return@filterTo true
        val errorAt = record.lastErrorAtMs ?: return@filterTo true
        val success = record.lastSuccessAtMs
        success != null && success > errorAt
    }
}

/** [mergeDuplicates] with everything from [context]. */
fun mergeDuplicates(items: List<BrowseItem>, context: MergeContext): List<BrowseItem> =
    mergeDuplicates(items, context.overrides, context.preference, context.onlineServerIds)

/** The user's corrections, as pairs of book ids (either order). */
data class MergeOverrides(
    val splits: Set<Pair<String, String>> = emptySet(),
    val merges: Set<Pair<String, String>> = emptySet(),
)

private const val LENGTH_TOLERANCE = 0.02
private const val LENGTH_SLACK_MS = 5 * 60_000L

private val EDITION_SUFFIX = Regex("""\s*(?:[(\[]\s*(?:un)?abridged(?:\s+edition)?\s*[)\]]|[-:,]\s*(?:un)?abridged(?:\s+edition)?)\s*$""")
private val ARTICLE = Regex("""^(?:the|an|a)\s+""")
private val NOT_ALNUM = Regex("""[^\p{L}\p{N}]+""")
private val AUTHOR_SEPARATORS = Regex("""\s*(?:[,;&]|\band\b)\s*""")

/** A title as matching compares it: accents folded, edition suffix and leading article gone, only letters and digits. */
fun normalisedTitle(title: String): String {
    var t = foldAccents(title).trim()
    while (true) {
        val stripped = t.replace(EDITION_SUFFIX, "").trim()
        if (stripped == t) break
        t = stripped
    }
    t = t.replace(ARTICLE, "")
    return t.replace(NOT_ALNUM, "")
}

/** The first author as matching compares it: accents folded, only letters and digits. */
fun normalisedFirstAuthor(author: String): String {
    val first = foldAccents(author).trim().split(AUTHOR_SEPARATORS).firstOrNull { it.isNotBlank() } ?: return ""
    return first.replace(NOT_ALNUM, "")
}

private fun lengthsAgree(a: Long, b: Long): Boolean {
    if (a <= 0L || b <= 0L) return false
    val diff = abs(a - b)
    return diff <= LENGTH_SLACK_MS || diff <= max(a, b) * LENGTH_TOLERANCE
}

private fun pairKey(a: String, b: String): Pair<String, String> = if (a <= b) a to b else b to a

/**
 * One entry per group of [items] that are the same book, at the place of the group's first member in [items];
 * a book with no match passes through unchanged. See the file comment for the rules.
 */
fun mergeDuplicates(
    items: List<BrowseItem>,
    overrides: MergeOverrides,
    preference: SourcePreference,
    onlineServerIds: Set<String>,
): List<BrowseItem> {
    if (items.size < 2) return items
    // One item per id (a repeated id cannot be two sources).
    val byId = LinkedHashMap<String, BrowseItem>()
    for (item in items) if (item.book.id !in byId) byId[item.book.id] = item
    val ids = byId.keys.sorted()
    val index = HashMap<String, Int>()
    ids.forEachIndexed { i, id -> index[id] = i }

    val splits = overrides.splits.mapTo(HashSet()) { pairKey(it.first, it.second) }
    val forced = overrides.merges.map { pairKey(it.first, it.second) }
        .filter { it.first != it.second && it.first in index && it.second in index && it !in splits }
        .distinct().sortedWith(compareBy({ it.first }, { it.second }))

    // Automatic candidates: same title and first author, then the length check.
    val buckets = HashMap<String, MutableList<String>>()
    for (id in ids) {
        val book = byId.getValue(id).book
        val title = normalisedTitle(book.title)
        val author = normalisedFirstAuthor(book.author)
        if (title.isEmpty() || author.isEmpty()) continue
        buckets.getOrPut("$title\u0000$author") { mutableListOf() }.add(id)
    }
    class Candidate(val a: String, val b: String, val diff: Long)
    val automatic = mutableListOf<Candidate>()
    for (bucket in buckets.values) {
        for (i in bucket.indices) for (j in i + 1 until bucket.size) {
            val a = byId.getValue(bucket[i]).book
            val b = byId.getValue(bucket[j]).book
            if (a.serverId == b.serverId || !lengthsAgree(a.durationMs, b.durationMs)) continue
            val key = pairKey(a.id, b.id)
            if (key in splits) continue
            automatic += Candidate(key.first, key.second, abs(a.durationMs - b.durationMs))
        }
    }
    automatic.sortWith(compareBy<Candidate>({ it.diff }, { it.a }, { it.b }))

    // Union-find over groups that also keep their member lists, so a join can be refused.
    val parent = IntArray(ids.size) { it }
    val members = Array(ids.size) { mutableListOf(ids[it]) }
    fun find(i: Int): Int {
        var x = i
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
        return x
    }
    fun tryJoin(a: String, b: String) {
        val ra = find(index.getValue(a))
        val rb = find(index.getValue(b))
        if (ra == rb) return
        val left = members[ra]
        val right = members[rb]
        val leftServers = left.mapTo(HashSet()) { byId.getValue(it).book.serverId }
        if (right.any { byId.getValue(it).book.serverId in leftServers }) return
        if (left.any { l -> right.any { r -> pairKey(l, r) in splits } }) return
        val (root, child) = if (ra < rb) ra to rb else rb to ra
        parent[child] = root
        members[root].addAll(members[child])
        members[child].clear()
    }
    for (pair in forced) tryJoin(pair.first, pair.second)
    for (c in automatic) tryJoin(c.a, c.b)

    val emitted = HashSet<Int>()
    val result = ArrayList<BrowseItem>(byId.size)
    for (item in byId.values) {
        val root = find(index.getValue(item.book.id))
        val group = members[root]
        if (group.size < 2) { result += item; continue }
        if (!emitted.add(root)) continue
        result += entryOf(group.map { byId.getValue(it) }, preference, onlineServerIds)
    }
    return result
}

private fun preferredSource(preference: SourcePreference): ServerSource =
    if (preference == SourcePreference.PREFER_PLEX) ServerSource.PLEX else ServerSource.ABS

private fun memberOrder(preference: SourcePreference, onlineServerIds: Set<String>): Comparator<BrowseItem> {
    val preferred = preferredSource(preference)
    return compareBy<BrowseItem>(
        { if (it.downloadState == DownloadState.DOWNLOADED) 0 else 1 },
        { if (it.book.serverId in onlineServerIds) 0 else 1 },
        { if (it.book.source == preferred) 0 else 1 },
        { if (it.book.source == ServerSource.ABS) 0 else 1 },
        { it.book.id },
    )
}

/** The entry for [members] (each as it is on its own server): the first by the member order, newest progress. */
private fun entryOf(members: List<BrowseItem>, preference: SourcePreference, onlineServerIds: Set<String>): BrowseItem {
    val ordered = members.map { it.copy(sources = emptyList()) }.sortedWith(memberOrder(preference, onlineServerIds))
    return withPrimary(ordered.first(), ordered)
}

private fun withPrimary(primary: BrowseItem, ordered: List<BrowseItem>): BrowseItem {
    val newest = ordered.mapNotNull { it.progress }.sortedWith(compareByDescending<com.chapterplayer.data.model.ReadingProgress> { it.lastPlayedAt }.thenBy { it.bookId }).firstOrNull()
    val sources = listOf(primary) + ordered.filter { it.book.id != primary.book.id }
    return primary.copy(progress = newest, sources = sources)
}

/**
 * [item] if it has a member on one of [serverIds], with that member as the primary (the first such in member order);
 * null when no member is on them. An empty set keeps every item as it is.
 */
fun BrowseItem.onServers(serverIds: Set<String>): BrowseItem? {
    if (serverIds.isEmpty()) return this
    if (!isMerged) return takeIf { book.serverId in serverIds }
    if (book.serverId in serverIds) return this
    val match = sources.firstOrNull { it.book.serverId in serverIds } ?: return null
    return withPrimary(match, sources)
}

/** The servers this entry is on. */
val BrowseItem.serverIds: Set<String> get() = allSources.mapTo(LinkedHashSet()) { it.book.serverId }

private fun canPlay(member: BrowseItem, onlineServerIds: Set<String>): Boolean =
    member.downloadState == DownloadState.DOWNLOADED || member.book.serverId in onlineServerIds

/**
 * The member to play without asking, or null when the user has to choose. A book on one server is always itself.
 * Prefer downloaded: the downloaded copy, otherwise a reachable Audiobookshelf copy. Prefer Audiobookshelf or Plex:
 * the first copy of that type that can play (downloaded or reachable). For these three, when the preferred copy
 * cannot play but exactly one other copy can, that one plays (only Plex reachable: Plex). Always ask: null.
 */
fun chosenSource(item: BrowseItem, preference: SourcePreference, onlineServerIds: Set<String>): BrowseItem? {
    if (!item.isMerged) return item
    val members = item.sources
    val preferred = when (preference) {
        SourcePreference.ASK -> return null
        SourcePreference.PREFER_DOWNLOADED ->
            members.firstOrNull { it.downloadState == DownloadState.DOWNLOADED }
                ?: members.firstOrNull { it.book.source == ServerSource.ABS && it.book.serverId in onlineServerIds }
        SourcePreference.PREFER_ABS -> members.firstOrNull { it.book.source == ServerSource.ABS && canPlay(it, onlineServerIds) }
        SourcePreference.PREFER_PLEX -> members.firstOrNull { it.book.source == ServerSource.PLEX && canPlay(it, onlineServerIds) }
    }
    return preferred ?: members.filter { canPlay(it, onlineServerIds) }.singleOrNull()
}

/** One row of the source sheet. */
data class SourceOption(val item: BrowseItem, val downloaded: Boolean, val playable: Boolean, val unavailableReason: String?)

/** The rows of the source sheet: downloaded copies first, then the member order; the rest say why they cannot play. */
fun sourceOptions(item: BrowseItem, onlineServerIds: Set<String>, deviceOnline: Boolean): List<SourceOption> =
    item.allSources
        .sortedWith(compareBy { if (it.downloadState == DownloadState.DOWNLOADED) 0 else 1 })
        .map { member ->
            val downloaded = member.downloadState == DownloadState.DOWNLOADED
            val playable = canPlay(member, onlineServerIds)
            val reason = when {
                playable -> null
                !deviceOnline -> "No network"
                else -> "Server not reachable"
            }
            SourceOption(member, downloaded, playable, reason)
        }

private fun ServerSource.longLabel(): String = when (this) {
    ServerSource.ABS -> "Audiobookshelf"
    ServerSource.PLEX -> "Plex"
}

/** The small mark on a merged tile ("2 sources"); null for a book on one server. */
fun sourcesMark(item: BrowseItem): String? = if (item.isMerged) "${item.sources.size} sources" else null

/** What a screen reader says about the sources ("available from Audiobookshelf and Plex"); null for one server. */
fun sourcesDescription(item: BrowseItem): String? {
    if (!item.isMerged) return null
    val types = item.sources.map { it.book.source }.distinct().sortedBy { it.ordinal }
    if (types.size == 1) return "available from ${item.sources.size} ${types.single().longLabel()} servers"
    return "available from " + types.joinToString(" and ") { it.longLabel() }
}

/** The entry [bookId] belongs to in the merged list [all] (as the primary or another member); null when it is not listed. */
fun entryContaining(all: List<BrowseItem>, bookId: String): BrowseItem? =
    all.firstOrNull { it.book.id == bookId } ?: all.firstOrNull { entry -> entry.sources.any { it.book.id == bookId } }

/**
 * The books "Merge with…" offers for [current]: entries of [all] with no member on a server [current] is already on
 * (a group holds one book per server), matching [query] like Search does (every entry for a blank query), by title.
 */
fun mergeCandidates(all: List<BrowseItem>, current: BrowseItem, query: String): List<BrowseItem> {
    val taken = current.serverIds
    val ownIds = current.allSources.mapTo(HashSet()) { it.book.id }
    val open = all.filter { entry -> entry.allSources.none { it.book.id in ownIds } && entry.serverIds.none { it in taken } }
    val matching = if (query.isBlank()) open else searchItems(open, query)
    return matching.sortedWith(compareBy({ titleSortKey(it.book.title) }, { it.book.id }))
}
