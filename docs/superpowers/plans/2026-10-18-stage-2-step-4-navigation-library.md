# Stage 2 step 4: Navigation shell and Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Four tabs (Home, Library, Downloads, Search), a Library with five views, three layouts, filter chips and a Filter sheet, a combined view with a Libraries check-list, and the downloaded-state and offline UI (always "not downloaded" until Stage 3).

**Architecture:** Books gain the fields the browse views need (library, added date, series) in Room version 3. Authors, Narrators and Series are derived on the phone from the cached books by pure functions (so they also work offline and for Plex); Collections are fetched from Audiobookshelf at sync. A `libraries` table remembers every library a server has (including turned-off ones) so the Libraries check-list can turn them back on; the check-list changes only the stored exclusions. Filtering, sorting and grouping are pure functions in `commonMain` with unit tests; screens only render their result.

**Tech Stack:** Compose Multiplatform 1.12 (JetBrains navigation), Koin, Room (KSP, AutoMigration), Ktor + kotlinx.serialization, Coil 3, `AppSettings` (DataStore) for the layout choice.

**Spec:** `docs/superpowers/specs/2026-10-14-stage-2-abs-core-design.md` (sections Tabs, Library, Downloaded state, Libraries and the combined view; build-order step 4).

## Global Constraints

- Tabs, in this order: Home, Library, Downloads, Search. Downloads shows exactly: "No downloads yet. Downloading arrives in a later update".
- Library views along the top: Library, Authors, Narrators, Series, Collections. Layouts: large covers two across (default for new installs), small covers three across, list; the choice is remembered (`AppSettings.libraryLayout`, already present).
- Filter chips: All, Downloaded, In progress, Not started, Finished. The Filter button opens a sheet with Downloaded / Not downloaded, progress, and the sort order (Recently added, Recently played, Title, Author, Length). Filters apply to Library, Authors, Narrators, Series, Collections, Search and Home; groups with no matching books disappear while a filter is on.
- An in-progress book shows a thin amber bar; a finished book shows a check mark; every book shows its download state on its cover (check = downloaded, ring = downloading, nothing = on the server only). In Stage 2 the state is always "not downloaded", and the Downloaded chip shows "Nothing downloaded here yet".
- No connection: books that are not downloaded are dimmed with a cloud icon and cannot be played; a banner offers "Downloaded only".
- Libraries control: a check-list of every library grouped by server; unticking hides the library everywhere; a library added later appears ticked. The app stores what is turned OFF (`excluded_libraries`, already present).
- Nothing auto-plays. The mini player and the player come in steps 6 and 7; the Resume card on Home in step 7.
- Public repo: fixtures are synthetic (real structure, made-up titles, ids, paths, names); never commit real server data. Tokens and passwords never in logs or `toString`.
- Colours only through the theme (`AmberGold`, `TextSecondary`, `MaterialTheme.colorScheme`...), read inside composables; `onPrimary` on amber buttons.
- Room: `exportSchema` on, commit the new `shared/schemas/.../3.json`, never edit `1.json` or `2.json`; use `AutoMigration(2, 3)` with `@ColumnInfo(defaultValue = ...)` for new columns.
- Files in the working tree use CRLF; after every edit check the change really applied (grep for the new text).
- Commits end with: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey`.

## Review Focus

1. A server with a book that has no series, no narrator, a blank author or an empty library: no crash; "Unknown author" style groups are sensible; empty groups never show.
2. Authors/narrators that are lists ("A, B" and "A & B"): the book appears under each, and counts are right; the same person with different capitalisation or stray spaces is one entry.
3. Turning a library off and on again: books vanish immediately and come back after a sync; progress for books that vanish is kept; turning off every library of a server leaves the server in Settings and the Library says there is nothing to show instead of looking broken.
4. Filters hiding everything (Downloaded chip with nothing downloaded): an explanatory empty state, not a blank screen; changing tab keeps the chosen layout.
5. Offline: no connection monitor result at startup, flapping connectivity and airplane mode never crash and never block browsing of cached books.

---

## File structure

- `shared/src/commonMain/kotlin/com/chapterplayer/data/model/AudioBook.kt` (modify), `DownloadState.kt` (new), `LibraryInfo.kt` (new), `BookCollection.kt` (new).
- `shared/src/commonMain/kotlin/com/chapterplayer/data/local/` entities and DAOs for `libraries`, `collections`, `collection_books` (new); `BookEntity` (modify); database version 3.
- `shared/src/commonMain/kotlin/com/chapterplayer/domain/browse/` (new): `BrowseFilter.kt`, `BrowseSort.kt`, `BrowseGroups.kt` (pure functions).
- `shared/src/commonMain/kotlin/com/chapterplayer/ui/navigation` (existing `navigation/AppNavigation.kt`, modify), `ui/home`, `ui/downloads`, `ui/search`, `ui/library` (split the current 320-line `LibraryScreen.kt` into focused files), `ui/components`.
- `shared/src/commonMain/kotlin/com/chapterplayer/data/connectivity/ConnectivityMonitor.kt` (interface) with an Android implementation in `androidMain` and an iOS stub.

---

### Task 4.1: Book fields, libraries table and Room version 3

**Files:** modify `AudioBook.kt`, `BookEntity.kt`, the Room database class and its schemas folder, `AbsLibrarySource.kt`, ABS DTOs, the Plex source; create `LibraryInfo.kt`, `LibraryEntity.kt`, `LibraryDao.kt`; tests `BookEntityTest` (extend), `AbsLibrarySourceTest` (extend), `LibraryRepositoryTest` (new).

**Interfaces:**
- Produces: `AudioBook(libraryId: String, addedAt: Long, seriesName: String?, seriesSequence: String?)` (new fields last, with defaults `""`, `0L`, `null`, `null`); `LibraryInfo(serverId: String, id: String, name: String)`; Room `libraries(serverId, id, name)` primary key (`serverId`, `id`); `BookEntity` new columns `libraryId`, `addedAt`, `seriesName`, `seriesSequence` with defaults; `LibrarySource.fetchLibraries(server): List<LibraryInfo>` (all book libraries, including turned-off ones).
- Consumes: `ServerConfig.excludedLibraryIds` (step 3).

- [ ] **Step 1: Failing tests.** `AbsLibrarySourceTest`: an item from the `items-page-with-total.json` fixture maps `libraryId`, `addedAt` (milliseconds, as given by the server), `seriesName` "Name #3" -> `seriesName = "Name"`, `seriesSequence = "3"`; a series name without `#` has a null sequence; a blank `seriesName` is null. `fetchLibraries` returns the book libraries even when listed in `excludedLibraryIds`, and skips podcast libraries. `BookEntityTest`: round trip keeps the new fields.
- [ ] **Step 2: Run to see them fail.** `./gradlew :shared:testAndroidHostTest --tests "*AbsLibrarySourceTest" --tests "*BookEntityTest"`.
- [ ] **Step 3: Implement** the fields, the DTO additions (`libraryId`, `addedAt` on `AbsLibraryItemDto`, `seriesName` on `AbsMetadataDto`, all optional), the series-name parser (`"Name #3.5"` -> name + sequence, a pure function in `commonMain` with its own test), the Plex mapping (`libraryId` = section id, `addedAt` from the item's `addedAt` attribute when present, no series), `fetchLibraries` on both sources, Room version 3 with `AutoMigration(2, 3)`, the `libraries` table and DAO. `BookRepositoryImpl.syncBooksFromServer` also replaces the server's `libraries` rows (from `fetchLibraries`) in the same pass; a failed library call keeps the old rows.
- [ ] **Step 4: Run the suite and `assembleDebug`; `git status --short shared/schemas` shows only `3.json` added.**
- [ ] **Step 5: Commit** "Stage 2: store library, added date and series on books; Room version 3".

### Task 4.2: Turning libraries on and off

**Files:** modify `ServerRepository.kt`, `ServerRepositoryImpl.kt`, `BookRepository.kt`, `BookRepositoryImpl.kt`, `BookDao.kt`, `LibraryDao.kt`; tests `ServerRepositoryTest`, `BookRepositorySyncTest`, `LibraryVisibilityTest` (new).

**Interfaces:**
- Produces: `ServerRepository.setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean)` (changes ONLY the exclusion rows, never the credentials or the server row); `BookRepository.observeLibraries(): Flow<List<ServerLibraries>>` where `ServerLibraries(server: ServerConfig, libraries: List<LibraryChoice>)` and `LibraryChoice(id: String, name: String, enabled: Boolean)` (reuse the existing `LibraryChoice` type's shape if it is in `commonMain`, else define it in `data/model`).
- `BookDao.deleteByLibrary(serverId, libraryId)`.

- [ ] **Step 1: Failing tests.** `setLibraryEnabled(false)` adds the exclusion and removes that library's books but not other libraries' books or any progress; `(true)` removes the exclusion; neither touches tokens (`getById` still returns the same token and refresh token); `observeLibraries` lists turned-off libraries as `enabled = false`; turning off every library leaves the server in `observeAll`.
- [ ] **Step 2: Run to see them fail. Step 3: Implement**; turning ON is followed by a sync of that server (done by the caller, see Task 4.7). Delete order for a turned-off library: exclusion row first, then books.
- [ ] **Step 4: Run the suite. Step 5: Commit** "Stage 2: turn libraries on and off without touching sign-in".

### Task 4.3: Browse logic (pure functions)

**Files:** create `domain/browse/BrowseFilter.kt`, `BrowseSort.kt`, `BrowseGroups.kt`; test `BrowseLogicTest.kt`. (A good first task for the local OpenClaw agent: pure Kotlin, exact spec, no UI. The result is reviewed like any other task.)

**Interfaces:**
- `enum class ProgressFilter { ALL, IN_PROGRESS, NOT_STARTED, FINISHED }`, `enum class DownloadFilter { ALL, DOWNLOADED, NOT_DOWNLOADED }`, `enum class BrowseSort { RECENTLY_ADDED, RECENTLY_PLAYED, TITLE, AUTHOR, LENGTH }`.
- `data class BrowseQuery(val progress: ProgressFilter = ALL, val download: DownloadFilter = ALL, val sort: BrowseSort = RECENTLY_ADDED)`.
- `data class BrowseItem(val book: AudioBook, val progress: ReadingProgress?, val downloadState: DownloadState)`.
- `fun applyQuery(items: List<BrowseItem>, query: BrowseQuery): List<BrowseItem>`.
- `data class BookGroup(val key: String, val title: String, val items: List<BrowseItem>)`; `fun groupByAuthor(items): List<BookGroup>`, `groupByNarrator`, `groupBySeries` (items ordered by `seriesSequence` numerically, books without a sequence last, then title), `groupByCollection(items, collections: List<BookCollection>)`.

- [ ] **Step 1: Failing tests** (made-up data): IN_PROGRESS = progress exists, not finished, position > 0; NOT_STARTED = no progress or position 0; FINISHED = `isFinished`; sort by each key (RECENTLY_PLAYED puts never-played last; TITLE ignores a leading "The "/"A "/"An " and case); `groupByAuthor` splits "A, B" and "A & B", trims and merges differently-cased names, puts a blank author under "Unknown author"; groups are ordered by title, books inside by the query sort; `groupBySeries` ignores books with no series; groups of zero items are omitted after `applyQuery`; `groupByCollection` keeps collection order and skips collections with no matching books.
- [ ] **Step 2: Run to see them fail. Step 3: Implement. Step 4: Run to see them pass. Step 5: Commit** "Stage 2: filter, sort and group books for the Library views".

### Task 4.4: Collections

**Files:** create `BookCollection.kt`, `CollectionEntity.kt`, `CollectionBookEntity.kt`, `CollectionDao.kt`; modify `AbsApiService.kt` (+ DTO), `LibrarySource` (optional `fetchCollections`), Room version 3 (same migration as Task 4.1: add these two tables there), `BookRepositoryImpl`; tests with a synthetic fixture `abs/collections-page.json`.

**Interfaces:** `BookCollection(serverId: String, id: String, name: String, bookIds: List<String>)`; `BookRepository.observeCollections(): Flow<List<BookCollection>>`; `AbsApiService.getLibraryCollections(baseUrl, bearerToken, libraryId, limit, page)` for `GET /api/libraries/{id}/collections` (answer `{results:[{id,name,books:[{id}]}],total,limit,page}`).

- [ ] **Step 1: Failing tests:** the fixture parses (ids become `abs_<itemId>`, books missing from the cache are ignored when grouping); an empty `results` gives no collections; a failed collections call does NOT fail the book sync and keeps the old collections.
- [ ] **Step 2-5:** run to fail, implement (bounded paging), run to pass, commit "Stage 2: sync Audiobookshelf collections". Note: the owner's server has no collections, so the shape follows the Audiobookshelf documentation and cannot be checked live; say so in the PR.

### Task 4.5: Navigation shell, Home, Downloads, Search

**Files:** modify `navigation/AppNavigation.kt` and `Screen`; create `ui/home/HomeScreen.kt`, `ui/downloads/DownloadsScreen.kt`, `ui/search/SearchScreen.kt` + `SearchViewModel.kt`; tests `SearchViewModelTest`.

**Interfaces:** tabs Home, Library, Downloads, Search (replace the Player/Settings tabs; keep the Player route for later). Settings opens from a gear icon in the top bar of Home and Library. Home shows the existing continue-reading strip and a "Recently added" shelf, both honouring the current `BrowseQuery` (the Resume card and the Continue Series shelf arrive in step 7, when the player exists to resume into); `SearchViewModel` filters cached books by title, author, narrator and series (case-insensitive, trimmed, ignoring empty queries) and applies `BrowseQuery`.

- [ ] **Step 1: Failing tests** for `SearchViewModel` (matches each field, empty query shows nothing, filters apply). **Step 2-4:** run to fail, implement the shell and screens (compile + emulator check by the controller), run to pass. **Step 5: Commit** "Stage 2: four tabs with Home, Downloads and Search".

### Task 4.6: Library screen: views, layouts, chips, Filter sheet

**Files:** split `ui/library/LibraryScreen.kt` into `LibraryScreen.kt`, `LibraryViews.kt` (the five-view row), `LibraryLayouts.kt` (large grid, small grid, list), `FilterChips.kt`, `FilterSheet.kt`, `GroupList.kt`, `GroupDetailScreen.kt`; modify `LibraryViewModel.kt`/`LibraryUiState.kt`; tests `LibraryViewModelTest` (extend).

**Interfaces:** `LibraryUiState` carries `view: LibraryView` (LIBRARY, AUTHORS, NARRATORS, SERIES, COLLECTIONS), `layout: LibraryLayout` (from `AppSettings`), `query: BrowseQuery`, `groups`/`items` from Task 4.3; the old source pills (All/ABS/Plex) are removed (the source badge on each cover stays). A group opens `GroupDetailScreen` listing its books.

- [ ] **Step 1: Failing view-model tests:** the layout choice is read from and written to `AppSettings`; chips and sheet changes update `query`; each view maps to the right groups; a filter that hides everything yields an explanatory empty state flag (`emptyBecauseOfFilter = true`).
- [ ] **Step 2-4:** run to fail, implement, run to pass (screens compile-checked; controller checks on the emulator: layouts, chips, sheet, drill-in, empty states, both themes). **Step 5: Commit** "Stage 2: Library views, layouts, filter chips and Filter sheet".

### Task 4.7: Libraries check-list

**Files:** create `ui/library/LibrariesSheet.kt`; modify `LibraryViewModel.kt`; tests in `LibraryViewModelTest`.

**Interfaces:** the Library top bar gets a "Libraries" button opening a sheet listing `observeLibraries()` grouped by server with a checkbox per library (`Role.Checkbox`, 48dp rows). Toggling calls `setLibraryEnabled`; turning ON also syncs that server; the sheet cannot leave every library of every server off without a note "Everything is hidden".

- [ ] **Step 1: Failing tests:** toggling off calls `setLibraryEnabled(false)` and the books disappear from `items`; toggling on calls it and starts a sync of that server exactly once; a failing sync shows the usual sync message and leaves the library ticked. **Step 2-4** as usual. **Step 5: Commit** "Stage 2: Libraries check-list".

### Task 4.8: Downloaded state and offline

**Files:** create `data/model/DownloadState.kt`, `data/connectivity/ConnectivityMonitor.kt`, `androidMain/.../AndroidConnectivityMonitor.kt`, `iosMain` stub, `ui/components/DownloadBadge.kt`, `OfflineBanner.kt`; modify `BookCard.kt`, list rows, `LibraryViewModel`; Koin binding; tests `OfflineRulesTest`.

**Interfaces:** `enum class DownloadState { NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED }`; `interface DownloadStates { fun observe(): Flow<Map<String, DownloadState>> }` (Stage 2 implementation: empty map = everything NOT_DOWNLOADED); `interface ConnectivityMonitor { val isOnline: Flow<Boolean> }` (initial value `true` until the platform reports; errors never throw); pure `fun isPlayable(state: DownloadState, online: Boolean, downloadedOnly: Boolean): Boolean` and `fun isDimmed(...)`; the banner "Downloaded only" is a switch held in `AppSettings` (`downloadedOnly`).

- [ ] **Step 1: Failing tests** for the pure rules (offline + not downloaded = not playable and dimmed; downloaded is always playable; online and not "Downloaded only" is playable) and the empty-map default. **Step 2-4:** run to fail, implement (Android monitor uses `ConnectivityManager.registerDefaultNetworkCallback`, unregistered on close), run to pass; `KoinModulesTest` must pass. **Step 5: Commit** "Stage 2: downloaded state and offline dimming".

### Task 4.9: Verification, docs, PR (controller)

- [ ] Full suite, `assembleDebug`, minified `assembleRelease`; privacy scan; emulator pass with a throwaway ABS user: tabs, five views, three layouts (kept after a restart), chips, sheet, group drill-in, Libraries check-list off/on, empty states, airplane mode dimming and banner, both themes, upgrade of a v2 database to v3 keeps servers and books.
- [ ] Update `docs/ROADMAP.md` (tick the three step-4 items it covers) and `CLAUDE.md` (the browse package, the libraries table).
- [ ] Final whole-branch review, one fix wave, PR "Stage 2: tabs and Library (step 4)"; merge only with the owner's go-ahead.

## Delegation to the local OpenClaw agent

Task 4.3 (pure logic with exact tests) and the pure rules in Task 4.8 go to OpenClaw (`qwen3-coder`) first; the other tasks go to the usual implementers. OpenClaw's output is written into the repo by the controller, run against the tests and reviewed like any other task. Nothing secret is placed in its prompts or the shared `claude-link` folder.
