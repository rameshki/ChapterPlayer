# Stage 3 step 4: play downloaded books offline, and the Android Auto Downloaded tab

Spec: `docs/superpowers/specs/2026-10-24-stage-3-downloads-offline-design.md` (items 7 and 9). Branch `stage-3/offline-playback`. Read `CLAUDE.md` (Playback, Progress, Android Auto, Downloads paragraphs) first. Main has: downloads in Room (`downloads`, `download_files` with local `path`, status DONE), `DownloadStates`, `DownloadOverview`, the Downloads UI, and the emulator-verified downloader.

## Global constraints
- `PlaybackStarter` stays the ONE load path (phone and car). Public repo: synthetic data only. commonMain stays portable.
- Progress must keep working offline: local save every 10 s already exists; the pending-progress queue delivers when the network returns. A downloaded book opened while ONLINE still opens a normal ABS playback session (live sync of listening time, as today) but streams from the local files instead of the network; offline it plays with no session at all.

## Task 1: the decision (pure, commonMain, tested)
`domain/playback/PlaybackSource.kt`: given book download state (DONE with all files present or not), online flag and `downloadedOnly`, decide `Stream`, `Local`, or `Unavailable(reason)`: online + DONE -> `Local` (session still opened for sync); online + not downloaded -> `Stream`; offline + DONE -> `Local`; offline + not downloaded -> `Unavailable` (existing "needs a connection" wording); Plex stays unavailable as today. A DONE row whose files are missing on disk is treated as not downloaded (and the row is marked FAILED so the UI offers "Try again").

## Task 2: starter and player
Teach `PlaybackStarter` / the item builder in `AndroidPlaybackController` to build the media items from the local files (file URIs, same multi-file timeline mapping and chapter offsets as streaming, durations from the stored book) when the decision is `Local`. Offline: skip the `/play` call, keep the progress saver and the pending-progress queue, do not show errors for failed session sync. If the network drops while streaming a book that is downloaded, nothing changes (it already plays locally). Host tests for item building and the decision wiring; no real ExoPlayer in host tests.

## Task 3: Android Auto
`domain/car/CarBrowse.kt`: the Downloaded tab lists real downloaded books (same row rules; playable even offline) instead of the placeholder note; empty -> a single note "No downloaded books yet". `CarData` carries what it needs (download state is already inside `BrowseItem`). Offline, the existing banner and dimming stay. Update `CarBrowseTest`.

## Task 4: docs
Update `CLAUDE.md` (Downloads paragraph: offline playback) and `docs/ROADMAP.md` (offline mode item; Android Auto Downloaded tab; keep unverified items unchecked: car checks, reboot restore).

## Finish
`:shared:testAndroidHostTest`, `:androidApp:assembleDebug`, `:androidApp:assembleRelease` green; privacy scan; commits with trailers `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey` (exactly "Claude Sonnet 5.5"). Never push, never dispatch subagents. Report to `C:/Users/Robert/AppData/Local/Temp/claude/C--Users-Robert-ClaudeProjects-ChapterPlayer/5951ad6b-9e9b-494e-886e-6d21b9103272/scratchpad/stage3-4-report.md`; return only status, commits, one-line test count, concerns. The controller checks it on the emulator afterwards.
