# Stage 2 step 6: Playback service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapping Resume on a book's details screen plays the book (streamed from Audiobookshelf), with chapters, speed, sleep timer, skip 15 back / 30 forward, lock-screen and notification controls, rewind on resume and pause when headphones are unplugged. Tapping a chapter jumps to it.

**Architecture:** One Media3 `MediaLibraryService` hosts one ExoPlayer; everything else (details screen now, mini player and full player in step 7, Android Auto in step 9) talks to it through a Media3 `MediaController` wrapped by the Android `PlaybackController`. The decisions (which file and offset for a book position, sleep timer maths, rewind rule, skip clamping, speed steps) are pure code in `domain/playback` with unit tests. A book with several audio files plays as ONE continuous timeline: the player holds one media item per file, and the book position is `file start offset + position in file`. Audiobookshelf is asked for a playback session (`POST /api/items/{id}/play`), which returns the audio tracks; the audio itself is fetched with the user's bearer token, refreshed when it expires.

**Tech Stack:** Media3 (`media3-exoplayer`, `media3-session`, `media3-common`, `media3-datasource`) on Android only, Ktor + kotlinx.serialization, Koin, Compose Multiplatform.

**Spec:** `docs/superpowers/specs/2026-10-14-stage-2-abs-core-design.md` (sections Player, One playback engine for phone and car, Playing a book; build-order step 6).

## What the live server does (checked 2026-10-20 against v2.37.1, structure only)
- `POST /api/items/{id}/play` with a JSON body `{deviceInfo:{clientName,deviceId}, forceDirectPlay:true, forceTranscode:false, supportedMimeTypes:[...], mediaPlayer:"exoplayer"}` returns 200 with `id` (the session id), `audioTracks[]` (each: `index`, `startOffset` seconds, `duration` seconds, `mimeType` such as `audio/mp4`, `contentUrl` such as `/api/items/<itemId>/file/<ino>`, plus file metadata), `chapters[]`, `duration`, `startTime`, `currentTime`, `playMethod` (0 = direct play).
- The `contentUrl` needs `Authorization: Bearer <token>` (401 without) and supports range requests (206).
- `POST /api/session/{id}/close` with `{currentTime, timeListened}` ends the session (200).

## Global Constraints
- Skip back **15 s**, skip forward **30 s**; the numbers are on the buttons (step 7 draws them; the service offers the same increments to the notification and car).
- Speed steps: 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0 (default 1.0, remembered per app run for now).
- Sleep timer: Off, 5, 10, 15, 30, 45, 60 minutes, or End of chapter; the timer counts time spent PLAYING at the current speed-independent wall clock (minutes) and pauses playback when it reaches zero (a short fade is optional, a clean pause is enough).
- Rewind on resume (after pausing): paused under 10 s: 0 s; 10 s to under 5 min: 5 s; 5 min to under 60 min: 10 s; 60 min or more: 20 s; never before 0.
- Pause when headphones are unplugged or Bluetooth disconnects (`setHandleAudioBecomingNoisy(true)`); take audio focus (`setAudioAttributes(AUDIO_CONTENT_TYPE_SPEECH, handleAudioFocus = true)`), duck/pause for calls like any media app.
- Playing is blocked when the book is not playable (offline and not downloaded: `isPlayable(...)` from `domain/browse/OfflineRules.kt`); the Resume button then shows why.
- Android: foreground service type `mediaPlayback`, notification channel for playback, `POST_NOTIFICATIONS` requested at first play on Android 13+ (playback still works if denied), `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions, service `exported=true` only as Media3 requires (`androidx.media3.session.MediaLibraryService` intent filter), no secrets in logs.
- Public repo: synthetic test data only; never real server data; tokens never logged or in exception text; the bearer is added per request, never stored in a MediaItem URL.
- `commonMain` stays portable: no JVM-only APIs (`putIfAbsent`, `String.format`, `java.*`); Media3 code lives in `androidMain`.
- Files in the working tree are CRLF: after every edit check that it applied.
- Commits end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey`.

## Review Focus
1. A book with several audio files (gapless playlist): positions at a boundary, in a gap, past the end, negative; seeking across files; chapters that straddle two files.
2. Token expiry in the middle of a long book (ExoPlayer re-opens the stream with a stale bearer): refresh once and retry; a refused refresh surfaces "Sign in again" instead of looping.
3. The session start failing (offline, 401, 5xx, server removed) or the book deleted on the server: an error line on the details screen, nothing stuck in "loading", no foreground service left running.
4. Service lifecycle: the app swiped away while playing (keeps playing), the user pausing for a long time (service stops itself, notification goes), a second book started while one plays (the first session is closed), process death and the controller reconnecting.
5. Audio focus loss (call), becoming noisy, and rewind-on-resume not rewinding past the start or applying to a fresh start.

---

## File structure
- `shared/src/commonMain/kotlin/com/chapterplayer/domain/playback/` (new): `PlaybackTimeline.kt`, `SleepTimer.kt`, `ResumeRules.kt`, `PlaybackSpeeds.kt`.
- `shared/src/commonMain/kotlin/com/chapterplayer/data/playback/PlaybackSession.kt` (new): `PlaybackSession`, `PlaybackTrack`; `PlaybackController.kt` (extend).
- `shared/src/androidMain/.../data/remote/abs/dto/AbsPlaybackDto.kt` (new); `AbsApiService.kt`, `data/source/AbsLibrarySource.kt`, `LibrarySource.kt` (modify).
- `shared/src/androidMain/.../data/playback/` (new): `PlaybackService.kt`, `AndroidPlaybackController.kt`, `AuthorizingDataSourceFactory.kt`, `PlayerSettings.kt`.
- `androidApp/src/main/AndroidManifest.xml` (modify); `gradle/libs.versions.toml`, `shared/build.gradle.kts` (Media3 dependencies); `di/KoinModules.kt`.
- `shared/src/commonMain/kotlin/com/chapterplayer/ui/details/` (modify): `BookDetailsViewModel`, `BookDetailsScreen` (Resume, chapter taps).

---

### Task 6.1: Playback logic (pure functions)  [local-model task: tests first]
**Files:** create the four `domain/playback` files and their tests.

**Interfaces:**
- `data class TrackSpan(val index: Int, val startMs: Long, val durationMs: Long)`; `data class TrackPosition(val trackIndex: Int, val offsetMs: Long)`.
- `fun locate(tracks: List<TrackSpan>, positionMs: Long): TrackPosition?`: null for no tracks; the track whose `[startMs, startMs + durationMs)` holds the position; a position before the first start gives track 0 offset 0; in a gap or after the last end gives the end of the last track that started (offset = its duration... clamp so offset never exceeds the track's duration); tracks are taken in order given.
- `fun bookPosition(tracks: List<TrackSpan>, trackIndex: Int, offsetMs: Long): Long`: `tracks[trackIndex].startMs + offsetMs.coerceIn(0, durationMs)`; an unknown index gives 0.
- `sealed interface SleepTimer { data object Off; data class After(val minutes: Int); data object EndOfChapter }` and `fun sleepRemainingMs(timer: SleepTimer, playedMs: Long, positionMs: Long, chapters: List<Chapter>, speed: Float): Long?`: Off gives null; After gives `max(0, minutes*60_000 - playedMs)`; EndOfChapter gives the wall-clock time to the end of the current chapter (`currentChapterIndex`) divided by speed, rounded up, never negative; null when there are no chapters.
- `fun rewindOnResumeMs(pausedForMs: Long): Long` per Global Constraints; `fun resumeStart(positionMs: Long, pausedForMs: Long): Long = (positionMs - rewindOnResumeMs(pausedForMs)).coerceAtLeast(0)`.
- `fun skipBackTo(positionMs: Long, byMs: Long = 15_000): Long` (not below 0) and `fun skipForwardTo(positionMs: Long, durationMs: Long, byMs: Long = 30_000): Long` (not above duration).
- `val PLAYBACK_SPEEDS: List<Float>` per Global Constraints and `fun nextSpeed(current: Float): Float` (next higher step, wraps to the first after the last; a value not in the list goes to the next higher step).
- [ ] Failing tests with exact values (boundaries, gaps, one track, empty, negative, past the end, 3 h 59 min sleep, EndOfChapter at the last ms of a chapter, speed 2.0 halves the wall clock, rewind thresholds at 9 999 / 10 000 ms and 299 999 / 300 000 ms etc.). Run, implement, run, commit "Stage 2: playback logic".

### Task 6.2: Start a playback session
**Files:** `PlaybackSession.kt`, `AbsPlaybackDto.kt`, `AbsApiService.startPlayback/closeSession`, `LibrarySource.startPlayback/closePlayback` (defaults: `null` / no-op so Plex keeps compiling), `AbsLibrarySource` override; fixtures `abs/play-session.json` (synthetic, the shape above, 2 tracks plus extra fields to ignore) and tests.

**Interfaces:**
- `data class PlaybackTrack(val index: Int, val startMs: Long, val durationMs: Long, val mimeType: String, val url: String)` where `url` is absolute (server base + `contentUrl`, no token in it).
- `data class PlaybackSession(val sessionId: String, val serverId: String, val bookId: String, val durationMs: Long, val startPositionMs: Long, val tracks: List<PlaybackTrack>, val chapters: List<Chapter>)`.
- `LibrarySource.startPlayback(server: ServerConfig, bookId: String): PlaybackSession?` (ABS: strips `abs_`, POSTs the body from the plan, seconds to ms with rounding, absolute urls, chapters via the existing chapter mapping, `startPositionMs` = the server's `currentTime`); `LibrarySource.closePlayback(server: ServerConfig, sessionId: String, positionMs: Long, listenedMs: Long)`.
- Through the same authorizer as the other calls (a 401 refreshes once).
- [ ] Tests: the fixture parses; ms conversion exact; request path/body/headers; absolute url building (trailing slash in the base address); 401 then success; 500 throws; close posts the right body; tracks are ordered by index. Commit "Stage 2: start and close Audiobookshelf playback sessions".

### Task 6.3: Playback service and controller (Android)
**Files:** Media3 dependencies, manifest, `PlaybackService`, `AuthorizingDataSourceFactory`, `AndroidPlaybackController`, `PlayerSettings`; Koin; tests for what is testable on the host (state mapping, the data-source header logic as a pure function, session-to-media-item mapping).

**Interfaces and behaviour:**
- Extend `PlaybackController` (commonMain) with what the UI needs and keep the existing members: `val sleepTimer: StateFlow<SleepTimer>`, `fun setSleepTimer(timer: SleepTimer)`, `fun skipBack()`, `fun skipForward()`, `fun seekToChapter(index: Int)`, `suspend fun load(book: AudioBook, startMs: Long)` stays and now returns `Result<Unit>`-style by throwing `PlaybackStartException(reason)` with a safe message; `PlaybackState` gains `error: String?`, `isBuffering: Boolean`, `sleepRemainingMs: Long?`, `chapters: List<Chapter>`.
- `PlaybackService : MediaLibraryService` owns an `ExoPlayer` built with: `AuthorizingDataSourceFactory` (adds `Authorization: Bearer <current token for the book's server>` in a `ResolvingDataSource` resolver, token from the session manager, refreshed when expired; the server id travels in the MediaItem's extras), speech audio attributes with focus handling, `setHandleAudioBecomingNoisy(true)`, `seekBackIncrementMs = 15_000`, `seekForwardIncrementMs = 30_000`, wake mode network, a `MediaLibrarySession` with the player, notification via Media3's default provider showing play/pause and the skip buttons, foreground type `mediaPlayback`; the service stops itself after a long pause.
- `AndroidPlaybackController` connects a `MediaController` to the service (lazily, reconnecting after process death), implements `load` (start the ABS session via the repository/source, build one `MediaItem` per track with the book metadata (title, author, cover uri) and the session id in extras, `setMediaItems` + `seekTo(trackIndex, offset)` from `locate`, `prepare`), position/state mapping through `bookPosition` and `currentChapterIndex`, a ticking `StateFlow` (about every 500 ms while playing), speed, sleep timer (a small coroutine ticker using `sleepRemainingMs`), `skipBack/skipForward` via `skipBackTo/skipForwardTo`, `seekToChapter`, rewind on resume via `resumeStart` on `play()` after a pause, closes the previous session when a different book loads and on `release()`.
- Position/duration mapping: while several media items are queued the player reports per-item positions; the controller converts with `bookPosition`.
- [ ] Failing tests for the pure mapping parts first; then implement; `KoinModulesTest` passes; `assembleDebug` passes. Commit(s) "Stage 2: Media3 playback service and controller".

### Task 6.4: Play from the details screen
**Files:** `BookDetailsViewModel` (+ state), `BookDetailsScreen`/`ChaptersTab` (Resume and chapter taps), strings, tests.
- Resume is enabled when `isPlayable(...)` and the book has a server; it starts at the saved local position (`ReadingProgress.positionMs`, finished books start from 0), plays, and then shows **Pause** while this book plays (and **Resume** again after pausing); while the session starts it shows a progress indicator and is disabled; failures show the error line with Retry.
- Tapping a chapter row seeks the playing book to that chapter, or starts the book at that chapter when nothing plays; the "Playback arrives in a later update" captions go away; the "Tap a chapter to jump to it once playback is added" note becomes "Tap a chapter to jump to it".
- Local progress is saved when playback pauses or stops (position, duration, last played); the full periodic save and server sync are step 8.
- Offline and not downloaded: Resume disabled with "Needs a connection to play".
- [ ] View-model tests first (fake `PlaybackController`): enabled/disabled rules, start position, toggling Resume/Pause by state, chapter tap seeks, errors, progress saved on pause. Commit "Stage 2: play from the book details screen".

### Task 6.5: Verification, docs, PR (controller)
- Full suite, `assembleDebug`, minified `assembleRelease` (R8 keep rules for Media3 if needed) signed and run; emulator pass with a throwaway Audiobookshelf user: play a short book, listen position advancing (`adb shell dumpsys media_session`), pause/resume, chapter jump, speed, sleep timer, skip buttons from the notification shade, lock screen controls, headphone-unplug equivalent (`adb shell cmd media_session dispatch pause`), swipe the app away while playing, a multi-file book if one exists, airplane mode mid-play, token refresh; privacy scan; `docs/ROADMAP.md` (Media3 playback item) and `CLAUDE.md` notes; final whole-branch review (opus) and ONE fix wave with scoped re-review; PR "Stage 2: playback service (step 6)"; merge only with the owner's go-ahead.

## Delegation to the local models
Task 6.1 (and any further pure function that appears) goes to gemma4:26b through OpenClaw with tests first; the DTO + mapper part of 6.2 likewise (one file per call, header dictated, "common Kotlin only" in the prompt). Service, controller, Compose and Koin work stay with Claude implementers.
