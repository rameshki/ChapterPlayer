# Merged sources Phase 2: keep progress in sync between copies of a book

Branch `stage-4/mirror-progress`. Read `docs/PROJECT_STATUS.md` and `CLAUDE.md` (merged sources, Progress, Plex, Bookmarks) first, and `docs/superpowers/plans/2026-10-26-merged-sources.md`. Robert approved this idea on 2026-10-26 ("write to whichever server you are not using as a background courtesy").

## Problem
A book on both servers is one merged entry, but each copy has its own progress (local row per copy, server progress per copy). Today the tile shows the progress of the copy played last, while Play/Resume starts the chosen copy at its OWN saved position, so the position can jump back. Phase 2 makes the copies agree.

## Design decisions (made; change only with a stated reason)
- **Mapping:** a position in one copy maps to another by fraction of the whole book: `otherMs = round(positionMs / durationMs * otherDurationMs)` (both durations known and > 0; otherwise do not mirror). Chapters differ between copies, so never map by chapter. Round to whole seconds; never past the end; a finished copy mirrors as finished.
- **Local first:** whenever a copy's progress is saved (`savePlaybackProgress`, `markFinished`, server merge), the other members' LOCAL progress rows are updated by the mapped position with `lastPlayedAt` = the source's, flagged as mirrored (new column on `progress`, auto-migration, default false), so Resume, the tile, the car and the widgets agree at once, offline too. Newest wins: never overwrite a member whose own non-mirrored progress is newer than the source's; a mirrored row never counts as listening (stats recorder untouched) and never starts a push storm (no mirroring of mirrored rows).
- **Servers:** pushes to the OTHER copy's server go through the existing queue machinery (`pending_progress`, flusher, backoff; ABS `PATCH /api/me/progress/{id}`, Plex timeline/scrobble via `pushProgress`/`plexProgressPlan`), throttled (at most one push per other copy per 60 s while playing, plus one on pause/stop/close and on mark finished), only for copies on reachable servers, and never while the copy is the one loaded in the player. A queued mirror yields to any newer real listening of that copy (flusher's existing "server holds something newer" rule). Do NOT create playback sessions on the other server.
- **On open:** `PlaybackStarter` already weighs server vs local progress for the copy being started; extend it so that it also considers the other members' progress (their local rows and, when online, their server progress, mapped by fraction) and starts at the newest, so progress made on another device against the other server is found. Plex progress is read only at open as today.
- **Setting:** "Keep progress in sync between copies" in Settings (Downloads and offline page or a small Listening group), default ON. When off: no mirroring, behaviour as today.
- **Mark as finished / not finished** on a merged book applies to ALL copies (the menu and the details action), each through its own server path; the server's answer per copy is adopted as today (an un-finished ABS copy resets to 0: mirror that to the other copies at 0 too).
- **Split ("Not the same book")** stops mirroring immediately and leaves the progress each copy has.
- Bookmarks and history stay per copy (not mirrored).

## Tasks (test-first, host tests; commonMain portable: no JVM-only calls such as putIfAbsent, computeIfAbsent, String.format, java.*; CI iOS compiles commonMain with Kotlin/Native and Windows cannot)
1. Pure rules `domain/playback/MirrorProgress.kt`: mapping, newest-wins decision, "which members to update", throttle decision, finished/reset handling; table-driven tests (different durations, zero duration, finished, un-finished, newer other copy, mirrored row never re-mirrors, split pair).
2. Room: `mirrored` flag on progress (+ schema JSON, auto-migration, schema test).
3. Data: mirror on local save/mark/merge paths (one lock, as the saver and `markFinished` already share), push through the queue with the throttle, setting in `AppSettings` + UI.
4. Starter: newest across members at open; Home Resume/car/widgets keep using `resumeCopy` and now agree.
5. Mark finished on all copies.
6. Tests for each path with fakes (two ABS-like and Plex-like copies), including: listen on copy A -> B's local row follows within one save; B newer than A -> not overwritten; offline -> queued then flushed; split stops it; setting off does nothing.
7. **Docs (required, standing rule):** update `docs/PROJECT_STATUS.md` (State at a glance: what is merged and what is verified; Next steps), the merged-sources paragraph of `CLAUDE.md` and `docs/ROADMAP.md`, honest about what is only host-tested.

## Finish
`:shared:testAndroidHostTest`, `:androidApp:assembleDebug`, `:androidApp:assembleRelease` green; privacy scan; commits with trailers `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey` (exactly "Claude Sonnet 5.5", even if your own attribution says otherwise). Never push, never dispatch subagents. One gradle command at a time, JDK 17, CRLF tree (grep after edits; edit with Python using encoding utf-8). Report to `C:/Users/Robert/AppData/Local/Temp/claude/C--Users-Robert-ClaudeProjects-ChapterPlayer/5951ad6b-9e9b-494e-886e-6d21b9103272/scratchpad/mirror-report.md`; return only status, commits, one-line test count, concerns.
