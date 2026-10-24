# Stage 3 step 1: download rules, Room tables, real DownloadStates

Spec: `docs/superpowers/specs/2026-10-24-stage-3-downloads-offline-design.md`. Branch `stage-3/download-rules`. No UI, no network code in this step.

## Global constraints
- `commonMain` portable (no JVM-only APIs: no `sortedMapOf`, `System`, `java.*`). Public repo: synthetic data only.
- Room v5 -> v6 with `AutoMigration(from = 5, to = 6)`; commit the generated `shared/schemas/.../6.json`.
- Follow `BookmarkEntity` / `HistoryDao` style. Host tests in `shared/src/androidHostTest` (`./gradlew :shared:testAndroidHostTest`). `KoinModulesTest` must stay green.

## Task 1: pure rules, `shared/src/commonMain/kotlin/com/chapterplayer/domain/downloads/DownloadRules.kt` (tests first, `DownloadRulesTest.kt`)
- `enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, FAILED, DONE }`.
- `data class FileProgress(val totalBytes: Long, val doneBytes: Long)`.
- `fun overallFraction(files: List<FileProgress>): Float` — sum done / sum total, clamped 0..1; 0 when total is 0.
- `class SpeedTracker(windowMs: Long = 30_000)` with `fun add(nowMs: Long, bytesTotalSoFar: Long)` and `fun bytesPerSecond(): Double` (rolling window over samples; 0 with fewer than 2 samples or no time passed; a counter that goes backwards resets the window).
- `fun etaSeconds(remainingBytes: Long, bytesPerSecond: Double): Long?` — null when speed <= 0 or nothing to compute; 0 when remaining <= 0.
- `fun nextFileIndex(files: List<FileProgress>): Int?` — first file not complete, null when all complete.
- `fun validResumeOffset(partFileLength: Long, recordedDoneBytes: Long, totalBytes: Long): Long` — the offset to request: `partFileLength` if it is between 0 and totalBytes and is <= recordedDoneBytes + 1 MiB tolerance... simpler rule: use min(partFileLength, recordedDoneBytes) when both within 0..totalBytes, otherwise 0.
- `fun backoffMs(attempt: Int): Long` — 5 s doubling, capped at 5 min; attempt < 1 treated as 1.
- `enum class NetworkPolicy { WIFI_ONLY, WIFI_AND_CELLULAR }` and `fun mayDownload(policy, onUnmetered: Boolean, online: Boolean): Boolean`.
- `fun hasRoomFor(neededBytes: Long, freeBytes: Long, limitBytes: Long?, usedBytes: Long, safetyBytes: Long = 100L * 1024 * 1024): Boolean` — false if free < needed + safety, or if limit set and used + needed > limit.

## Task 2: Room v6
- `DownloadEntity` (table `downloads`): `bookId` PK, `serverId`, `status` (String, a `DownloadStatus` name), `totalBytes`, `error: String?`, `updatedAt`.
- `DownloadFileEntity` (table `download_files`): primary key (`bookId`, `ino`), `fileName`, `path`, `totalBytes`, `doneBytes`, `sortOrder`.
- `DownloadDao`: `observeAll(): Flow<List<DownloadEntity>>`, `observeFiles(): Flow<List<DownloadFileEntity>>`, `getFiles(bookId)`, `get(bookId)`, `upsert(download)`, `upsertFiles(files)`, `updateStatus(bookId, status, error, updatedAt)`, `updateFileProgress(bookId, ino, doneBytes)`, `delete(bookId)` (and its files in one `@Transaction deleteBook`).
- Register entities, DAO accessor `downloadDao()`, bump to version 6 with the auto-migration. Add to `RoomSchemaTest` the way earlier tables are covered.
- DAO tests the way `BookmarkDao`/`HistoryDao` are tested in the repo (look at the existing DAO test pattern).

## Task 3: real `DownloadStates`
- `RoomDownloadStates(dao: DownloadDao) : DownloadStates` in `commonMain/data` mapping: DONE -> `DownloadState.DOWNLOADED`; QUEUED/DOWNLOADING/PAUSED/FAILED -> `DOWNLOADING`; no row -> missing from the map. Keep `NoDownloadStates` for tests only.
- Replace the Koin binding in `di/KoinModules.kt` (line ~136) with `RoomDownloadStates(get<ChapterPlayerDatabase>().downloadDao())` following how other DAOs are provided; register `DownloadDao` if DAOs have their own bindings.
- Tests for the mapping with a fake DAO flow or the in-memory DB used by other DAO tests.

## Finish
`:shared:testAndroidHostTest` and `:androidApp:assembleDebug` green; privacy scan; commit in a few commits with trailers `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey`. Never push.
