package com.chapterplayer.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.downloads.DownloadController
import com.chapterplayer.data.downloads.DownloadOverview
import com.chapterplayer.data.downloads.DownloadRow
import com.chapterplayer.data.permissions.NotificationPermission
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PLAYBACK_NOT_ON_IOS_TEXT
import com.chapterplayer.data.PlatformFeatures
import com.chapterplayer.data.playback.PlaybackStartException
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.merge.BookMergeRepository
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.chosenDownloadSource
import com.chapterplayer.domain.browse.chosenPlaySource
import com.chapterplayer.domain.browse.entryContaining
import com.chapterplayer.domain.browse.mergeCandidates
import com.chapterplayer.domain.browse.sourceOptions
import com.chapterplayer.domain.browse.sourcesDescription
import com.chapterplayer.domain.browse.BookLinks
import com.chapterplayer.domain.browse.SeriesEntry
import com.chapterplayer.domain.browse.aboutInfo
import com.chapterplayer.domain.browse.bookLinks
import com.chapterplayer.domain.browse.chapterRows
import com.chapterplayer.domain.browse.formatLength
import com.chapterplayer.domain.browse.formatTimeLeft
import com.chapterplayer.domain.browse.isPlayable
import com.chapterplayer.domain.browse.seriesNeighbours
import com.chapterplayer.domain.playback.isFinishedAt
import com.chapterplayer.domain.playback.MarkAction
import com.chapterplayer.domain.playback.MARK_ERROR_TEXT
import com.chapterplayer.domain.playback.markActionFor
import com.chapterplayer.domain.playback.markResultText
import com.chapterplayer.domain.browse.favouriteRowIds
import com.chapterplayer.domain.browse.markTarget
import com.chapterplayer.domain.browse.isFavourite
import com.chapterplayer.data.favourites.FavouritesRepository
import com.chapterplayer.data.favourites.NoFavourites
import com.chapterplayer.data.upnext.NoUpNext
import com.chapterplayer.data.upnext.UpNextRepository
import com.chapterplayer.domain.playback.UP_NEXT_ERROR_TEXT
import com.chapterplayer.domain.playback.UP_NEXT_REMOVED_TEXT
import com.chapterplayer.domain.playback.UpNextOutcome
import com.chapterplayer.domain.playback.editUpNext
import com.chapterplayer.domain.playback.removeFromUpNext
import com.chapterplayer.domain.playback.upNextOutcomeText
import com.chapterplayer.domain.playback.startPositionFor
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withTimeoutOrNull
import com.chapterplayer.domain.browse.hasCopy
import com.chapterplayer.domain.browse.member
import com.chapterplayer.domain.browse.serverDisplayNames
import com.chapterplayer.domain.browse.shortLabel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One book's details: the cached chapters at once, the live chapters and description when the server answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailsViewModel(
    private val bookId: String,
    private val books: BookRepository,
    downloads: DownloadStates,
    private val controller: PlaybackController,
    private val starter: PlaybackStarter,
    connectivity: ConnectivityMonitor,
    settings: AppSettings,
    dispatcher: CoroutineDispatcher,
    overview: DownloadOverview,
    private val downloadController: DownloadController,
    private val notifications: NotificationPermission,
    merging: SourceMerging = SourceMerging.None,
    private val merges: BookMergeRepository? = null,
    servers: ServerRepository? = null,
    /** Without playback (iOS until Stage 5 step 4) Play is off with the reason; without downloads, Download is. */
    private val features: PlatformFeatures = PlatformFeatures.Full,
    private val favourites: FavouritesRepository = NoFavourites,
    /** Up next: Play next and Add to Up next. */
    private val upNext: UpNextRepository = NoUpNext,
    /**
     * Built for the book long-press menu (`BookActionsSheet`) rather than the page: nothing is fetched from the server on
     * open, and the results of the actions are reported through [message] for a snackbar.
     */
    private val quickActions: Boolean = false,
) : ViewModel() {

    private class Live(val details: BookDetails? = null, val loading: Boolean = false, val error: String? = null)

    private class Whole(
        val book: AudioBook?,
        val series: List<SeriesEntry>,
        val links: BookLinks,
        val downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
        /** The list entry this book belongs to (several copies when other servers have it too), and the whole list. */
        val entry: BrowseItem? = null,
        val all: List<BrowseItem> = emptyList()
    )

    /**
     * The merging inputs, the server names, the open source sheet, the "Merge with" sheet (null: closed), a notice, and
     * the download sizes the other copies' servers gave (by book id).
     */
    private class Extras(
        val context: MergeContext?,
        val serverNames: Map<String, String>,
        val sheet: SourceAction?,
        val mergeSheet: MergeSheetUi?,
        val notice: String?,
        val copySizes: Map<String, Long>,
    )

    /**
     * The play control's own state: a start under way, the last start failure, and a player error the
     * person has dismissed (so the same text does not come back until playback is tried again).
     */
    private class Local(val starting: Boolean = false, val error: String? = null, val dismissedPlayerError: String? = null)

    private class Env(
        val player: PlaybackState,
        val local: Local,
        val online: Boolean,
        val downloadedOnly: Boolean,
        /** Every copy of this book that has a download row (this page's copy first); empty when none has. */
        val rows: List<Pair<String, DownloadRow>>,
        /** The copy whose "Remove download" waits for the confirmation. */
        val confirmingRemoveId: String?
    ) {
        val downloadBookId: String? get() = rows.firstOrNull()?.first
        val download: DownloadRow? get() = rows.firstOrNull()?.second
    }

    private val live = MutableStateFlow(Live())
    private val local = MutableStateFlow(Local())
    private val selectedTab = MutableStateFlow(DetailsTab.CHAPTERS)
    private val confirmingRemove = MutableStateFlow<String?>(null)
    private var loadJob: Job? = null

    // Series and links come from every book of the library, never from the filtered lists.
    private val whole = observeBrowseItems(books, downloads, merging).map { all ->
        // This page shows its own copy, also when the list shows a merged book as another server's copy.
        val entry = entryContaining(all, bookId)
        val item = entry?.allSources?.firstOrNull { it.book.id == bookId }
        val book = item?.book
        if (book == null) Whole(null, emptyList(), BookLinks(null, emptyList(), emptyList()))
        else Whole(book, seriesNeighbours(all, book), bookLinks(all, book), item.downloadState, entry, all)
    }.flowOn(dispatcher)

    private val sourceSheet = MutableStateFlow<SourceAction?>(null)
    private val copySizes = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val sheetAndSizes = combine(sourceSheet, copySizes) { sheet, sizes -> sheet to sizes }
    private val mergeQuery = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    // Servers with the same or no name are told apart ("Home (books.example)", "Home #2").
    private val serverNames = servers?.observeAll()?.map(::serverDisplayNames) ?: flowOf(emptyMap())

    // The "Merge with" list is worked out off the main thread, only when the search or the library changes.
    private val mergeSheet = combine(whole, mergeQuery, serverNames, merging.context) { w, query, names, context ->
        val entry = w.entry
        if (query == null || context == null || entry == null) null
        else MergeSheetUi(query, mergeCandidates(w.all, entry, query).map { candidateRow(it, names) })
    }.distinctUntilChanged().flowOn(dispatcher)

    private val extras = combine(merging.context, serverNames, sheetAndSizes, mergeSheet, notice) { context, names, sheetSizes, merge, text ->
        Extras(context, names, sheetSizes.first, merge, text, sheetSizes.second)
    }

    /*
     * Every copy that has a download row, this page's copy first: the first is the main download control, the others
     * get a control each (a merged book can have a copy on the phone and another one on its way). With none, the main
     * control is this page's Download (a merged book picks its copy when Download is tapped).
     */
    private val downloadRowsOfCopies = whole
        .map { w -> w.entry?.let(::downloadableMembers)?.map { it.book.id }.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { copyIds ->
            val ids = (listOf(bookId) + copyIds).distinct()
            combine(ids.map { id -> overview.observeBook(id).map { row -> id to row } }) { rows ->
                rows.mapNotNull { (id, row) -> row?.let { id to it } }
            }
        }

    private val downloadRow = combine(downloadRowsOfCopies, confirmingRemove) { rows, confirming -> rows to confirming }

    private val env = combine(
        controller.state, local, connectivity.isOnline, settings.downloadedOnly, downloadRow
    ) { player, local, online, downloadedOnly, download ->
        Env(player, local, online, downloadedOnly, download.first, download.second)
    }

    /** The stored favourites and the Up next rows (by book id). */
    private class Marks(val favouriteIds: Set<String>, val queue: List<String>)

    private val wholeAndExtras = combine(whole, extras, combine(favourites.favouriteIds, upNext.queue, ::Marks)) { w, x, m -> Triple(w, x, m) }

    private val _message = MutableStateFlow<String?>(null)

    /** The result of a long-press menu action (only with `quickActions`) or of an Up next action, for a snackbar; see [onMessageShown]. */
    val message: StateFlow<String?> = _message

    val uiState: StateFlow<BookDetailsUiState> = combine(
        wholeAndExtras, books.observeProgress(bookId), live, selectedTab, env
    ) { (whole, extras, marks), progress, live, tab, env ->
        val favouriteIds = marks.favouriteIds
        val book = whole.book
        if (book == null) {
            BookDetailsUiState(phase = DetailsPhase.NOT_FOUND, selectedTab = tab)
        } else {
            val chapters = live.details?.chapters ?: book.chapters
            // A copy whose length is not known yet (a Plex album before it was opened) shows another copy's.
            val knownLength = book.durationMs.takeIf { it > 0L }
                ?: whole.entry?.allSources?.firstOrNull { it.book.durationMs > 0L }?.book?.durationMs ?: book.durationMs
            val timeLeft = when {
                progress == null -> null
                progress.isFinished || progress.positionMs >= knownLength -> "Finished"
                progress.positionMs > 0 -> formatTimeLeft(knownLength, progress.positionMs)
                else -> null
            }
            val entry = whole.entry
            val context = extras.context
            val merged = entry != null && entry.isMerged && context != null
            val thisLoaded = env.player.bookId == bookId
            // Another copy of this book in the player counts as this book playing (Pause, Open player), but its
            // position belongs to that copy: the chapters keep the saved position and chapter taps are off.
            val otherCopy = env.player.bookId?.takeIf { merged && !thisLoaded }?.let { id -> entry!!.member(id) }
            val loaded = thisLoaded || otherCopy != null
            val playback = when {
                env.local.starting -> DetailsPlayback.STARTING
                // Buffering before the first sound still counts as playing: Pause is offered at once.
                loaded && (env.player.isPlaying || (env.player.isBuffering && env.player.playWhenReady)) -> DetailsPlayback.PLAYING
                loaded -> DetailsPlayback.PAUSED
                else -> DetailsPlayback.NOT_PLAYING
            }
            val canPlay = features.playback && book.serverId.isNotBlank() && (
                isPlayable(whole.downloadState, env.online, env.downloadedOnly) ||
                    (merged && entry!!.sources.any { isPlayable(it.downloadState, env.online, env.downloadedOnly) })
                )
            val options = if (merged) sourceOptions(entry!!, context!!.onlineServerIds, context.deviceOnline) else emptyList()
            val mainDownloadId = env.downloadBookId ?: bookId
            val rowIds = env.rows.mapTo(HashSet()) { it.first }
            fun copyLabel(id: String): String? = entry?.member(id)?.let { copy ->
                val name = extras.serverNames[copy.book.serverId]
                copy.book.source.shortLabel() + (if (name.isNullOrBlank()) "" else " · $name")
            }
            val rows = options.map { sourceRow(it, extras.serverNames, lastPlayedId = entry?.progress?.bookId) }
            // While this book is loaded in the player the highlight follows the live position.
            val shown = if (thisLoaded) ReadingProgress(bookId, env.player.positionMs, book.durationMs, 0L) else progress
            // No local progress: let the server say where; a book finished here starts over.
            val savedStart = startPositionFor(progress, book.durationMs)
            BookDetailsUiState(
                phase = DetailsPhase.LOADED,
                book = book,
                chapters = chapterRows(chapters, shown),
                about = aboutInfo(book, live.details),
                series = whole.series,
                links = whole.links,
                timeLeft = timeLeft,
                lengthText = formatLength(knownLength),
                loadingDetails = live.loading,
                detailsLoaded = live.details != null,
                error = live.error,
                selectedTab = tab,
                playback = playback,
                canPlay = canPlay,
                cannotPlayReason = when {
                    canPlay -> null
                    !features.playback -> PLAYBACK_NOT_ON_IOS_TEXT
                    else -> NEEDS_CONNECTION_TEXT
                },
                playError = env.local.error
                    ?: env.player.error?.takeIf { loaded && it != env.local.dismissedPlayerError },
                currentPositionMs = if (thisLoaded) env.player.positionMs else progress?.positionMs ?: 0L,
                resumePositionMs = savedStart,
                download = detailsDownload(
                    env.download, env.online,
                    // A shown download of another copy has its own size; this page's server only knows this copy's.
                    knownSizeBytes = env.download?.totalBytes?.takeIf { it > 0L }
                        ?: live.details?.downloadSizeBytes?.takeIf { mainDownloadId == bookId },
                    confirmingRemove = env.confirmingRemoveId == mainDownloadId,
                    available = features.downloads,
                ).let { d ->
                    // A copy has a download: another copy is fetched only when the person asks for it, and only one
                    // with no download yet whose server answers can be.
                    val another = merged && features.downloads && env.online && env.rows.isNotEmpty() &&
                        entry!!.allSources.any {
                            it.book.id !in rowIds && it.downloadState == DownloadState.NOT_DOWNLOADED &&
                                it.book.serverId in context!!.onlineServerIds
                        }
                    if (another) d.copy(canDownloadAnotherCopy = true) else d
                },
                downloadBookId = mainDownloadId,
                downloadCopyLabel = if (merged && env.rows.isNotEmpty()) copyLabel(mainDownloadId) else null,
                otherCopyDownloads = if (!merged) emptyList() else env.rows.drop(1).map { (id, row) ->
                    CopyDownloadUi(
                        bookId = id,
                        label = copyLabel(id).orEmpty(),
                        download = detailsDownload(
                            row, env.online, knownSizeBytes = row.totalBytes.takeIf { it > 0L },
                            confirmingRemove = env.confirmingRemoveId == id, available = features.downloads,
                        ),
                    )
                },
                sources = rows,
                sourcesDescription = if (merged) sourcesDescription(entry!!) else null,
                sourceSheet = extras.sheet?.takeIf { merged }?.let { action ->
                    val sizes = extras.copySizes + listOfNotNull(live.details?.downloadSizeBytes?.let { bookId to it })
                    val statuses = env.rows.associate { it.first to it.second.status }
                    SourceSheetUi(action, if (action == SourceAction.PLAY) rows else downloadRows(entry!!, rows, context!!, sizes, statuses))
                },
                canSplit = merged && merges != null,
                canMerge = context != null && entry != null && merges != null,
                mergeSheet = extras.mergeSheet,
                playingFrom = otherCopy?.let { copy ->
                    val name = extras.serverNames[copy.book.serverId]
                    "Playing from ${copy.book.source.shortLabel()}" + (if (name.isNullOrBlank()) "" else " · $name")
                },
                notice = extras.notice,
                entry = if (merged) entry else null,
                mergeContext = context,
                favourite = entry?.let { isFavourite(it, favouriteIds) } ?: (bookId in favouriteIds),
                favouriteCopyIds = entry?.let(::favouriteRowIds) ?: listOf(bookId),
                // The entry's progress is the copy played last, as the tiles show it.
                mark = markActionFor(entry?.progress ?: progress),
                inUpNext = (entry?.let(::favouriteRowIds) ?: listOf(bookId)).let { copies -> marks.queue.any { it in copies } },
                inPlayer = loaded,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailsUiState())

    init {
        if (!quickActions) loadJob = viewModelScope.launch { fetchIfCached() }
        // When the player error goes away, a dismissal is forgotten so the same text can show again.
        viewModelScope.launch {
            controller.state.map { it.error }.distinctUntilChanged().collect { error ->
                val now = local.value
                if (error == null && now.dismissedPlayerError != null) local.value = Local(now.starting, now.error)
            }
        }
    }

    /** The play control: starts, pauses or resumes this book. */
    fun onResumeClicked() {
        if (local.value.starting) return
        val state = uiState.value
        val book = state.book ?: return
        when (state.playback) {
            DetailsPlayback.STARTING -> Unit
            DetailsPlayback.PLAYING -> controller.pause()
            DetailsPlayback.PAUSED -> {
                if (!state.canPlay) return
                local.value = Local()
                // A book that played to its end starts again from the beginning of the whole book (only this
                // page's copy: another copy's position is not measured against this book's length).
                if (state.playingFrom == null && isFinishedAt(state.currentPositionMs, book.durationMs)) controller.seekTo(0L)
                controller.play()
            }
            DetailsPlayback.NOT_PLAYING -> if (state.canPlay) playChosenCopy(state, book)
        }
    }

    // A merged book plays a downloaded copy, else the copy the "Play from" choice picks, or asks with the source sheet.
    private fun playChosenCopy(state: BookDetailsUiState, book: AudioBook) {
        val entry = state.entry
        val context = state.mergeContext
        if (entry == null || context == null) return start(book, state.resumePositionMs)
        val chosen = chosenPlaySource(entry, context.playChoice, context.onlineServerIds)
        when {
            chosen != null -> playCopy(state, chosen.book.id)
            // No copy can play: say why instead of a sheet of greyed rows.
            state.sources.none { it.enabled } -> noCopyText(state).let { text ->
                local.value = Local(error = text)
                report(text)
            }
            else -> sourceSheet.value = SourceAction.PLAY
        }
    }

    private fun noCopyText(state: BookDetailsUiState): String = noCopyText(state.sources.firstNotNullOfOrNull { it.reason })

    /*
     * A chapter tap with nothing playing: this page's chapters, started in the copy Play would pick, else
     * this page's copy, else any copy that can play (the copies' lengths agree within 2%).
     */
    private fun startAtChapter(state: BookDetailsUiState, book: AudioBook, startMs: Long) {
        val entry = state.entry
        val context = state.mergeContext
        if (entry == null || context == null) return start(book, startMs)
        val usable = state.sources.filter { it.enabled }.map { it.bookId }
        val target = chosenPlaySource(entry, context.playChoice, context.onlineServerIds)
            ?: entry.member(bookId)?.takeIf { it.book.id in usable }
            ?: entry.sources.firstOrNull { it.book.id in usable }
        if (target == null) local.value = Local(error = noCopyText(state)) else start(target.book, startMs)
    }

    private fun playCopy(state: BookDetailsUiState, copyId: String) {
        val book = state.book ?: return
        if (copyId == bookId) return start(book, state.resumePositionMs)
        val copy = state.entry?.allSources?.firstOrNull { it.book.id == copyId } ?: return
        // Another copy starts where this phone left off in that copy (progress is not mirrored between copies yet).
        start(copy.book, null)
    }

    /** A row of the source sheet was chosen. */
    fun onSourceChosen(copyId: String) {
        val state = uiState.value
        val sheet = state.sourceSheet ?: return
        val row = sheet.rows.firstOrNull { it.bookId == copyId } ?: return
        if (!row.enabled) return
        sourceSheet.value = null
        when (sheet.action) {
            SourceAction.PLAY -> if (!local.value.starting) playCopy(state, copyId)
            // A paused or failed download of that copy goes on where it stopped.
            SourceAction.DOWNLOAD -> downloadAction(askNotifications = true, done = if (row.resumes) DOWNLOAD_RESUMED else DOWNLOAD_STARTED) {
                if (row.resumes) downloadController.resume(copyId) else downloadController.enqueue(copyId)
            }
        }
    }

    fun onSourceSheetDismissed() {
        sourceSheet.value = null
    }

    /** "Not the same book": this copy leaves the merged book, for good. */
    fun onNotSameBookClicked() {
        val state = uiState.value
        val repo = merges ?: return
        if (!state.canSplit) return
        val others = state.entry?.allSources?.map { it.book.id }.orEmpty() - bookId
        mergeAction { repo.split(bookId, others) }
    }

    /** "Merge with": opens the search list of the other books. */
    fun onMergeWithClicked() {
        if (uiState.value.canMerge) mergeQuery.value = ""
    }

    fun onMergeQueryChanged(text: String) {
        if (mergeQuery.value != null) mergeQuery.value = text
    }

    fun onMergeSheetDismissed() {
        mergeQuery.value = null
    }

    /**
     * The chosen book is the same book as this one from now on: both groups join (their earlier "Not the same book"
     * choices between each other are forgotten). When the join still cannot happen, a notice says so.
     */
    fun onMergeCandidateChosen(otherId: String) {
        val repo = merges ?: return
        val state = uiState.value
        val candidate = state.mergeSheet?.candidates?.firstOrNull { it.bookId == otherId } ?: return
        val ownIds = state.entry?.allSources?.map { it.book.id } ?: listOf(bookId)
        mergeQuery.value = null
        mergeAction {
            repo.merge(bookId, otherId, ownIds, candidate.memberIds)
            val joined = withTimeoutOrNull(MERGE_WAIT_MS) { whole.first { it.entry?.hasCopy(otherId) == true } }
            if (joined == null) notice.value = MERGE_FAILED_TEXT
        }
    }

    fun onNoticeDismissed() {
        notice.value = null
    }

    private fun mergeAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing changes; the person can try again.
            }
        }
    }

    /** Jumps to a chapter of the loaded book, or starts the book at that chapter. */
    fun onChapterClicked(index: Int) {
        if (local.value.starting) return
        val state = uiState.value
        val book = state.book ?: return
        val row = state.chapters.getOrNull(index) ?: return
        if (!chapterTapsEnabled(state.playback, state.canPlay) || state.playingFrom != null) return
        when (state.playback) {
            DetailsPlayback.PLAYING, DetailsPlayback.PAUSED -> controller.seekTo(row.startMs)
            DetailsPlayback.NOT_PLAYING -> startAtChapter(state, book, row.startMs)
            DetailsPlayback.STARTING -> Unit
        }
    }

    fun dismissPlayError() {
        if (local.value.starting) return
        // A start failure is cleared; a player error is remembered as dismissed.
        val playerError = uiState.value.playError.takeIf { local.value.error == null }
        local.value = Local(dismissedPlayerError = playerError)
    }

    // `starting` is set before the coroutine runs, so a second tap cannot start a second load.
    private fun start(book: AudioBook, startMs: Long?) {
        local.value = Local(starting = true)
        viewModelScope.launch {
            local.value = try {
                starter.start(book, startMs).fold(
                    onSuccess = { Local() },
                    onFailure = { error ->
                        val text = error.message ?: PlaybackStartException.DEFAULT_MESSAGE
                        report(text)
                        Local(error = text)
                    }
                )
            } catch (e: CancellationException) {
                local.value = Local()
                throw e
            }
        }
    }

    /** Download, or Try again after a failure. Notifications are asked for first (once); the download starts whatever the answer. */
    fun onDownloadClicked() {
        val download = uiState.value.download
        if (!download.canStart) return
        when (download.phase) {
            DetailsDownloadPhase.IDLE -> startDownload()
            DetailsDownloadPhase.FAILED -> downloadAction(askNotifications = true, done = DOWNLOAD_RETRIED) { downloadController.resume(downloadBookId()) }
            else -> Unit
        }
    }

    /*
     * A merged book downloads the copy the "Download from" choice picks (when its server answers), or asks with the
     * "Download from" sheet, which shows each copy's size once its server says.
     */
    private fun startDownload() {
        val state = uiState.value
        val entry = state.entry
        val context = state.mergeContext
        val copies = entry?.let(::downloadableMembers).orEmpty()
        if (entry == null || context == null || copies.size < 2) {
            downloadAction(askNotifications = true, done = DOWNLOAD_STARTED) { downloadController.enqueue(downloadBookId()) }
            return
        }
        val chosen = chosenDownloadSource(entry.copy(sources = copies), context.downloadChoice, context.onlineServerIds)
        if (chosen == null) openDownloadSheet(copies) else downloadAction(askNotifications = true, done = DOWNLOAD_STARTED) { downloadController.enqueue(chosen.book.id) }
    }

    private fun openDownloadSheet(copies: List<BrowseItem>) {
        sourceSheet.value = SourceAction.DOWNLOAD
        loadCopySizes(copies.map { it.book.id } - bookId)
    }

    /** "Download another copy" next to a finished download: the sheet, with the copy on the phone shown as downloaded. */
    fun onDownloadAnotherCopyClicked() {
        val state = uiState.value
        if (!state.download.canDownloadAnotherCopy) return
        val copies = state.entry?.let(::downloadableMembers).orEmpty()
        if (copies.size >= 2) openDownloadSheet(copies)
    }

    // The other copies' sizes for the "Download from" sheet; best effort, a copy whose server does not answer shows none.
    private fun loadCopySizes(ids: List<String>) {
        for (id in ids) {
            if (id in copySizes.value) continue
            viewModelScope.launch {
                val size = try {
                    books.loadDetails(id).getOrNull()?.downloadSizeBytes
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (size != null && size > 0L) copySizes.value = copySizes.value + (id to size)
            }
        }
    }

    // The copy the main download control shows (see downloadRowsOfCopies); this page's book until the state is known.
    private fun downloadBookId(): String = uiState.value.downloadBookId.ifEmpty { bookId }

    // The control of copy [id]: the main one or another copy's.
    private fun controlOf(id: String): DetailsDownload? {
        val state = uiState.value
        if (id == downloadBookId()) return state.download
        return state.otherCopyDownloads.firstOrNull { it.bookId == id }?.download
    }

    fun onPauseDownloadClicked() = onCopyPauseClicked(downloadBookId())
    fun onResumeDownloadClicked() = onCopyResumeClicked(downloadBookId())
    fun onCancelDownloadClicked() = onCopyCancelClicked(downloadBookId())
    fun onRemoveDownloadClicked() = onCopyRemoveClicked(downloadBookId())

    /** Pause on the control of copy [id]. */
    fun onCopyPauseClicked(id: String) {
        val phase = controlOf(id)?.phase
        if (phase == DetailsDownloadPhase.QUEUED || phase == DetailsDownloadPhase.DOWNLOADING) {
            downloadAction(done = DOWNLOAD_PAUSED) { downloadController.pause(id) }
        }
    }

    fun onCopyResumeClicked(id: String) {
        if (controlOf(id)?.phase == DetailsDownloadPhase.PAUSED) downloadAction(done = DOWNLOAD_RESUMED) { downloadController.resume(id) }
    }

    /** Try again on the failed download of another copy (the main control's is [onDownloadClicked]). */
    fun onCopyRetryClicked(id: String) {
        val control = controlOf(id) ?: return
        if (control.phase == DetailsDownloadPhase.FAILED && control.canStart) {
            downloadAction(askNotifications = true, done = DOWNLOAD_RETRIED) { downloadController.resume(id) }
        }
    }

    /** Stops a download of copy [id] that has not finished and deletes what it fetched. */
    fun onCopyCancelClicked(id: String) {
        when (controlOf(id)?.phase) {
            DetailsDownloadPhase.QUEUED, DetailsDownloadPhase.DOWNLOADING, DetailsDownloadPhase.PAUSED, DetailsDownloadPhase.FAILED ->
                downloadAction(done = DOWNLOAD_CANCELLED) { downloadController.cancel(id) }
            else -> Unit
        }
    }

    /** "Remove download" on the finished download of copy [id]: asks first, then removes that copy only. */
    fun onCopyRemoveClicked(id: String) {
        if (controlOf(id)?.phase == DetailsDownloadPhase.DONE) confirmingRemove.value = id
    }

    fun onRemoveDownloadConfirmed() {
        val id = confirmingRemove.value ?: return
        confirmingRemove.value = null
        downloadAction(done = DOWNLOAD_REMOVED) { downloadController.remove(id) }
    }

    fun onRemoveDownloadDismissed() {
        confirmingRemove.value = null
    }

    // The table is the truth the control follows, so a failed call simply leaves the control as it was.
    private fun downloadAction(askNotifications: Boolean = false, done: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (askNotifications) {
                try {
                    notifications.ensure()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // No notification then; the download runs anyway.
                }
            }
            try {
                block()
                done?.let(::report)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The control keeps showing the stored state; the person can tap again.
                report(DOWNLOAD_ERROR)
            }
        }
    }

    /** The heart: marks the book (every copy of a merged book) as a favourite, or unmarks every copy when any is one. */
    fun onFavouriteClicked() {
        val state = uiState.value
        if (state.book == null) return
        val ids = state.favouriteCopyIds.ifEmpty { listOf(bookId) }
        viewModelScope.launch {
            try {
                report(if (favourites.toggle(ids)) FAVOURITE_ADDED else FAVOURITE_REMOVED)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                report(FAVOURITE_ERROR)
            }
        }
    }

    /**
     * Mark as finished / not finished, as the player's menu does it: [BookRepository.markFinished] on the copy whose
     * progress the entry shows (it marks the other copies too); the result is reported for a snackbar.
     */
    fun onMarkClicked() {
        val state = uiState.value
        if (state.book == null) return
        val finished = state.mark == MarkAction.MarkFinished
        val target = markTarget(state.entry, bookId)
        viewModelScope.launch {
            val result = try {
                books.markFinished(target, finished)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Result.failure(IllegalStateException())
            }
            report(result.fold(onSuccess = { markResultText(finished, it) }, onFailure = { MARK_ERROR_TEXT }))
        }
    }

    /**
     * "Play next" ([front]) or "Add to Up next": queues the whole entry (every copy of a merged book counts as one); the
     * book in the player is never queued. The result is reported for a snackbar, on the page as in the long-press menu.
     */
    fun onUpNextClicked(front: Boolean) {
        val state = uiState.value
        if (state.book == null) return
        val copies = state.entry?.let(::favouriteRowIds) ?: listOf(bookId)
        // The page's own copy is the row stored, so the queue shows the book as it was chosen.
        val ids = listOf(bookId) + copies.filter { it != bookId }
        val loadedId = controller.state.value.bookId
        viewModelScope.launch {
            try {
                var outcome = UpNextOutcome.ALREADY_QUEUED
                upNext.edit { queue -> editUpNext(queue, ids, loadedId, front).also { outcome = it.outcome }.queue }
                announce(upNextOutcomeText(outcome))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                announce(UP_NEXT_ERROR_TEXT)
            }
        }
    }

    /** "Remove from Up next": every row of the entry leaves the queue. */
    fun onRemoveFromUpNextClicked() {
        val state = uiState.value
        if (state.book == null) return
        val copies = state.entry?.let(::favouriteRowIds) ?: listOf(bookId)
        viewModelScope.launch {
            try {
                upNext.edit { queue -> removeFromUpNext(queue, copies + bookId) }
                announce(UP_NEXT_REMOVED_TEXT)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                announce(UP_NEXT_ERROR_TEXT)
            }
        }
    }

    /** The long-press menu's Play / Resume / Pause (the Play rule of this page). True when it opened the "Play from" sheet. */
    fun onQuickPlay(): Boolean {
        // A sheet left from an earlier menu (it never showed) must not pass for one this tap opened.
        sourceSheet.value = null
        onResumeClicked()
        return sourceSheet.value == SourceAction.PLAY
    }

    /** The long-press menu's Download / Try again. True when it opened the "Download from" sheet. */
    fun onQuickDownload(): Boolean {
        sourceSheet.value = null
        onDownloadClicked()
        return sourceSheet.value == SourceAction.DOWNLOAD
    }

    /** "Download another copy" in the long-press menu: the "Download from" sheet. True when it opened. */
    fun onQuickDownloadAnotherCopy(): Boolean {
        sourceSheet.value = null
        onDownloadAnotherCopyClicked()
        return sourceSheet.value == SourceAction.DOWNLOAD
    }

    fun onMessageShown() {
        _message.value = null
    }

    // Only the long-press menu reports results; the page shows them in its own controls.
    private fun report(text: String) {
        if (quickActions) _message.value = text
    }

    // Up next has no control of its own that shows the result, so the page gets the snackbar too.
    private fun announce(text: String) {
        _message.value = text
    }

    fun selectTab(tab: DetailsTab) {
        selectedTab.value = tab
    }

    fun retry() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { fetchIfCached() }
    }

    // An unknown or deleted book never asks the server.
    private suspend fun fetchIfCached() {
        // `whole` already runs on the injected dispatcher, so the lookup stays off the main thread.
        if (whole.first().book != null) fetch()
    }

    private suspend fun fetch() {
        live.value = Live(live.value.details, loading = true, error = null)
        val result = try {
            books.loadDetails(bookId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        live.value = result.fold(
            onSuccess = { Live(it ?: live.value.details) },
            onFailure = { Live(live.value.details, error = errorText(it)) }
        )
    }

    private fun errorText(error: Throwable) =
        if (error is SignInRequiredException) "Could not load details. Sign in again in Settings."
        else "Could not load details. Showing what is saved."

    private companion object {
        const val MERGE_WAIT_MS = 2_000L
        const val MERGE_FAILED_TEXT = "These books could not be shown as one"
        const val FAVOURITE_ADDED = "Added to favourites"
        const val FAVOURITE_REMOVED = "Removed from favourites"
        const val FAVOURITE_ERROR = "Could not change the favourites. Try again."
        const val DOWNLOAD_STARTED = "Download started"
        const val DOWNLOAD_RETRIED = "Trying the download again"
        const val DOWNLOAD_PAUSED = "Download paused"
        const val DOWNLOAD_RESUMED = "Download resumed"
        const val DOWNLOAD_CANCELLED = "Download cancelled"
        const val DOWNLOAD_REMOVED = "Download removed"
        const val DOWNLOAD_ERROR = "Could not change the download. Try again."
    }
}
