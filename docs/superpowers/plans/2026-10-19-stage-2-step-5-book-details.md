# Stage 2 step 5: Book details Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapping a book opens a details screen: a compact header (cover, title, author, narrator, length, Resume button) and three tabs, About, Chapters and Series, opening on Chapters with the current chapter highlighted.

**Architecture:** The library list has no chapters, so the app loads them for one book from `GET /api/items/{id}` when its details screen opens and caches them in the existing `books.chaptersJson` column (no database change). Everything that decides what to show (which chapter is current, how times read, which books share a series) is pure code in `domain/browse` with unit tests; the screen only renders it. Playback does not exist yet (steps 6-7): the Resume button and chapter taps are inert until then.

**Tech Stack:** Compose Multiplatform 1.12 (JetBrains navigation), Koin, Ktor + kotlinx.serialization, Room (existing columns only), Coil 3.

**Spec:** `docs/superpowers/specs/2026-10-14-stage-2-abs-core-design.md` (sections Book details and "Playing a book"; build-order step 5).

## Global Constraints

- Tabs: About, Chapters, Series. The screen OPENS on Chapters. The chapter you are on is highlighted. Tapping a chapter jumps to it (playback arrives in step 6: until then a tap changes nothing and the UI says so).
- Header: cover, title, author, narrator, length, and a Resume button. Until playback exists (steps 6-7) the button is shown DISABLED with the caption "Playback arrives in a later update".
- Chapters are loaded per book from `/api/items/{id}` (Audiobookshelf) when the screen opens and cached with the book in `books.chaptersJson`; the cached chapters show immediately and offline.
- Links from the details screen (added at the owner's request): if the book is part of a series the screen says "Part of <series> #<number>" and tapping it opens that series' book list; for each author and each narrator of the book who has OTHER books in the library there is a "More by <name> · N books" row that opens that person's book list. These open the existing group detail screen (the same one the Library's Authors, Narrators and Series views use), so filters and layouts carry over. Authors and narrators with no other books get no row; "unknown" is never a link.
- Description, genres, year and series come live from the same call; they are not stored (no migration). Offline with no live data, About shows what the list already has (title, author, narrator, series name) and the note "More details need a connection".
- Public repo: fixtures are synthetic (real structure, made-up text, ids, paths); never commit real server data. Tokens and passwords never in logs or `toString`.
- Colours only through the theme, read inside composables; `onPrimary` on amber buttons; both themes readable; accessibility labels; 16dp gutters; 48dp touch targets.
- Files in the working tree use CRLF; after every edit check the change really applied (grep for the new text).
- Commits end with: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey`.

## Review Focus

1. A book with no chapters, one chapter, or chapters that overlap or leave gaps: no crash; "Chapters" says there are none; the current-chapter rule is defined for every position.
2. A position before the first chapter start, exactly on a boundary, at the very end, or past the end (a bad saved position): a sensible chapter, never an exception.
3. The details call failing (offline, 401, 5xx) or a book that was deleted on the server: cached chapters still show; an error line, not a blank screen; a 401 goes through the same "Sign in again" path as syncing.
4. A very long description or hundreds of chapters: scrolls smoothly (lazy list), no layout jump when the live data arrives.
5. Series tab for a book with no series, a series of one, a sequence like "3.5" or missing, books from two servers with the same series name: only same-server books, ordered by sequence, the current book marked.

---

## File structure

- `shared/src/commonMain/kotlin/com/chapterplayer/domain/browse/ChapterLogic.kt` (new): `currentChapterIndex`, formatting helpers; `SeriesNeighbours.kt` (new).
- `shared/src/androidMain/.../data/remote/abs/dto/AbsItemDetailDto.kt` (new), `AbsApiService.kt` (modify), `data/source/AbsLibrarySource.kt` + `LibrarySource.kt` (modify), `data/model/BookDetails.kt` (new).
- `BookRepository` / `BookRepositoryImpl` (modify): `loadDetails`.
- `shared/src/commonMain/kotlin/com/chapterplayer/ui/details/` (new): `BookDetailsViewModel.kt`, `BookDetailsScreen.kt`, `ChaptersTab.kt`, `AboutTab.kt`, `SeriesTab.kt`.
- `navigation/AppNavigation.kt` (modify): route `book/{id}`.

---

### Task 5.1: Chapter and series logic (pure functions)

**Files:** create `domain/browse/ChapterLogic.kt`, `domain/browse/SeriesNeighbours.kt`; tests `ChapterLogicTest.kt`, `SeriesNeighboursTest.kt`. (Local-model task: Claude writes the tests first, the local model writes the code, both are reviewed.)

**Interfaces:**
- `fun currentChapterIndex(chapters: List<Chapter>, positionMs: Long): Int?`: `null` for an empty list; the index of the chapter whose `[startMs, endMs)` contains the position; chapters are taken in the order given; a position before the first chapter's start gives 0; a position at or after the last chapter's end gives the last index; when ranges overlap the LAST chapter that has started wins; in a gap between chapters the previous chapter stays current; a negative position gives 0.
- `fun formatClock(ms: Long): String`: "M:SS" under an hour, "H:MM:SS" from an hour (negative or zero gives "0:00"; hours do not wrap past 99: 100 h reads "100:00:00").
- `fun formatLength(ms: Long): String`: "45 min", "1 h 05 min", "12 h 30 min", exactly an hour "1 h", under a minute "under 1 min", zero or negative "0 min".
- `fun formatTimeLeft(durationMs: Long, positionMs: Long): String`: `formatLength` of the remainder (never negative) followed by " left", for example "3 h 10 min left", "finished" when nothing is left.
- `data class SeriesEntry(val item: BrowseItem, val isCurrent: Boolean)`; `fun seriesNeighbours(all: List<BrowseItem>, current: AudioBook): List<SeriesEntry>`: empty when the current book has no (or blank) series; otherwise the books on the SAME server whose series name equals the current one ignoring case and surrounding spaces, ordered like `groupBySeries` (numeric sequence, missing last, then title key, then id), the current book flagged. A series with only the current book returns a single entry.

- `data class SeriesLink(val key: String, val title: String, val sequence: String?, val bookCount: Int)`; `data class PersonLink(val key: String, val title: String, val otherBookCount: Int)`; `data class BookLinks(val series: SeriesLink?, val authors: List<PersonLink>, val narrators: List<PersonLink>)`; `fun bookLinks(all: List<BrowseItem>, current: AudioBook): BookLinks` in `BookLinks.kt`: `series` is null for no or blank series, otherwise key = the trimmed lowercase name (the SAME key the Series group list uses, so the link opens the right group), title = the current book's series name trimmed, sequence = the current book's sequence, bookCount = the number of books in that group; `authors` and `narrators` come from the existing `groupByAuthor` / `groupByNarrator` groups that contain the current book (the current book is treated as part of `all` even if missing from it): one `PersonLink` per group (key and title exactly as the group has them, `otherBookCount` = group size minus the current book), only when `otherBookCount > 0`, never the "unknown" group, ordered by title case-insensitively.

- [ ] **Step 1: Failing tests** (exact values above, with the boundary, gap, overlap, empty, negative, huge and "3.5" cases, two servers with the same series name, and for the links: no series, blank series, series count and key, an author with other books, an author with none (omitted), two authors where only one has other books, "unknown" never linked, narrators the same way, the current book missing from `all`).
- [ ] **Step 2: Run to see them fail.** `./gradlew :shared:testAndroidHostTest --tests "*ChapterLogicTest" --tests "*SeriesNeighboursTest" --tests "*BookLinksTest"`
- [ ] **Step 3: Implement** in the three files (the series ordering reuses `groupBySeries`'s comparator; the links reuse the group functions).
- [ ] **Step 4: Run to see them pass.** **Step 5: Commit** "Stage 2: chapter, series and link logic for the details screen".

### Task 5.2: Load a book's details

**Files:** create `AbsItemDetailDto.kt`, `BookDetails.kt`, fixture `abs/item-detail.json` (synthetic, shape: `media.chapters[{id,start,end,title}]`, `media.metadata{description,descriptionPlain,genres[],publishedYear,series[{id,name,sequence}],authors[{id,name}],narrators[]}`, `media.duration`, plus extra fields that must be ignored); modify `AbsApiService`, `LibrarySource`, `AbsLibrarySource`, `PlexLibrarySource`, `BookRepository(Impl)`; tests `AbsItemDetailParsingTest`, `AbsLibrarySourceTest` (extend), `BookRepositoryDetailsTest`.

**Interfaces:**
- `data class BookDetails(val chapters: List<Chapter>, val description: String?, val genres: List<String>, val publishedYear: String?, val series: List<SeriesRef>)` with `SeriesRef(name: String, sequence: String?)`.
- `AbsApiService.getItem(baseUrl: String, bearerToken: String, itemId: String): AbsItemDetailDto` (`GET /api/items/{id}?expanded=1`, through the authorizer like the other calls).
- `LibrarySource.fetchBookDetails(server: ServerConfig, bookId: String): BookDetails?` (ABS: maps the DTO, chapters seconds -> ms, description = `descriptionPlain` when present else the HTML-free `description`; Plex: `null`, its chapters come with its tracks).
- `BookRepository.loadDetails(bookId: String): Result<BookDetails?>`: finds the book and its server, calls the source, and on success writes the chapters into `books.chaptersJson` for that book only; on failure returns the failure (cached chapters stay untouched). `SignInRequiredException` propagates as a failure like in sync.

- [ ] **Step 1: Failing tests:** the fixture parses (unknown fields ignored, missing optional fields give nulls and empty lists); chapters convert seconds to milliseconds exactly; `fetchBookDetails` requests the right path with the bearer; `loadDetails` stores chapters for that book only and leaves other books alone; a failing call leaves the cached chapters; a book id without a server gives a failure, not a crash; a 401 leads to the same refresh path as sync (use the existing authorizer fake).
- [ ] **Step 2-5:** run to fail, implement, run to pass (full suite and `assembleDebug`), commit "Stage 2: load a book's chapters and details".

### Task 5.3: Details view model

**Files:** create `ui/details/BookDetailsViewModel.kt`, `BookDetailsUiState.kt`; test `BookDetailsViewModelTest.kt`.

**Interfaces:** `BookDetailsViewModel(bookId: String, books: BookRepository, ...)` exposing `StateFlow<BookDetailsUiState>`: `book: AudioBook?`, `phase: Loading | Loaded | NotFound`, `chapters: List<ChapterRow(index, title, startMs, durationMs, isCurrent)>` (cached chapters first, replaced when the live call returns), `currentChapterIndex` from the book's progress via `currentChapterIndex`, `about: AboutInfo(description, genres, year, seriesLine, narratorLine, authorLine, needsConnection)`, `series: List<SeriesEntry>`, `links: BookLinks` (from `bookLinks`, computed on the unfiltered library so a filter never hides a link), `timeLeft: String`, `error: String?`, `selectedTab: DetailsTab` (default CHAPTERS), `resumeEnabled = false` for now.

- [ ] **Step 1: Failing tests:** opens on CHAPTERS; cached chapters appear before the live call returns and are replaced after; the current chapter follows the saved progress (finished book: last; no progress: first); a failing live call keeps the cached chapters and sets an error line; a missing book gives NotFound; About shows the "needs a connection" note when no live data arrived; the series list marks the current book; tab selection survives re-collection.
- [ ] **Step 2-5:** fail, implement, pass, commit "Stage 2: book details view model".

### Task 5.4: Details screen and navigation

**Files:** create `ui/details/BookDetailsScreen.kt`, `ChaptersTab.kt`, `AboutTab.kt`, `SeriesTab.kt`; modify `navigation/AppNavigation.kt` (route `book/{id}` with an encoded id; Home, Library, Search and group-detail book taps open it; the Player stub stays reachable from code).

- [ ] **Step 1:** screens compile and the view model is wired through Koin (`KoinModulesTest` passes).
- [ ] **Step 2:** header (cover, title, author, narrator, length via `formatLength`, "3 h 10 min left" when started, the disabled Resume button with its caption), tab row opening on Chapters, chapter rows (number, title, start time via `formatClock`, the current one highlighted and marked for screen readers), About tab (description with "Read more" when long, genres as chips, year, series line, the connection note), Series tab (an "Open series" button at the top, rows with the sequence number, current book marked; tapping another book opens its details), a "Part of <series> #N" line under the title and a "More by" section on the About tab with one tappable row per linked author and narrator ("More by <name> · N books", with a chevron; one 48dp target each); each link opens the existing group detail route with the link's kind and key (authors, narrators, series), dimmed offline books keep opening details (only playing is blocked), lazy lists with stable keys.
- [ ] **Step 3:** controller checks on an emulator (both themes, a book with many chapters, a book with none, offline, back navigation, tab state), full suite, `assembleDebug`; **Step 4: Commit** "Stage 2: book details screen".

### Task 5.5: Verification, docs, PR (controller)

- [ ] Full suite, `assembleDebug`, minified `assembleRelease`, privacy scan, emulator pass with a throwaway ABS user, `docs/ROADMAP.md` and `CLAUDE.md` updated, final whole-branch review and one fix wave, PR "Stage 2: book details (step 5)"; merge only with the owner's go-ahead.

## Delegation to the local models

Task 5.1 goes to the local models first (gemma4:26b through OpenClaw's gateway, tests written first by Claude, header dictated, one file per call), reviewed like any other task. The rest goes to the usual implementers.
