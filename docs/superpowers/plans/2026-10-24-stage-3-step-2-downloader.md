# Stage 3 step 2: the resumable downloader (core and worker)

Spec: `docs/superpowers/specs/2026-10-24-stage-3-downloads-offline-design.md` (read all of it). Branch `stage-3/downloader`. Step 1 (rules, Room v6 `downloads`/`download_files`, `DownloadDao`, `domain/downloads/DownloadRules.kt`) is on main: use it, do not redo it. No UI in this step.

## Global constraints
- Public repo: synthetic data only; tokens never logged, never in URLs, exceptions or notifications. `commonMain` portable (no `java.*`).
- Auth exactly like the existing audio loader: bearer token per request from the server's session (`AbsAuthorizer` in `AbsSessionManager.kt`, refresh once on 401; a second 401 = `SignInRequiredException`, which stops the download as FAILED with a "Sign in again" message, no retry loop). Never follow a redirect to another host (same rule as `AuthorizingDataSourceFactory`).
- The Ktor client for downloads must have NO request/socket timeout (see the note in `HttpClientFactory.kt`, `HttpTimeouts`); stall detection is ours. Create it separately from the API client.
- Host tests in `shared/src/androidHostTest`; Ktor `MockEngine` is the fake server. One gradle command at a time, JDK 17, CRLF tree (grep after edits).

## Task 1: what to download
- Extend the item-detail DTO (`AbsItemDetailDto.kt`) with `media.audioFiles` (`ino`, `metadata.filename`, `metadata.size`, `index`, ordered by `index`); keep parsing tolerant of missing fields. Add a recorded-shaped fixture test (synthetic names, ids) like the other parser tests.
- `AbsApiService.fileDownloadUrl(baseUrl, itemId, ino)` -> `/api/items/{id}/file/{ino}/download` (path parts encoded; no token in it).
- `domain/downloads/DownloadPlan.kt` (commonMain, pure): `data class PlannedFile(ino, fileName, totalBytes, sortOrder)`, `fun planFiles(...)` that drops entries with no ino or size <= 0 and sorts by index; `fun partFileName(ino, ext)` and `fun safeFileName(name)` that strips path separators and `..` (a server filename must never escape the book folder).

## Task 2: the transfer core (androidMain, `data/downloads/FileDownloader.kt`)
`suspend fun download(request: FileRequest, onProgress: (doneBytes: Long) -> Unit): FileResult` where the request has url builder inputs, the `.part` file, the expected total, and a bearer provider:
- Resume offset = `validResumeOffset(part.length, recordedDone, total)`; request `Range: bytes=<offset>-` when > 0.
- 206 with a matching `Content-Range` start: append. 200 (server ignored Range): truncate the part file and start from 0. 416 with the file already full: treat as complete after a length check; any other 416: restart from 0. 401: refresh once (existing mechanism) and retry once.
- Stall watchdog: if no bytes arrive for `stallMs` (default 60_000, injectable for tests) cancel the request and return `Retry`. Network errors (IOException, timeouts, 5xx) -> `Retry`; other 4xx -> `Failed(message)` with fixed text (no URL, no token).
- On complete: verify length == total (size mismatch -> delete part, `Failed`), then atomically rename `.part` to the final name.
- Progress: `onProgress` called at most every 500 ms and at the end; writes use a buffered stream, `fsync` before rename.
Tests (MockEngine, temp dir): full download; resume at the right byte with correct Range header; 200-instead-of-206 restart; 416 handling; truncated body then resume; stall -> Retry; 401 refresh once; second 401 -> fails with sign-in; redirect to other host refused; size mismatch; filename traversal blocked.

## Task 3: the book job (androidMain, `data/downloads/BookDownloader.kt`)
`suspend fun run(bookId): BookResult` using `DownloadDao`: load the files (fetch `getItem` and write `download_files` rows on first run, space check with `hasRoomFor` before starting -> FAILED "Not enough space"), loop files via `nextFileIndex`, per file call `FileDownloader`, persist `doneBytes` through `updateFileProgress` (throttled ~2 s), update status QUEUED/DOWNLOADING/PAUSED/FAILED/DONE, keep a `SpeedTracker` and expose `Flow<BookProgress(fraction, bytesPerSecond, etaSeconds)>` for the UI later. Retry with `backoffMs(attempt)`; attempt counter resets after progress. Save the cover and chapters needed offline only if they are not already stored in Room (the book row already has chapters; do not duplicate). Directory: `<filesDir or chosen root>/downloads/<serverId>/<bookId>/`; root comes from a `DownloadLocation` interface (default: app files dir) so step 3 can add the Settings choice. Tests with MockEngine plus the in-memory DAO fake from step 1 (`InMemoryDownloadDao`): multi-file book resumes at the right file, failure paths, space check.

## Task 4: WorkManager and foreground notification
- Add WorkManager (version catalog) and the `FOREGROUND_SERVICE_DATA_SYNC` permission, with the WorkManager foreground service entry (`foregroundServiceType="dataSync"`) in the manifest. A `DownloadWorker` (`CoroutineWorker`, unique work name `download-<bookId>`) runs `BookDownloader`, shows an ongoing progress notification (title = book title, "42% · 3h 10m left", channel "Downloads", low importance, Pause/Cancel actions), and handles `onTimeout` (Android 15 dataSync limit): save, set status PAUSED_BY_SYSTEM-like QUEUED, and re-enqueue itself.
- Constraints from the network policy: `NetworkType.UNMETERED` for Wi-Fi only (default), `CONNECTED` otherwise. A `DownloadController` interface (commonMain, so the UI can use it later) with `enqueue(bookId)`, `pause(bookId)`, `resume(bookId)`, `cancel(bookId)` (stops and deletes files and rows), `remove(bookId)`; the Android implementation wraps WorkManager. The network policy comes from a new `AppSettings.downloadWifiOnly` (default true, no UI yet).
- On app start, re-enqueue anything QUEUED/DOWNLOADING (reboot and process death).
- Koin bindings; `KoinModulesTest` stays green. Pure parts (notification text, state decisions) are unit-tested; the WorkManager glue is thin and is checked on the emulator.

## Finish
`:shared:testAndroidHostTest`, `:androidApp:assembleDebug`, `:androidApp:assembleRelease` green; privacy scan of the diff; commit in a few commits with trailers `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey`. Never push, never dispatch subagents. Report to `C:/Users/Robert/AppData/Local/Temp/claude/C--Users-Robert-ClaudeProjects-ChapterPlayer/5951ad6b-9e9b-494e-886e-6d21b9103272/scratchpad/stage3-2-report.md`; return only status, commits, one-line test count, concerns.
