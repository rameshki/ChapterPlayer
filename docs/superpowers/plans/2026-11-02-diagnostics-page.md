# Developer Diagnostics page (debug builds)

Branch `stage-4/diagnostics`. Read `docs/PROJECT_STATUS.md` and `CLAUDE.md` (Progress, Plex, merged sources, Downloads, Settings hub) first. Purpose: let Claude (and Robert) see exactly what the app and each server hold for a book, without opening other apps and without exposing secrets, so device tests can be run remotely over wireless debugging and the Plex "does the server keep the position" question gets a definite answer.

## What to build
- **Entry:** Settings > About: a "Diagnostics" row, shown ONLY in debuggable builds (`AppInfo`/`Debuggable` flag that already exists for the HTTP logger; not in release builds, on Android and iOS).
- **Page "Diagnostics":** a book picker (search box over the merged library entries; shows title + the copies' server names/types) and, for the chosen book, one section per COPY with:
  - Local progress row: position, duration, last played (readable time), finished, `mirrored` flag.
  - Server progress as the app would read it when opening the book: ABS `GET /api/me/progress/{id}` (position, duration, isFinished, lastUpdate); Plex: the per-track list from `PlexLibrarySource.fetchProgress`/track list (rating key suffix only, `viewOffset`, `viewCount`, `lastViewedAt`, duration) and the computed book position/finished/updatedAt (`plexServerProgress`). A "Read from server" button (online only) with a clear error line on failure (never URLs or tokens).
  - Pending queue rows for that copy (position, listened, attempts, next attempt, markedFinished, mirrorOf).
  - Download state: status, bytes done/total, files (name only), folder name (no full path with ids beyond the book id), error text.
  - Merge info: group members, overrides (splits/merges), the Download and Play choices and which copy each rule picks right now (`chosenPlaySource`/`chosenDownloadSource` outcomes with reasons).
- **Output channels:** (1) on screen, scrollable and selectable; (2) a "Copy report" button (clipboard; clipboard writes via a small platform interface, no-op default); (3) the same report as ONE structured text block written to the log with tag `ChapterPlayerDiag` (Android Logcat; iOS `NSLog`) when "Read from server" finishes and when "Log report" is tapped, so Claude can read it with `adb logcat -s ChapterPlayerDiag`.
- **Redaction (hard requirement, tested):** never include tokens, passwords, Authorization/X-Plex-Token values, full URLs, server hostnames/addresses, plex.tv machine ids or full item ids that are not already the user's own book ids; server names only as the user named them; titles are fine (they are the user's own). A pure `redact()` pass over the whole report text plus unit tests with realistic tokens/URLs/hosts/ids.
- Pure report building in `domain/diagnostics/` (commonMain; data classes in, text out, with tests); thin view model; the page in `ui/settings/DiagnosticsScreen.kt`; route `settings/diagnostics`; commonMain portable (no JVM-only calls); Android + iOS compile checks stay green.
- A second small action on the page: "Simulate offline for 2 minutes" is NOT wanted; do not add anything that changes data. The page is read-only.

## Tasks (test-first)
1. Pure report model + builder + `redact()` with tests (including: token-looking strings, URLs with query tokens, IPv4/IPv6 and host names, UUIDs that are machine ids).
2. Data readers (read-only, through existing repositories/sources; no new network endpoints beyond the ones the app already calls to read progress) and view model.
3. UI page + route + About row (debug only), copy-to-clipboard interface, logging.
4. Docs (standing rule): `docs/PROJECT_STATUS.md` (how to use it for remote testing: `adb logcat -s ChapterPlayerDiag`), `CLAUDE.md`, `docs/ROADMAP.md`.

## Finish
`:shared:testAndroidHostTest`, `:androidApp:assembleDebug`, `:androidApp:assembleRelease` (the row must be absent: assert in a test that a non-debuggable flag hides it), `:shared:compileKotlinIosSimulatorArm64`, `:shared:compileKotlinIosArm64` green; privacy scan; commits with trailers `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey` (exactly "Claude Sonnet 5.5"). Never push, never dispatch subagents. One gradle command at a time, JDK 17, CRLF tree (grep after edits; edit with Python using encoding utf-8). Report to `C:/Users/Robert/AppData/Local/Temp/claude/C--Users-Robert-ClaudeProjects-ChapterPlayer/5951ad6b-9e9b-494e-886e-6d21b9103272/scratchpad/diagnostics-report.md`; return only status, commits, one-line test count, concerns.
