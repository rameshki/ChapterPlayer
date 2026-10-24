# Stage 3 design: downloads, offline and widgets

Goal: click a book, download it (even over a very slow tunnel), then play it with no network. Widgets follow. Scope is Audiobookshelf only (Plex cannot play yet).

## What you asked for (from the roadmap)
- One request per audio file with HTTP `Range` resume. Never the zip.
- No overall timeout, only stall detection; retry with backoff; continue the same file at the same byte after backgrounding, process death and reboot.
- Percent from bytes received, ETA from a rolling average speed, per book and overall.
- Wi-Fi only or Wi-Fi and cellular; pause and resume; storage limit; choose where files go.
- Offline mode: play downloaded books, queue progress, sync when back.

## Approach (recommended): our own resumable downloader on WorkManager
Media3's `DownloadManager` is built for streaming segments and keeps files in a cache we cannot browse or move. Audiobook files are single large files, so a plain ranged `GET` into a `.part` file is simpler, and the result is an ordinary file that ExoPlayer plays directly.

1. **Files to fetch.** `GET /api/items/{id}` already gives `audioFiles` with `ino`, size and metadata. Each file downloads from `/api/items/{id}/file/{ino}/download` with the bearer token (same `AuthorizingDataSourceFactory` refresh-once-on-401 rule). The chapter list and cover are saved with the book so the details screen works offline.
2. **Storage.** App-specific external or internal storage `downloads/<serverId>/<bookId>/<ino>.<ext>`. Download location: internal or any app-specific volume the device offers (SD card); no scoped-storage permission needed. A `.part` file is renamed on completion, and its length is the resume offset (verified against what Room recorded).
3. **State in Room (v6, auto-migration).** `downloads` (book, state QUEUED / DOWNLOADING / PAUSED / FAILED / DONE, total bytes, error) and `download_files` (book, ino, path, size, bytes done). `DownloadStates` (already used by the whole UI) becomes the Room-backed implementation, so badges, filters, offline dimming and the Android Auto "Downloaded" tab light up without UI rewrites.
4. **Worker.** One WorkManager `CoroutineWorker` per book, run as a foreground service (`dataSync`) with a progress notification. On Android 15 the 6-hours-per-24 `dataSync` limit calls `onTimeout`: the worker saves its offset, stops cleanly and re-enqueues itself, so a 30-hour download simply continues in the next window. Constraints: network type (unmetered when Wi-Fi only) so it pauses by itself when Wi-Fi drops and restarts when it returns. User-initiated data transfer jobs (no 6 h limit) are a later upgrade if the limit hurts in practice; the worker body is the same either way.
5. **Transfer.** Ktor request with `Range: bytes=<n>-`, no request timeout, a stall watchdog (no bytes for 60 s cancels and retries with backoff 5 s to 5 min). A server that answers 200 instead of 206 restarts that file from zero (never appends wrongly). Progress is flushed to Room about every 2 s or 1 MB.
6. **Pure rules (`domain/downloads`, host-tested):** rolling-average speed and ETA, overall percent, next-file choice, resume-offset validation, backoff, storage-limit check ("not enough space" before starting, using the sizes already known), Wi-Fi policy.
7. **Playback offline.** `PlaybackStarter` uses the local files when the book is DONE (no `/play` session, no network), with progress still saved locally and queued by the existing `pending_progress` flow. A downloaded book opened online still opens a normal ABS session so listening time syncs live.
8. **UI.** Download / Remove button and progress on Book details; Downloads tab (list with percent, ETA, pause, resume, cancel, total storage used); Settings: Wi-Fi only (default on), download location, storage limit. Deleting a download never touches the server.
9. **Android Auto.** The Downloaded tab lists real downloads; offline, the car shows only those.
10. **Widgets (last, own PR).** Glance: Now Playing (play/pause, skip) and Continue Listening.

## Order of work (one PR each, `stage-3/<name>`)
1. Pure rules plus Room tables plus Room-backed `DownloadStates` (no UI yet).
2. The resumable downloader and worker (tested against a local fake server with dropped connections).
3. Book details button, Downloads tab, Settings.
4. Offline playback and the Android Auto Downloaded tab.
5. Widgets. Then tag `v0.3.0`.

## Checking
Host tests for all pure rules and the downloader against a fake server (drop mid-file, 200-instead-of-206, stall). Emulator: real download from your server with the emulator network cut and restored, and playing offline. Not checkable here: a 30-hour run, real reboot behaviour on a device, and the Android 15 timeout (I will test `onTimeout` by calling it directly).

## Decisions (Robert, 2026-10-24)
- Audiobookshelf only; Wi-Fi only is the default setting (switchable in Settings).
- Downloads are removed by hand only. Later, maybe a Settings option "Remove all downloads I have listened to" (not in Stage 3 scope).
