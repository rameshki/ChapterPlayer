# Stage 2 step 8a: Progress saving, server sync and the offline queue

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Where you are in a book follows you: the phone saves your position every 10 seconds while playing, Audiobookshelf learns it through the playback session, and when the server cannot be reached the latest position is queued and delivered later. Opening a book starts at the most recent position between this phone and the server, and progress made on other devices shows up here after a library sync. (Bookmarks and History are step 8b.)

**Architecture:** Two small app-scoped engines read `PlaybackController.state`: the existing `PlaybackProgressSaver` (local Room save; now also every 10 s) and a new `ProgressSyncer` (every ~10 s while playing it sends the position through the active session; on failure it writes one row per book into a `pending_progress` table). A `PendingProgressFlusher` delivers the queue on app start, when the network returns and on a backoff timer, comparing timestamps first so an older queued position never overwrites newer progress made elsewhere. All decisions (what is newer, how long to back off, when to save, how to merge) are pure code in `domain/playback` with unit tests.

**Tech Stack:** Room (v3 to v4 auto-migration), Ktor + kotlinx.serialization, Koin, kotlinx.coroutines.

**Spec:** `docs/superpowers/specs/2026-10-14-stage-2-abs-core-design.md` (section Progress; build-order step 8).

## What the live server does (checked 2026-10-20 against v2.37.1 with a throwaway user; structure only)
- `GET /api/me` returns `mediaProgress[]` (each `{libraryItemId, duration, progress, currentTime, isFinished, lastUpdate (ms), startedAt, finishedAt, ...}`) and `bookmarks[]` (`{libraryItemId, time, title, createdAt}`).
- `POST /api/session/{id}/sync` body `{currentTime, timeListened, duration}` gives 200 "OK" (the session is the one from `POST /api/items/{id}/play`; `POST /api/session/{id}/close` with the same kind of body ends it).
- `GET /api/me/progress/{libraryItemId}` returns one `mediaProgress` object (404 when the user has none).
- `PATCH /api/me/progress/{libraryItemId}` body `{currentTime, duration, progress, isFinished}` gives 200 and sets `lastUpdate` to the server's now.

## Rulings made while planning
- "Most recent wins": local timestamp is `ReadingProgress.lastPlayedAt`, server timestamp is `mediaProgress.lastUpdate`; equal or missing timestamps prefer the LOCAL position (the phone is what the user holds).
- A queued position is delivered with `PATCH /api/me/progress/{id}` (not through a session: the session may be gone), but only after a `GET` shows the server has nothing newer than the queued `updatedAt`; otherwise the queued row is dropped and the server's progress is stored locally.
- One queue row per book (latest position wins, listened time accumulates). Backoff: 5 s, 10 s, 20 s, 40 s, then 5 minutes (cap), reset after a success; the queue also flushes on app start and when connectivity returns.
- A book the server marks finished is finished here; un-finishing is not synced in this step.
- Server progress is merged into the local table during the library sync of an ABS server (from `/api/me`), never over a local row that is newer or that has a pending queue row.
- The device id sent with sessions stays the constant for now.

## Global Constraints
- Local save first: a failing network never prevents the local save. A save or sync failure never interrupts playback or shows an error banner (silent, retried).
- Never send a position of 0 for a book never played; never send progress for a book without an active ABS server; never log tokens, URLs with tokens or response bodies.
- The periodic work runs only while playing and stops promptly on pause (final save + final sync on pause, on book switch and on release).
- Public repo: synthetic test data only; fixtures never contain real server data. `commonMain` stays portable (no `putIfAbsent`, `String.format`, `java.*`, `System.*`); Android-only code in `androidMain`.
- Room: schema export file committed (`shared/schemas/.../4.json`), auto-migration tested by the existing schema tests pattern.
- Files in the working tree are CRLF: check every edit applied. Commits end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey`.

## Review Focus
1. Offline for a long time with several books played: one row per book, the latest position, accumulated listened time, flushed in a sensible order with backoff; app killed while rows are queued (they survive and flush at next start).
2. Clock and timestamp problems: device clock wrong or behind the server; `lastUpdate` missing; a queued row older than server progress made on another device (must NOT overwrite it); the same book played on two devices.
3. A session that the server has closed or expired (sync returns 404/401): fall back to the queue path, never loop; a 401 goes through the existing refresh path, a refused refresh stops syncing quietly until sign-in.
4. Many ticks: no unbounded coroutines, no sync after pause, no sync storm after reconnect (one flush at a time), a flush racing with a new play of the same book.
5. Merge on library sync: never overwrite newer local progress; a finished book on the server; a book that exists only on the server's progress list (not in the cache); progress for a server library that is switched off.

---

## File structure
- `shared/src/commonMain/kotlin/com/chapterplayer/domain/playback/ProgressSync.kt` (new, pure), `ProgressRules.kt` (extend).
- `shared/src/commonMain/kotlin/com/chapterplayer/data/progress/` (new): `ProgressSyncer.kt`, `PendingProgressFlusher.kt`, `PendingProgress.kt` (model), `ServerProgress.kt` (model).
- `shared/src/commonMain/.../data/local/` Room: entity `PendingProgressEntity`, `PendingProgressDao`, `ChapterPlayerDatabase` v4 (+ schema `4.json`).
- `shared/src/androidMain/.../data/remote/abs/` DTOs `AbsProgressDto.kt`, `AbsApiService` (`syncSession`, `getProgress`, `patchProgress`, `getMe`), `data/source/AbsLibrarySource.kt` + `LibrarySource.kt` (new methods with defaults), `BookRepository(Impl)`.
- `PlaybackState.sessionId` (controller), `PlaybackProgressSaver` (periodic), Koin, `ChapterPlayerApp` (start the engines).

---

### Task 8a.1: Progress rules (pure functions)  [local-model task: tests first]
**Interfaces (all in `domain/playback`):**
- `data class ServerProgress(val positionMs: Long, val durationMs: Long, val isFinished: Boolean, val updatedAtMs: Long)`.
- `fun chooseStartPosition(local: ReadingProgress?, server: ServerProgress?): Long?`: null when neither exists; only local: local position (0 when `isFinished` and at the end... keep the existing `startPositionFor` meaning: finished or at the end gives 0); only server: the server position with the same finished rule (finished or within the finished window of the duration gives 0); both: the one with the later timestamp (`local.lastPlayedAt` vs `server.updatedAtMs`), ties and a non-positive server timestamp prefer local.
- `fun shouldSaveNow(lastSavedAtMs: Long, nowMs: Long, isPlaying: Boolean, intervalMs: Long = 10_000): Boolean`: only while playing and at least the interval since the last save; a clock that went backwards (`now < lastSaved`) saves.
- `fun shouldSyncNow(lastSyncedAtMs: Long, nowMs: Long, isPlaying: Boolean, intervalMs: Long = 10_000): Boolean`: the same rule (separate name so the intervals can differ later).
- `fun nextBackoffMs(attempts: Int): Long`: 0 or fewer attempts give 0; 1 -> 5_000, 2 -> 10_000, 3 -> 20_000, 4 -> 40_000, 5 and more -> 300_000.
- `data class PendingProgress(val bookId: String, val serverId: String, val positionMs: Long, val durationMs: Long, val listenedMs: Long, val updatedAtMs: Long, val attempts: Int = 0)` and `fun coalescePending(existing: PendingProgress?, incoming: PendingProgress): PendingProgress`: null existing returns incoming; otherwise the incoming position/duration/updatedAt (the LATEST by `updatedAtMs`; an incoming that is older than the existing keeps the existing position), `listenedMs` summed, `attempts` reset to 0 when the incoming is newer.
- `fun flushDecision(pending: PendingProgress, server: ServerProgress?): FlushDecision` with `enum FlushDecision { SEND, DROP_SERVER_NEWER }`: SEND when the server has no progress or its `updatedAtMs` is not newer than `pending.updatedAtMs`; DROP_SERVER_NEWER when the server's `updatedAtMs` is strictly newer.
- `fun mergeServerProgress(local: ReadingProgress?, server: ServerProgress, hasPendingLocal: Boolean): ReadingProgress?`: null when nothing should change (a pending local row, or local at least as new); otherwise a `ReadingProgress` built from the server (bookId taken from local when present else the caller sets it: take a `bookId: String` parameter) with `lastPlayedAt = server.updatedAtMs`.
- [ ] Failing tests first (exact values: both timestamps, ties, finished window, backoff table, coalescing order and sums, flush decision boundaries, merge cases), run, implement, run, commit "Stage 2: progress sync rules".

### Task 8a.2: Server calls and the pending queue (data)
- ABS: `AbsApiService.syncSession(baseUrl, token, sessionId, positionSec, listenedSec, durationSec)`, `getProgress(baseUrl, token, itemId): AbsProgressDto?` (404 gives null), `patchProgress(...)`, `getMe(...)` returning `mediaProgress[]` (bookmarks are ignored here; step 8b reads them). DTOs + fixtures `abs/me.json`, `abs/progress.json` (synthetic). Through the authorizer (401 refresh once).
- `LibrarySource` defaults (Plex unaffected): `suspend fun syncPlayback(server, sessionId, positionMs, listenedMs, durationMs)`, `fetchProgress(server, bookId): ServerProgress?`, `pushProgress(server, bookId, positionMs, durationMs, isFinished)`, `fetchAllProgress(server): Map<String, ServerProgress>` (keys are the app's book ids with the `abs_` prefix).
- Room v4: `pending_progress` (primary key `bookId`), `PendingProgressDao` (`upsert`, `get`, `observeAll`, `delete`, `incrementAttempts`), auto-migration 3 to 4, schema json, `ProgressRepository`/`BookRepository` methods (`syncPlayback`, `queueProgress` (coalescing in one transaction), `pendingProgress()`, `deletePending`, `flushOne`).
- [ ] Tests (parsing, paths and bodies, 401 refresh, 404 progress, queue coalescing inside the DAO fake matching the real SQL, migration schema test), commit.

### Task 8a.3: The engines
- `PlaybackState.sessionId: String?` (set by the controller from the active session; null when none).
- `PlaybackProgressSaver`: also saves every 10 s while playing (`shouldSaveNow`), keeping the existing stop/switch/release saves.
- `ProgressSyncer` (app-scoped, started from `ChapterPlayerApp`): while `isPlaying` ticks (`shouldSyncNow`), sends `syncPlayback` for the active session with the position, the listened time since the last sync and the duration; on any failure (including no session) calls `queueProgress`; final sync on pause/stop/book switch; never throws.
- `PendingProgressFlusher`: runs at app start, whenever `ConnectivityMonitor` turns online, and on the backoff timer; one flush at a time (a mutex); for each queued row: `fetchProgress` then `flushDecision`; SEND -> `pushProgress` and delete on success, bump attempts on failure; DROP_SERVER_NEWER -> store the server progress locally and delete the row; a refused sign-in (`SignInRequiredException`) stops quietly until the next trigger.
- Library sync (`LibrarySyncCoordinator` path for ABS servers): fetch `fetchAllProgress` and apply `mergeServerProgress` (skipping books with pending rows) inside the sync, best effort (a failure never fails the sync).
- `PlaybackStarter`: when `atMs == null` and online, ask `fetchProgress` for the book (short timeout, ~2 s) and use `chooseStartPosition(local, server)`; offline or on failure use local only (today's behaviour).
- [ ] Tests first with fakes: ticks and final syncs, failure to queue, no sync when paused, flusher decisions and backoff scheduling (virtual time), mutex, the merge on sync, the starter's choice. Commit(s).

### Task 8a.4: Verification, docs, PR (controller)
- Full suite, `assembleDebug`, minified `assembleRelease`; emulator with a throwaway user: play and watch `GET /api/me/progress/{id}` advance every ~10 s; airplane mode while playing, then back online: the queue row appears (read the debug DB) and flushes; a newer server progress made through the API beats an older queued row; progress changed through the API on "another device" shows on Home after a library refresh; privacy scan; `docs/ROADMAP.md` (progress sync item) and `CLAUDE.md`; final whole-branch review (opus), ONE fix wave, scoped re-review; PR "Stage 2: progress sync and the offline queue (step 8a)"; merge as authorised once CI is green.

## Delegation to the local models
Task 8a.1 goes to gemma4:26b through OpenClaw (tests first, one file per call, header dictated, "common Kotlin only"); the rest to Claude implementers.
