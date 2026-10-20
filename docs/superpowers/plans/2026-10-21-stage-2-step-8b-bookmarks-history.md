# Stage 2 step 8b: Bookmarks (synced with Audiobookshelf) and History

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the player you can save a bookmark at the current position (with an optional name), see and jump to your bookmarks, rename and delete them, and the same bookmarks appear on your other Audiobookshelf devices. A History sheet lists the recent positions of the current book, so after an accidental seek you can jump straight back.

**Architecture:** Bookmarks live in a local Room table (so they work offline) and carry a sync state; a small `BookmarkSyncer` pulls the server's bookmarks (`GET /api/me`) at app start, when the network returns and after library syncs, and pushes pending local creates/renames/deletes, the same trigger pattern as the progress engines. History is local only: a `HistoryRecorder` watches `PlaybackController.state` for position jumps (any source: slider, chapter jump, notification skip, headset) and stores the position the listener JUMPED FROM. All decisions (merge, titles, jump detection, trimming, labels) are pure code with unit tests.

**Tech Stack:** Room (v4 to v5 auto-migration), Ktor + kotlinx.serialization, Koin, Compose Multiplatform.

**Spec:** `docs/superpowers/specs/2026-10-14-stage-2-abs-core-design.md` (sections Player, assumptions about History and Bookmarks; build-order step 8).

## What the live server does (checked 2026-10-20 against v2.37.1 with a throwaway user; structure only)
- `GET /api/me` returns `bookmarks[]`, each `{libraryItemId, time (seconds), title, createdAt (ms)}`.
- `POST /api/me/item/{libraryItemId}/bookmark` body `{time, title}` returns the created `{libraryItemId, time, title, createdAt}`.
- `DELETE /api/me/item/{libraryItemId}/bookmark/{time}` removes it (200).
- A bookmark is identified by (`libraryItemId`, `time`): one per whole second per book. Rename: `PATCH /api/me/item/{libraryItemId}/bookmark` with `{time, title}` (the implementer confirms the exact verb against the server with the throwaway user, structure only).

## Rulings made while planning
- Bookmark identity is (bookId, whole second). A new bookmark within the same second as an existing one replaces its title instead of making a second row.
- Server wins for rows that were already synced; local rows with a pending operation (create, rename, delete) keep their state until pushed. A bookmark deleted on another device disappears here at the next pull unless it has a pending local change.
- Default bookmark title: "Bookmark at <clock time>" (for example "Bookmark at 1:02:03"); an empty or blank name given by the user falls back to it.
- The player's bottom row follows the spec: **Speed, Sleep timer, Bookmarks, History** (the chapter list stays reachable by tapping the chapter title, which already shows a chevron). This replaces the temporary Chapters button from step 7.
- History records the position the listener left (not where they landed) when the position jumps by at least 60 seconds without playing time to explain it (slider seeks, chapter jumps, skips from the notification do not count unless they jump that far), keeps the last 10 per book and drops entries within 5 seconds of an existing one.
- Bookmarks of books on a Plex server stay local (no server calls) until Plex support exists.

## Global Constraints
- Local first: adding, renaming and deleting a bookmark works offline and never waits for the server; failures are silent and retried.
- Never log tokens or full URLs; error texts are fixed and safe.
- Both themes, 48dp touch targets, content descriptions, headings on sheet titles, `selected` semantics where relevant; works at 200% font scale; colours only through theme tokens.
- `commonMain` stays portable (no `putIfAbsent`, `String.format`, `java.*`, `System.*`); the clock is a `() -> Long` parameter.
- Room: schema export `5.json` committed; the older tables untouched.
- Public repo: synthetic data only (fixtures too).
- Commits end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey`; CRLF working tree: check every edit applied.

## Review Focus
1. Two devices: a bookmark added here and deleted there before the sync; the same second bookmarked on both; a rename on both; deleting a bookmark that is still pending creation (it must never reach the server); a book removed from the library or a server removed with bookmarks left behind.
2. Offline for days with many pending operations: order of delivery, one row per (book, second), no duplicates after a retry that half-succeeded (the server created it but the response was lost).
3. History: a book played through normally never records anything; seeking back and forth quickly (trimming, dedupe); a jump detected while paused or buffering; chapter jumps across files; the very first state emission of a book (no previous position).
4. UI: a book with 500 bookmarks; very long names; the sheet while playing (position keeps moving: the 'Add' button saves the position at the moment of the tap); landscape; 200% font.
5. Sync races: pull merging while the user adds a bookmark; push of an old rename after a newer server rename.

---

## File structure
- `shared/src/commonMain/kotlin/com/chapterplayer/domain/playback/Bookmarks.kt`, `History.kt` (new, pure).
- `shared/src/commonMain/kotlin/com/chapterplayer/data/bookmarks/` (new): `BookmarkSyncer.kt`, `HistoryRecorder.kt`, model classes.
- Room: `BookmarkEntity` + `BookmarkDao`, `HistoryEntity` + `HistoryDao`, `ChapterPlayerDatabase` v5 (+ `5.json`).
- `androidMain` ABS: `AbsBookmarkDto.kt`, `AbsApiService` calls, `AbsLibrarySource`/`LibrarySource` methods, `BookRepository(Impl)` bookmark and history methods.
- `ui/player/`: `BookmarksSheet.kt`, `HistorySheet.kt`, `PlayerViewModel` additions, `PlayerScreen` bottom row.

---

### Task 8b.1: Bookmark and history rules (pure functions)  [local-model task: tests first]
- `Bookmarks.kt`: `enum class BookmarkState { SYNCED, PENDING_CREATE, PENDING_RENAME, PENDING_DELETE }`; `data class Bookmark(val bookId: String, val timeSec: Long, val title: String, val createdAt: Long, val state: BookmarkState)`; `fun defaultBookmarkTitle(positionMs: Long): String` ("Bookmark at M:SS" under an hour, "Bookmark at H:MM:SS" from an hour, whole seconds, negative counts as 0); `fun bookmarkTitleOrDefault(input: String?, positionMs: Long): String` (trimmed input, default when null or blank); `fun mergeBookmarks(local: List<Bookmark>, server: List<Bookmark>): BookmarkMerge` with `data class BookmarkMerge(val result: List<Bookmark>, val toCreate: List<Bookmark>, val toRename: List<Bookmark>, val toDelete: List<Bookmark>)` (rules below); `fun visibleBookmarks(all: List<Bookmark>): List<Bookmark>` (drops PENDING_DELETE, sorted by `timeSec` then `createdAt`).
  - Merge rules (all for ONE book; identity = `timeSec`): a server row with no local row becomes a SYNCED row; a server row with a SYNCED local row replaces it (server title and createdAt win); a server row with a PENDING_CREATE local row: the local row stays PENDING_CREATE... NO: the server already has it, so the result is the server row (SYNCED) and nothing is created; with a PENDING_RENAME local row the local row stays and goes into `toRename`; with a PENDING_DELETE local row the local row stays and goes into `toDelete`; a local SYNCED row missing on the server is dropped (deleted elsewhere); a local PENDING_CREATE row missing on the server stays and goes into `toCreate`; a local PENDING_RENAME row missing on the server is dropped (the server deleted it; the rename loses); a local PENDING_DELETE row missing on the server is dropped (already gone). `result` is sorted by `timeSec`.
- `History.kt`: `data class HistoryEntry(val bookId: String, val positionMs: Long, val recordedAt: Long)`; `fun jumpedFrom(previousPositionMs: Long, previousAtMs: Long, positionMs: Long, atMs: Long, speed: Float, isPlaying: Boolean, minJumpMs: Long = 60_000): Long?` returns the PREVIOUS position when the new position differs from where normal playback would have reached by at least `minJumpMs` (expected = previous + elapsed wall time * speed while playing, = previous while not playing; a non-positive speed counts as 1; elapsed never negative), else null; `fun addToHistory(existing: List<HistoryEntry>, entry: HistoryEntry, max: Int = 10, dedupeMs: Long = 5_000): List<HistoryEntry>` (newest first; an existing entry of the same book within `dedupeMs` of the new position is replaced by the new one; trimmed to `max`); `fun historyAgoLabel(recordedAt: Long, nowMs: Long): String` ("just now" under 1 min, "N min ago" under an hour, "N h ago" under a day, "N d ago", future times "just now").
- [ ] Failing tests first with exact values, run, implement, run, commit "Stage 2: bookmark and history rules".

### Task 8b.2: Server bookmark calls, Room v5 and the repository
- `AbsApiService`: `getMeBookmarks(baseUrl, token): List<AbsBookmarkDto>` (from `/api/me`, ignoring everything else), `createBookmark(baseUrl, token, itemId, timeSec, title)`, `deleteBookmark(baseUrl, token, itemId, timeSec)`, `renameBookmark(baseUrl, token, itemId, timeSec, title)`; DTO `AbsBookmarkDto(libraryItemId, time: Double, title, createdAt: Long)` with `toBookmark(bookId)` (seconds rounded, `abs_` prefix applied); fixtures `abs/bookmarks.json` (synthetic). `LibrarySource` defaults (Plex: no-ops, empty) and ABS overrides (book ids stripped via `toServerItemId()`), through the authorizer.
- Room v5: `bookmarks` (primary key (`bookId`, `timeSec`); columns `serverId`, `title`, `createdAt`, `state`) and `position_history` (auto id; `bookId`, `positionMs`, `recordedAt`), auto-migration 4 to 5, `5.json`, DAOs (`observeForBook`, `upsert`, `delete`, `getPending`, `replaceForServer` in one transaction for the merge result; history `observeForBook` newest first, `insertAndTrim(bookId, entry, max)` transactional), schema test.
- Repository: `observeBookmarks(bookId): Flow<List<Bookmark>>` (visible ones), `addBookmark(bookId, positionMs, title: String?)`, `renameBookmark(bookId, timeSec, title)`, `deleteBookmark(bookId, timeSec)` (a PENDING_CREATE row is deleted outright, a SYNCED one becomes PENDING_DELETE), `pullBookmarks(serverId)` (fetch + `mergeBookmarks` per book + apply, returning the ops to push), `pushPendingBookmarks(serverId)` (create/rename/delete with success handling per row; a created-but-response-lost duplicate is harmless because the server identity is (book, second)), history methods.
- [ ] Tests (DTO, source paths/bodies/401, in-memory DAO fakes matching the SQL, repository rules incl. the delete-pending-create case, migration schema), commit.

### Task 8b.3: Engines
- `BookmarkSyncer` (app-scoped, `start()` idempotent): pulls then pushes at app start (when online), when connectivity returns (throttled to once per 5 minutes per server), after the library sync of a server, and a few seconds after any local bookmark change (debounced); backoff via the existing `nextBackoffMs` on failures; a refused sign-in skips that server for the pass; never throws.
- `HistoryRecorder` (app-scoped): observes `PlaybackController.state`, keeps the previous `(position, wall clock)`, and on each emission for the SAME book calls `jumpedFrom`; a non-null result becomes a history entry (previous position) through the repository; the first emission of a book and book switches only reset the baseline.
- [ ] Tests with fakes and virtual time (syncer triggers, throttle, debounce, failure backoff, two servers; recorder jumps vs normal playback, pauses, speed 1.5, chapter jumps, first emission), commit.

### Task 8b.4: Player UI
- Bottom row becomes **Speed, Sleep timer, Bookmarks, History**; Chapters stays reachable through the chapter title.
- `BookmarksSheet`: title "Bookmarks"; an **Add bookmark** button (saves at the CURRENT position at the moment of the tap, optional name field with the default as hint); a lazy list (title, clock time, tap jumps and closes, a rename action opening a small name dialog and a delete action; an undo snackbar for delete is optional); empty state "No bookmarks yet". `HistorySheet`: title "History"; rows "<clock time> · <ago label>", tap jumps (the current position is recorded by the recorder as usual); empty state "Nothing to jump back to yet".
- `PlayerViewModel` additions (bookmarks and history flows for the current book, actions, pure text helpers with tests). Sheets use the same patterns as the speed/sleep sheets.
- [ ] View-model tests first, then screens; emulator checks by the controller.

### Task 8b.5: Verification, docs, PR (controller)
- Full suite, debug and minified release builds; emulator with the throwaway user: add a bookmark in the app and see it through `GET /api/me`; create one through the API and see it in the app after a refresh; rename and delete both ways; airplane mode: add offline, then online delivers it; delete a pending-create bookmark offline (never reaches the server); History after a big slider seek and a chapter jump; privacy scan; docs (`ROADMAP.md` bookmarks item, `CLAUDE.md`); final whole-branch review (opus), ONE fix wave, scoped re-review; PR "Stage 2: bookmarks and history (step 8b)"; merge as authorised once CI is green.

## Delegation to the local models
Task 8b.1 goes to gemma4:26b through OpenClaw (tests first, one file per call, header dictated, "common Kotlin only"). Everything else goes to Claude implementers or the controller.
