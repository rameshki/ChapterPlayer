# Stage 3 step 3: downloads in the UI

Spec: `docs/superpowers/specs/2026-10-24-stage-3-downloads-offline-design.md`. Branch `stage-3/downloads-ui`. Main already has: `domain/downloads` rules, Room `downloads`/`download_files`, `DownloadController` (enqueue/pause/resume/cancel/remove, commonMain), `BookDownloader` (androidMain; live `progress(bookId): Flow<BookProgress?>` with speed and ETA), `DownloadLocation` interface, `AppSettings.downloadWifiOnly`, `RoomDownloadStates`. Read `CLAUDE.md` first.

## Global constraints
- UI in `commonMain` (Compose Multiplatform), view models tested on the host like the existing ones (`HomeViewModelTest` style); follow the existing theme, components, spacing, and copy tone (plain words, no jargon). Android specifics (notification permission, storage volumes) behind small interfaces in `androidMain`.
- Public repo: synthetic data only.

## Task 1: what the UI reads (commonMain `data/downloads/DownloadOverview.kt`)
An interface `DownloadOverview { fun observe(): Flow<List<DownloadRow>> ; fun observeBook(bookId): Flow<DownloadRow?>; fun storageUsedBytes(): Flow<Long> }` with `DownloadRow(bookId, status: DownloadStatus, fraction, doneBytes, totalBytes, bytesPerSecond: Double, etaSeconds: Long?, error: String?)`. Android implementation combines the Room tables (fraction and bytes from the files) with `BookDownloader.progress(bookId)` for live speed/ETA. Pure mapping function tested on the host.

## Task 2: Book details
Add a download control to `BookDetailsScreen` for Audiobookshelf books: "Download" (shows size when known) -> progress with percent and "3h 10m left" (null ETA -> "Calculating"), Pause / Resume, Cancel, and "Remove download" with a confirm step when finished. Failed shows the stored error text and "Try again". Offline: disabled with the existing offline wording. Plex books: no control. View model methods call `DownloadController`. Request the notification permission (Android 13+) the first time a download starts, through a small `NotificationPermission` interface (`ensure()` returns whether it was granted; the download starts either way, it just has no visible notification if refused).

## Task 3: Downloads tab (replace the placeholder in `ui/downloads`)
List of downloads (cover, title, status line: percent + ETA, Queued, Paused, Failed, or size when done), Pause/Resume/Cancel/Remove per row, a header with total storage used and "Free: x GB". Empty state stays. Downloaded books open the details/player as elsewhere. Sort: active first, then by title.

## Task 4: Settings
New "Downloads" section: "Download on Wi-Fi only" switch (`AppSettings.downloadWifiOnly`; changing it must re-apply to queued jobs: re-enqueue them with the new constraint), "Download location" (internal storage or each available app-specific external volume/SD card shown with free space; changing it affects NEW downloads only, existing ones stay where they are, and the DB path per file is what playback/removal use), "Storage limit" (No limit / 1, 2, 5, 10, 20, 50 GB; `AppSettings.downloadStorageLimitBytes`, passed to `hasRoomFor` through the existing path in `BookDownloader`).

## Task 5: honest tests and docs
View-model tests for each screen's states (idle, queued, downloading, paused, failed, done, offline) and settings writes. Update `CLAUDE.md` (UI paragraph) and `docs/ROADMAP.md` (tick the Downloads tab, details button and settings items; keep unchecked what is unverified). Emulator checks are done afterwards by the controller, not by you.

## Finish
`:shared:testAndroidHostTest`, `:androidApp:assembleDebug`, `:androidApp:assembleRelease` green; privacy scan; commits with trailers `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey` (use exactly "Claude Sonnet 5.5"). Never push, never dispatch subagents. Report to `C:/Users/Robert/AppData/Local/Temp/claude/C--Users-Robert-ClaudeProjects-ChapterPlayer/5951ad6b-9e9b-494e-886e-6d21b9103272/scratchpad/stage3-3-report.md`; return only status, commits, one-line test count, concerns.
