# Browsing, favourites, selection, merging and source choice

Library views, filters, A to Z scroller, favourites, selection mode, one entry per book across servers, progress mirroring between copies.

Detail moved out of `CLAUDE.md` (review R8); keep it current when this area changes.

## Browsing and filters

- Browsing: pure rules live in `domain/browse` (filters, sort, search, grouping by author/narrator/series/collection, offline rules; tested without Android).

## Favourites and the long-press menu

- Favourites (local only: Audiobookshelf has no favourites, so they do not follow the person to another device) are the `favourites` table (Room v14, auto-migration; bookId, addedAt) behind `FavouritesRepository` (`data/favourites`, commonMain, Koin single in `commonServerModule`; `NoFavourites` / `InMemoryFavourites` for tests);
  - rules in `domain/browse/Favourites.kt`: an entry is a favourite when ANY copy's id is stored (`isFavourite`), marking and unmarking write every copy's id (`favouriteRowIds`, `toggle` removes all when any is set), `favouritesShelf` (Home, by title, hidden when empty);
  - `BrowseQuery.favouritesOnly` (the Favourites chip after All and the Filter sheet's "Favourites only", kept across launches with the other filters) takes the ids through `applyQuery(items, query, favouriteIds)` in every browse view model;
  - a removed server's rows go with it, rows of books that only vanished are ignored.
- Book details has a heart under the download control.
- The book long-press menu:
  - `Modifier.bookPress` (`ui/components/BookPress.kt`: `combinedClickable`, haptics through `LocalHaptics.longPress`, a "More options" custom accessibility action) on tiles and rows of Library, Home (Resume card and every shelf), Search and group pages opens `BookActionsHost` / `BookActionsSheet` (`ui/details`),
  - which runs on a `BookDetailsViewModel` built with `parametersOf(bookId, true)` (`quickActions`: no details fetch, results through `message` for the screen's snackbar; one per long-pressed book in the screen's store),
  - so Play, the download actions, the source sheets and offline/platform rules are the details page's own;
  - the rows are pure (`bookActionItems`);
  - Mark as finished uses `BookRepository.markFinished` on the entry's copy played last and the shared `markResultText`.

## Libraries, collections and sync

- `libraries`, `collections` and `collection_books` tables hold the server's library list and collections (Room v3, auto-migration).
- `LibrarySyncCoordinator` (`data/sync`, an app-scoped Koin single) is the only thing that syncs: it keeps one sync per server at a time, queues one follow-up when libraries change, and survives cancellation.
- Library views, layouts and filters are in `ui/library`; the Libraries check-list is `LibrariesSheet`.

## Server filter, scroller and sort keys

- `BrowseQuery.serverIds` filters by server:
  - empty = all;
  - rules in `domain/browse/ServerFilter.kt`;
  - `BrowseQueryStore` follows the server list so deleted servers drop out, and the choice is dropped below two servers;
  - the whole query (progress, download, sort, servers, favourites only; never the search text) is kept across launches:
    - `BrowseQueryStore.persist` restores `AppSettings.browseFilters` at start unless the person changed something first and saves each change
    - (the lists read `settledQuery`, which waits for that read, at most `BROWSE_RESTORE_WAIT_MS` = 1.5 s, so the defaults never flash; the Library view waits the same way),
    - as a small versioned JSON object (`domain/browse/BrowseQueryCodec.kt`, read field by field: garbage, unknown and removed values fall back to defaults; stored servers that no longer exist are pruned as before),
    - and the Library tab's view (Library, Authors, ...) is `AppSettings.libraryView` (the saved state still wins after a process death);
  - "Clear filters" is the Filter sheet's Reset and the empty-state button;
  - Home and Search show a hint whenever any filter is on: "Server filter on" / "Show all servers" for a server filter alone, else "Filters on" / "Clear filters" (`filterHintFor`, `withoutFilters` keeps the sort; `ui/components/FilterHintRow.kt`), and their empty states say filters may be hiding books with a Clear filters button
- The A to Z scroller is `ui/components/FastScroller` over rules in `domain/browse/FastScroll.kt`:
  - index from the list as shown;
  - offered by `fastScrollKeys` for Title/Author sorts and the Authors, Narrators, Series and Collections lists, 20 items or more;
  - `groupByCollection` lists collections by name with the title rule, equal names by key, each collection keeping its own book order, also in the car
  - haptics go through `LocalHaptics` (no-op default).
- Alphabetical keys live in `domain/browse/AlphaKeys.kt`: accents fold to their letter, titles skip a leading article and names do not (`titleLetterOf`/`personLetter`, with matching sort keys), and the '#' group sorts as one block first; the car's `titleLetter` uses the same rule.

## One entry per book (merging)

- A book several servers have is ONE entry:
  - `domain/browse/MergedSources.kt`:
    - `mergeDuplicates`: same normalised title and first author, lengths within 2% or 5 min, never two books of one server;
    - `BrowseItem.sources` holds the members, primary first: the copy `chosenPlaySource` picks when it decides, else downloaded, the play choice's copy when reachable, reachable, the play choice's copy, ABS;
    - series, author and narrator come from any member, ABS first;
    - the entry's progress is the member played last, and Android Auto, the widgets and the notification (which cannot ask) resume that copy via `resumeCopy`, except that a downloaded copy that can play is always resumed first (no stream when a copy is on the phone);
    - wherever the person taps Play or Resume in the app (Book details, Home's Resume card: `HomeViewModel.resume`) the Play rule applies: a downloaded copy at once, else the "Play from" choice's copy when it can play, else the same "Play from" sheet (`SourceSheet`, the copy played last marked "Last played"; with no copy able to play, the error says why);
    - car and widget ids resolve through `entryContaining`

## Selection mode and bulk actions

- Selection mode (host-tested only):
  - the Library book view and the group pages (not Home or Search) have "Select books" (Library: in the top bar's More menu with Statistics, so the bar keeps five buttons; group pages: a button; the checklist icon stays the Libraries sheet) and the long-press menu's first row "Select" (`BookActionKind.SELECT`, only where the host passes `onSelect`);
  - `BookSelectionViewModel` (`ui/library`, one per screen, fed the list as shown through `SelectionEffects`) holds the selection by primary id (`pruneSelection` keeps an entry selected through any copy's id and drops entries that left the list);
  - tiles use `selectionPress` (a check box with a state description) and `SelectionMark`;
  - `SelectionTopBar` shows "N selected", Close, Select all and the actions.
- Bulk rules are pure in `domain/browse/BulkActions.kt`:
  - each book goes through its own menu's calls:
    - `markTarget` = the copy played last, also used by `BookDetailsViewModel`;
    - a mark skips books already in that state, so Not finished never resets a book in progress;
    - favourites Add unless every selected book is one;
    - Up next through `editUpNext` in the order shown;
    - Download reads each copy's real `DownloadStatus` from `DownloadOverview` (not the list's DOWNLOADING-for-everything), resumes a paused or failed copy, queues only `chosenDownloadSource` copies (waiting up to 5 s for the merging context; without it merged books are skipped), and skips books needing a choice or with a copy done, queued or running;
    - Remove download every copy with a download row, finished or not;
    - `bulkNeedsConfirmation`: Remove download always, Mark as not finished above five books;
    - favourites in one repository write
  - `runBounded` runs them one at a time (marks `BULK_MARK_PARALLELISM` = 3) on the IO dispatcher, Cancel starts no further book, the top bar shows `bulkProgressText` (TalkBack hears only `bulkMilestoneText`: start and every tenth), the result is one `bulkResultText` snackbar and the mode closes;
  - Back closes it or cancels a running action.

## The merged list

- `MergedLibrary` (`data/merge`, an app-scoped Koin single) is the one merged list: Library, Home, Search, group pages, details, the player menu, Up next (sheet and advancer), stats, Diagnostics, the car and the widgets read its `items` (review R10).
- It is worked out once per change on the default dispatcher and shared (`shareIn`, `WhileSubscribed` with a 5 s stop timeout, replay 1, forgotten when the share stops, so a later `first()` never gets an old list).
- The merge is split in two (`domain/browse/MergedSources.kt`): `duplicateGroups(books, overrides)` decides which books are one entry (books and corrections only; the costly part: normalised titles, candidate pairs, union-find) and `MergeGroups.entries(items, playChoice, online)` builds each entry (primary, member order, newest progress; cheap).
- `mergedBrowseItems` keeps the groups while the books list is equal and the corrections are the same, so a progress save (every 10 s while playing), a download change or a source choice only rebuilds the entries;
  - `mergeDuplicates` is both steps at once and gives the same results as before (`MergedLibraryTest` compares them with the old code on synthetic libraries, counts merges per change for many readers, and times 5,000 books).
- `observeBrowseItems` stays as the cold, unshared form for tests; a view model built without Koin gets its own unshared `MergedLibrary`; `SourceMerging` (`data/merge`, Koin single in `browseModule`) supplies the corrections, the two source choices and the reachable servers (`reachableServerIds`).

## Corrections and which copy is used

- The user's "Not the same book" / "Merge with…" corrections are the `book_merges` table (Room v10, `BookMergeRepository`).
- Which copy is used is two settings (`domain/browse/SourceChoice.kt`: `SourceChoice` = `Ask`, `OfType(ABS|PLEX)` or `OfServer(id)`, stored as one string `ask` / `type:ABS` / `server:<id>`): `AppSettings.downloadSourceChoice` ("Download from") and `playSourceChoice` ("Play from (when not downloaded)"), both Ask by default;
  - the old single `source_preference` migrates once to both on the first save (Prefer ABS/Plex to that type, Ask and Prefer downloaded to Ask; read through until then).
- A downloaded copy ALWAYS plays first (`chosenPlaySource`: of several, the one played last); otherwise the play choice's copy when its server answers; `chosenDownloadSource` takes the download choice's copy when its server answers and it is not on the phone or on its way; anything else asks (no silent fallback).
- `SourceMerging` reads a removed server's choice as Ask.
- Servers go by `serverDisplayNames` (`domain/browse/ServerNames.kt`) in the sheets and settings: a blank name is the type, the same name gets the address host or `#n`, never an id or token.
- The details page lists Sources and asks through a source sheet; tiles show a "2 sources" pill.

## Unknown lengths (Plex)

- An unknown length (0: Plex album lists carry none) matches only when every author agrees, and a merged entry shows a known length.
- `PlexLengthFiller` (`data/sync`, started by `LibrarySyncCoordinator` after a Plex sync, in the background, one pass per server) fills unknown Plex lengths by summing each section's tracks (`type=10`, every page or nothing; only unknown lengths are written); the Servers page shows "Reading lengths… N%".

## Progress mirroring between copies

- Progress is mirrored between copies (Phase 2):
  - pure rules in `domain/playback/MirrorProgress.kt` (`mirroredProgress`: map by fraction of the whole book, whole seconds, never past the end, a finished copy mirrors as finished; newest wins, a copy's own row as new or within 15 s (an echo) stays; a mirrored row never mirrors on; `mirrorGroups` = the same grouping as the lists with the corrections, so a split stops it).
- `ProgressMirrorPeers` (`data/merge`, `MergedProgressPeers`, reads the corrections and the `AppSettings.mirrorProgress` switch on every call, default on) gives a book's other copies.
- `BookRepositoryImpl.savePlaybackProgress` and `saveServerProgress` write the other copies' local rows in the same lock and transaction, flagged `progress.mirrored` (Room v12, auto-migration), never the copy loaded in the player (`loadedBookId`) nor a copy whose row is the user's finished mark of this session (`userMarks`, so the pin holds);
  - `markFinished` marks the book locally first (its failure is the result, no other copy is touched), then the other copies locally, then every server at once, each waited for at most 8 s on the wall clock (then queued);
  - `MarkOutcome.PARTIAL` / `PARTIAL_QUEUED` when a copy failed (`combinedMarkOutcome`).
- `saveServerProgress` stores a server time in the future as now and keeps a mirrored row the server only echoes (`mergeServerProgress`); a queued mirror older than the server's progress (a future server time counts as now) is removed so the merge goes ahead (`mirrorRowYields`).
- `ProgressMirrorPusher` (`data/progress`, started by the Application) queues the mapped position in `pending_progress` for the other copies with `mirrorOf` = the source copy (Room v13): at most once per 60 s per copy while playing, once on a real pause/stop/book change (buffering does not count);
  - while the source copy is finished it sends `markedFinished = true` once instead of a position;
  - never the loaded copy or a server whose last sync failed (`unreachableServerIds`);
  - no session on the other server.
- The flusher drops a queued mirror (`mirrorFlushDecision`) when
  - the pair is no longer grouped (split or setting off: at once, even offline, through `ProgressMirrorPeers.changes`),
  - when the copy has newer listening here,
  - when the source copy moved elsewhere,
  - or when the source copy's server holds listening the phone does not have (a server holding the phone's own place and finished state is the phone's own delivery, not news);
  - it leaves a mirror aimed at the loaded book waiting (`loadedBookId`), and when `peersOfAll` returns null (lookup failed) it neither drops nor sends.
- `ProgressSyncer`'s live sync removes a queued mirror of its book, finished or not.
- Coalescing keeps the user's own mark and keeps a row a mirror only when both rows are mirrors (a copy's own queued listening never becomes one); a mirrored finished state yields to newer rows.
- `PlaybackStarter` weighs the other copies' own rows and, online, their server progress (in parallel, same 4 s wait), mapped (`progressOnCopy`, `newestAcrossCopies`).
- On a tie in `lastPlayedAt` a merged entry's progress is the copy's own row, not a mirrored one.
- Server merges mirror locally only (no push).
- Bookmarks and history stay per copy.
- Host-tested only

## Known limits

Accepted in a review and left as they are; each project review decides for each line: fix, accept or drop (review R40).

- Progress mirroring still groups copies by itself, apart from the merged library the lists share (R10 review).
- A list read by the car or a widget can be a few milliseconds behind a write (R10 review).
