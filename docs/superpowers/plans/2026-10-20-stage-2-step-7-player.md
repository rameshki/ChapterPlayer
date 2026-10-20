# Stage 2 step 7: Full player, mini player and Home Resume card

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Playing a book shows up everywhere it should: a full-screen player (large cover, chapter title, whole-book and chapter progress bars, time left, skip back 15 / forward 30, speed, sleep timer, chapter list), a mini player above the tab bar while something is loaded, and a Resume card plus a Continue Series shelf on Home. Tapping the playback notification opens the player.

**Architecture:** Everything reads one thing: `PlaybackController.state` (built in step 6). The player and mini player are views of it; a small shared `PlaybackStarter` (extracted from the details screen's view model) starts a book from any screen, so Home, details and the player never duplicate the start logic. What to show (fractions, labels, the resume candidate, the next book in a series) is pure code in `domain/playback` and `domain/browse` with unit tests; Compose only renders it.

**Tech Stack:** Compose Multiplatform 1.12, Koin, Coil 3, the existing `PlaybackController`/`AppSettings`.

**Spec:** `docs/superpowers/specs/2026-10-14-stage-2-abs-core-design.md` (sections Opening the app, Tabs, Player; build-order step 7).

## Rulings made while planning
- The spec's bottom row lists Speed, Sleep timer, Bookmarks and History. **Bookmarks and History belong to step 8** (bookmarks sync, history needs a table), so step 7 builds Speed, Sleep timer and Chapters only; no dead buttons.
- Home keeps its existing "Continue Reading" shelf for OTHER in-progress books (the Resume card takes the most recent one), because losing it would hide books.
- Previous/next chapter buttons are added to the player (the spec only names the skips; chapter navigation is what listeners expect for chaptered books).
- Sleep timer presets follow the spec's assumptions: Off, 15, 30, 45, 60 minutes and End of chapter. Speed presets 1.0, 1.25, 1.5, 2.0 plus the full step list 0.5-3.0 in the sheet. The chosen speed is remembered (`AppSettings.playbackSpeed`) and applied when a book loads.
- Continue Series offers the next book only after the most advanced touched book of the series is FINISHED (an in-progress book is already on the Resume card or Continue Reading); books without a sequence number sort last, so a finished unnumbered book counts as the most advanced.
- Resume and Play on a book already loaded in the player never reload it: the starter plays it on from the live position (nothing when it is already playing); a reload would restart from the last saved position.

## Global Constraints
- Both themes readable; colours only through the theme tokens read inside composables; `onPrimary` text on amber buttons; fonts as in the rest of the app (Playfair titles, DM Sans text); 16dp gutters; touch targets at least 48dp; content descriptions on every icon button (the skip buttons say "Back 15 seconds" / "Forward 30 seconds" and show the numbers); headings and `selected` semantics where relevant.
- The player must never show raw exception text; errors use the existing safe texts (`PlaybackState.error`).
- Nothing plays until the user presses Play/Resume (the Resume card button counts). Opening the player never starts audio by itself.
- The mini player appears whenever `PlaybackState.bookId != null`, sits above the tab bar, hides on the full player and on the Settings screens, never covers the last list item (content padding).
- `commonMain` stays portable (no `putIfAbsent`, `String.format`, `java.*`): format numbers by hand.
- Public repo: synthetic test data; never real server data. Files in the working tree are CRLF: check every edit applied.
- Commits end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey`.

## Review Focus
1. A book with no chapters (chapter bar and chapter title absent, skip and seek still work), one chapter, hundreds of chapters (the chapter list sheet scrolls and jumps), chapters not covering the whole book.
2. Seeking with the sliders: dragging must not fight the position updates (no jumping back mid-drag); a seek on release only; a seek past either end; a duration of 0 or unknown while buffering.
3. State edges: nothing loaded (player opened from a stale notification or deep link shows "Nothing is playing" with Back), error while loading, buffering, finished book, a second book started from Home while one plays.
4. Mini player and navigation: back stack with the player on top, tapping the notification when the app is closed or open, rotation, the mini player not flickering while the controller reconnects.
5. Home: a Resume card for a finished or never-started book must not appear; Continue Series for a series with one book, with gaps in the sequence, with a missing sequence, books from two servers; offline dimming of the card and shelf.

---

## File structure
- `shared/src/commonMain/kotlin/com/chapterplayer/domain/playback/NowPlaying.kt`, `domain/browse/ContinueSeries.kt`, `domain/browse/ResumeCard.kt` (new, pure).
- `shared/src/commonMain/kotlin/com/chapterplayer/data/playback/PlaybackStarter.kt` (new); `ui/details/BookDetailsViewModel.kt` (uses it).
- `shared/src/commonMain/kotlin/com/chapterplayer/ui/player/` (new): `PlayerViewModel.kt`, `PlayerScreen.kt`, `PlayerControls.kt`, `SpeedSheet.kt`, `SleepTimerSheet.kt`, `ChapterListSheet.kt`, `MiniPlayer.kt`.
- `navigation/AppNavigation.kt` (player route, mini player slot, open-player intent), `androidApp/.../MainActivity.kt` and the service's session activity (open the player), `ui/home/*` (Resume card, Continue Series), `data/settings/AppSettings.kt` (`playbackSpeed`), Koin.

---

### Task 7.1: Now-playing, resume-card and continue-series logic (pure)  [local-model task: tests first]
**Interfaces:**
- `fun chapterFraction(chapters: List<Chapter>, positionMs: Long): Float?` null for no chapters; position within the current chapter divided by its duration, clamped 0..1 (a chapter with zero or negative duration gives 0). `fun bookFraction(positionMs: Long, durationMs: Long): Float` clamped 0..1, 0 when the duration is not positive.
- `fun speedLabel(speed: Float): String`: "1x", "1.25x", "0.5x", "2.5x", "3x" (trailing zeros dropped, at most two decimals, no `String.format`).
- `fun sleepLabel(timer: SleepTimer, remainingMs: Long?): String`: Off gives "Sleep timer"; After gives the remaining time as "M:SS" (e.g. "14:59") or "H:MM:SS" from an hour, falling back to "N min" when `remainingMs` is null; EndOfChapter gives "End of chapter".
- `val SLEEP_PRESETS: List<SleepTimer>` = Off, After(15), After(30), After(45), After(60), EndOfChapter; `fun sleepPresetLabel(timer: SleepTimer): String` ("Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "End of chapter").
- `fun previousChapterStart(chapters, positionMs): Long` (restart the current chapter when more than 3 s into it, otherwise the previous chapter's start; the first chapter restarts at its start; no chapters gives 0) and `fun nextChapterStart(chapters, positionMs): Long?` (null on the last chapter or no chapters).
- `data class ResumeInfo(val item: BrowseItem, val chapterTitle: String?, val fraction: Float, val timeLeft: String)` and `fun resumeCandidate(items: List<BrowseItem>): ResumeInfo?`: the item with progress that is not finished and has `positionMs > 0`, most recent `lastPlayedAt` first (ties by id); `chapterTitle` from the book's cached chapters at the position; `fraction` = `bookFraction`; `timeLeft` = `formatTimeLeft`; null when none.
- `fun continueSeries(items: List<BrowseItem>, excludeBookId: String? = null): List<BrowseItem>`: for each series (same-server grouping, key = trimmed lowercase name) in which the user has at least one book that is finished or started, the NEXT book: the first book after the most advanced started/finished book in sequence order (sequence order as in `groupBySeries`) that is not finished and has not been started; series with no such book are left out; the result is ordered by the most recent `lastPlayedAt` of that series' books; `excludeBookId` removes one book (the one on the Resume card).
- [ ] Tests first with exact values (boundaries, 0 duration, the 3 s rule, gaps and missing sequences, two servers with the same series name, a finished last book, a series with one book). Run, implement, run, commit "Stage 2: player and home logic".

### Task 7.2: PlaybackStarter and the player view model
- `PlaybackStarter(controller, books, settings...)` in commonMain with `suspend fun start(book: AudioBook, atMs: Long? = null): Result<Unit>`: the logic now inside `BookDetailsViewModel.onResumeClicked` (resolve the start position: local progress, `-1` for none, `0` for finished; `load`; apply the saved speed; `play`; map failures to the safe texts); `BookDetailsViewModel` is refactored to use it with all its tests green.
- `AppSettings.playbackSpeed` (default 1.0), applied by the starter after `load`; changing speed in the player saves it.
- `PlayerViewModel(controller, starter, books, settings)` exposing `StateFlow<PlayerUiState>`: book (title, author, cover), chapter title, `bookFraction`, `chapterFraction`, elapsed/remaining labels, `timeLeft`, `isPlaying`, `isBuffering`, `speedLabel`, `sleepLabel`, `chapters` with the current index, `error`, `nothingPlaying`; actions: `playPause`, `skipBack`, `skipForward`, `previousChapter`, `nextChapter`, `seekBook(fraction)`, `seekChapter(fraction)`, `jumpToChapter(index)`, `setSpeed`, `setSleepTimer`, `dismissError`.
- [ ] View-model tests first with a fake controller (state transitions, each action calls the right controller method with the right values, seeks clamp, nothing playing, error mapping, speed persisted). Commit.

### Task 7.3: Full player screen
- `Screen.Player` route replaces the stub: large cover, chapter title (tap opens the chapter list sheet), book title and author, two sliders (whole book and current chapter; seek only on release; labels elapsed / remaining and the time left under them), row: previous chapter, Back 15 (number on the button), big Play/Pause (a progress ring while buffering), Forward 30, next chapter; bottom row: Speed (opens the speed sheet: presets 1.0/1.25/1.5/2.0 as chips and the full step list), Sleep timer (opens its sheet with the presets and the live countdown), Chapters (opens the list). Safe error banner with Dismiss; "Nothing is playing" state with a Back button. Rotation keeps state.
- [ ] Compile, wire Koin (`KoinModulesTest`), controller checks on the emulator. Commit.

### Task 7.4: Mini player and navigation
- `MiniPlayer` above the tab bar in the app scaffold: cover thumb, title, chapter, play/pause button, thin book progress line; tap opens the Player route; hidden on the player route and on Settings/Add server routes; Back from the player returns to the previous screen; opening the player from the mini player and from details/Home use the same route (no stacking the player twice).
- MainActivity and the service's session activity open the player when the notification is tapped (an intent extra `open_player`), both with the app closed and open.
- [ ] Tests for the visibility rule (pure function over route + state); emulator checks. Commit.

### Task 7.5: Home Resume card and Continue Series
- `HomeViewModel` adds `resume: ResumeInfo?` and `continueSeries: List<BrowseItem>` (computed from the UNFILTERED items so a browse filter never hides the card; dimming rules apply when offline); the existing in-progress shelf excludes the resume book.
- Home: a large Resume card on top (cover, chapter title, progress bar, time left, a Resume button that starts the book through `PlaybackStarter` and opens the player; tapping the card elsewhere opens the details), then Continue Series, Continue Reading, Recently added.
- [ ] View-model tests (card absent for finished or never-started books, excluded from the shelf, continue series wiring, offline dimming), emulator checks. Commit.

### Task 7.6: Verification, docs, PR (controller)
- Full suite, `assembleDebug`, minified `assembleRelease`; emulator pass in both themes (rotate, 200% font scale on the player), privacy scan, `docs/ROADMAP.md` (full player + mini player item) and `CLAUDE.md`; final whole-branch review (opus), ONE fix wave, scoped re-review; PR "Stage 2: player, mini player and Home Resume card (step 7)"; merge as the owner has authorised ("merge as you see fit") once CI is green.

## Delegation to the local models
Task 7.1 goes to gemma4:26b through OpenClaw (tests first, one file per call, header dictated, "common Kotlin only"). Views, view models, navigation and the starter refactor go to Claude implementers.
