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
import com.chapterplayer.domain.browse.chosenSource
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

    /** The merging inputs, the server names, the open source sheet and the "Merge with" search (null: closed). */
    private class Extras(
        val context: MergeContext?,
        val serverNames: Map<String, String>,
        val sheet: SourceAction?,
        val mergeQuery: String?
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
        val download: DownloadRow?,
        val downloadBookId: String,
        val confirmingRemove: Boolean
    )

    private val live = MutableStateFlow(Live())
    private val local = MutableStateFlow(Local())
    private val selectedTab = MutableStateFlow(DetailsTab.CHAPTERS)
    private val confirmingRemove = MutableStateFlow(false)
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
    private val mergeQuery = MutableStateFlow<String?>(null)

    private val extras = combine(
        merging.context,
        servers?.observeAll()?.map { list -> list.associate { it.id to it.name } } ?: flowOf(emptyMap()),
        sourceSheet,
        mergeQuery
    ) { context, names, sheet, query -> Extras(context, names, sheet, query) }

    /*
     * The copy the download control is about: one that already has a download (of any member), otherwise this page's
     * book when it is an Audiobookshelf book, otherwise the first Audiobookshelf copy of a merged book.
     */
    private val downloadTarget = whole
        .map { w -> w.entry?.let(::downloadableMembers)?.map { it.book.id }.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { absIds ->
            val ids = (listOf(bookId) + absIds).distinct()
            combine(ids.map { id -> overview.observeBook(id).map { row -> id to row } }) { rows ->
                rows.firstOrNull { it.second != null }
                    ?: ((if (bookId in absIds || absIds.isEmpty()) bookId else absIds.first()) to null)
            }
        }

    private val downloadRow = combine(downloadTarget, confirmingRemove) { target, confirming -> target to confirming }

    private val env = combine(
        controller.state, local, connectivity.isOnline, settings.downloadedOnly, downloadRow
    ) { player, local, online, downloadedOnly, download ->
        Env(player, local, online, downloadedOnly, download.first.second, download.first.first, download.second)
    }

    private val wholeAndExtras = combine(whole, extras) { w, x -> w to x }

    val uiState: StateFlow<BookDetailsUiState> = combine(
        wholeAndExtras, books.observeProgress(bookId), live, selectedTab, env
    ) { (whole, extras), progress, live, tab, env ->
        val book = whole.book
        if (book == null) {
            BookDetailsUiState(phase = DetailsPhase.NOT_FOUND, selectedTab = tab)
        } else {
            val chapters = live.details?.chapters ?: book.chapters
            val timeLeft = when {
                progress == null -> null
                progress.isFinished || progress.positionMs >= book.durationMs -> "Finished"
                progress.positionMs > 0 -> formatTimeLeft(book.durationMs, progress.positionMs)
                else -> null
            }
            val entry = whole.entry
            val context = extras.context
            val merged = entry != null && entry.isMerged && context != null
            val memberIds = entry?.allSources?.map { it.book.id }.orEmpty()
            // Another copy of this book playing counts as this book playing (Pause, Open player).
            val loaded = env.player.bookId == bookId || (merged && env.player.bookId in memberIds)
            val playback = when {
                env.local.starting -> DetailsPlayback.STARTING
                // Buffering before the first sound still counts as playing: Pause is offered at once.
                loaded && (env.player.isPlaying || (env.player.isBuffering && env.player.playWhenReady)) -> DetailsPlayback.PLAYING
                loaded -> DetailsPlayback.PAUSED
                else -> DetailsPlayback.NOT_PLAYING
            }
            val canPlay = book.serverId.isNotBlank() && (
                isPlayable(whole.downloadState, env.online, env.downloadedOnly) ||
                    (merged && entry!!.sources.any { isPlayable(it.downloadState, env.online, env.downloadedOnly) })
                )
            val options = if (merged) sourceOptions(entry!!, context!!.onlineServerIds, context.deviceOnline) else emptyList()
            val rows = options.map { sourceRow(it, extras.serverNames) }
            val downloadSource = entry?.allSources?.firstOrNull { it.book.id == env.downloadBookId }?.book?.source ?: book.source
            // While this book is loaded in the player the highlight follows the live position.
            val shown = if (loaded) ReadingProgress(bookId, env.player.positionMs, book.durationMs, 0L) else progress
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
                lengthText = formatLength(book.durationMs),
                loadingDetails = live.loading,
                detailsLoaded = live.details != null,
                error = live.error,
                selectedTab = tab,
                playback = playback,
                canPlay = canPlay,
                cannotPlayReason = when {
                    canPlay -> null
                    else -> NEEDS_CONNECTION_TEXT
                },
                playError = env.local.error
                    ?: env.player.error?.takeIf { loaded && it != env.local.dismissedPlayerError },
                currentPositionMs = if (loaded) env.player.positionMs else progress?.positionMs ?: 0L,
                resumePositionMs = savedStart,
                download = detailsDownload(
                    downloadSource, env.download, env.online,
                    knownSizeBytes = live.details?.downloadSizeBytes ?: env.download?.totalBytes,
                    confirmingRemove = env.confirmingRemove
                ),
                downloadBookId = env.downloadBookId,
                sources = rows,
                sourcesDescription = if (merged) sourcesDescription(entry!!) else null,
                sourceSheet = extras.sheet?.takeIf { merged }?.let { action ->
                    SourceSheetUi(action, if (action == SourceAction.PLAY) rows else downloadRows(entry!!, rows, context!!))
                },
                canSplit = merged && merges != null,
                canMerge = context != null && entry != null && merges != null,
                mergeSheet = extras.mergeQuery?.takeIf { context != null && entry != null }?.let { query ->
                    MergeSheetUi(query, mergeCandidates(whole.all, entry!!, query).map { candidateRow(it, extras.serverNames) })
                },
                entry = if (merged) entry else null,
                mergeContext = context
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailsUiState())

    init {
        loadJob = viewModelScope.launch { fetchIfCached() }
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
                // A book that played to its end starts again from the beginning of the whole book.
                if (isFinishedAt(state.currentPositionMs, book.durationMs)) controller.seekTo(0L)
                controller.play()
            }
            DetailsPlayback.NOT_PLAYING -> if (state.canPlay) playChosenCopy(state, book)
        }
    }

    // A merged book plays the copy the Settings choice picks, or asks with the source sheet.
    private fun playChosenCopy(state: BookDetailsUiState, book: AudioBook) {
        val entry = state.entry
        val context = state.mergeContext
        if (entry == null || context == null) return start(book, state.resumePositionMs)
        val chosen = chosenSource(entry, context.preference, context.onlineServerIds)
        if (chosen == null) sourceSheet.value = SourceAction.PLAY else playCopy(state, chosen.book.id)
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
            SourceAction.DOWNLOAD -> downloadAction(askNotifications = true) { downloadController.enqueue(copyId) }
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

    /** The chosen book is the same book as this one from now on. */
    fun onMergeCandidateChosen(otherId: String) {
        val repo = merges ?: return
        val sheet = uiState.value.mergeSheet ?: return
        if (sheet.candidates.none { it.bookId == otherId }) return
        mergeQuery.value = null
        mergeAction { repo.merge(bookId, otherId) }
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
        if (!chapterTapsEnabled(state.playback, state.canPlay)) return
        when (state.playback) {
            DetailsPlayback.PLAYING, DetailsPlayback.PAUSED -> controller.seekTo(row.startMs)
            DetailsPlayback.NOT_PLAYING -> start(book, row.startMs)
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
                    onFailure = { Local(error = it.message ?: PlaybackStartException.DEFAULT_MESSAGE) }
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
            DetailsDownloadPhase.FAILED -> downloadAction(askNotifications = true) { downloadController.resume(downloadBookId()) }
            else -> Unit
        }
    }

    // A merged book with several Audiobookshelf copies downloads the one the Settings choice picks, or asks.
    private fun startDownload() {
        val state = uiState.value
        val entry = state.entry
        val context = state.mergeContext
        val copies = entry?.let(::downloadableMembers).orEmpty()
        if (entry == null || context == null || copies.size < 2) {
            downloadAction(askNotifications = true) { downloadController.enqueue(downloadBookId()) }
            return
        }
        val chosen = chosenSource(entry.copy(sources = copies), context.preference, context.onlineServerIds)
        if (chosen == null) sourceSheet.value = SourceAction.DOWNLOAD
        else downloadAction(askNotifications = true) { downloadController.enqueue(chosen.book.id) }
    }

    // The copy the download control shows (see downloadTarget); this page's book until the state is known.
    private fun downloadBookId(): String = uiState.value.downloadBookId.ifEmpty { bookId }

    fun onPauseDownloadClicked() {
        val phase = uiState.value.download.phase
        if (phase == DetailsDownloadPhase.QUEUED || phase == DetailsDownloadPhase.DOWNLOADING) {
            downloadAction { downloadController.pause(downloadBookId()) }
        }
    }

    fun onResumeDownloadClicked() {
        if (uiState.value.download.phase == DetailsDownloadPhase.PAUSED) downloadAction { downloadController.resume(downloadBookId()) }
    }

    /** Stops a download that has not finished and deletes what it fetched. */
    fun onCancelDownloadClicked() {
        when (uiState.value.download.phase) {
            DetailsDownloadPhase.QUEUED, DetailsDownloadPhase.DOWNLOADING, DetailsDownloadPhase.PAUSED, DetailsDownloadPhase.FAILED ->
                downloadAction { downloadController.cancel(downloadBookId()) }
            else -> Unit
        }
    }

    /** "Remove download" on a finished download: asks first. */
    fun onRemoveDownloadClicked() {
        if (uiState.value.download.phase == DetailsDownloadPhase.DONE) confirmingRemove.value = true
    }

    fun onRemoveDownloadConfirmed() {
        if (!confirmingRemove.value) return
        confirmingRemove.value = false
        downloadAction { downloadController.remove(downloadBookId()) }
    }

    fun onRemoveDownloadDismissed() {
        confirmingRemove.value = false
    }

    // The table is the truth the control follows, so a failed call simply leaves the control as it was.
    private fun downloadAction(askNotifications: Boolean = false, block: suspend () -> Unit) {
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The control keeps showing the stored state; the person can tap again.
            }
        }
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
}
