# Stage 3 step 5: Glance widgets

Spec: `docs/superpowers/specs/2026-10-24-stage-3-downloads-offline-design.md` (item 10) and `docs/ROADMAP.md` Stage 3 ("Glance widgets: Now Playing (play/pause, skip) and Continue Listening"). Branch `stage-3/widgets`. Read `CLAUDE.md` (Playback, Progress, Player UI, Android Auto) first. Main has everything else in Stage 3.

## Global constraints
- Jetpack Glance (`androidx.glance:glance-appwidget` + `glance-material3`, versions in `gradle/libs.versions.toml`, choose a stable version compatible with compileSdk 37 / AGP 9.3). Widgets live in `shared/androidMain` (they need Koin and the playback classes); receivers are declared in `androidApp/src/main/AndroidManifest.xml`. `commonMain` stays portable.
- Public repo: synthetic data only. No tokens anywhere. Covers need the token, so widgets show NO cover image from the network; they may show a cached cover only if one already exists locally for a downloaded book (the downloader saved `cover.jpg`), otherwise a neutral placeholder.
- Pure decisions (what text/buttons each state shows, what "continue" picks) are plain Kotlin in `domain/` with host tests; Glance composables stay thin.

## Task 1: pure widget state (commonMain, tested)
`domain/widgets/WidgetState.kt`: `NowPlayingWidgetState` (Idle, or Book(title, author, chapterTitle?, isPlaying, canSkip)) built from the existing playback state (`PlaybackController.state` / `domain/playback/NowPlaying.kt`), and `ContinueWidgetState` (Empty or Book(bookId, title, author, percent, timeLeftText)) built from the same rule as Home's Resume card (`domain/browse/ResumeCard.kt`, `ContinueSeries.kt`): reuse, do not duplicate. Offline: a not-downloaded book is not offered by Continue.

## Task 2: Now Playing widget
`NowPlayingWidget` (GlanceAppWidget, responsive: small = play/pause only, medium = title + back 15 / play-pause / forward 30). Buttons are Glance `ActionCallback`s that act through the one `PlaybackController` (connect if needed; never start a different book). Tapping the body opens the app on the player (`open_player` intent extra, as the notification does). Updates when the playback state changes (a lightweight observer started with the app/service that calls `updateAll`, throttled to state changes only, not every position tick). Provider XML (`res/xml`), preview, label, `AppWidgetProvider` receiver, theme colours from the app palette (dark/light).

## Task 3: Continue Listening widget
`ContinueListeningWidget`: shows the Resume-card book (title, author, percent/time left) with a Resume button that starts it through `PlaybackStarter` (the one load path; offline rules apply) and opens the player. Refreshes when progress or downloads change (throttled) and after boot.

## Task 4: docs and checks
`CLAUDE.md` (a Widgets paragraph) and `docs/ROADMAP.md` (tick the widgets item, state what is unverified). Add the Koin bindings the widgets need; `KoinModulesTest` stays green. Widget rendering itself is checked on the emulator by the controller (the host tests cover state and wiring).

## Finish
`:shared:testAndroidHostTest`, `:androidApp:assembleDebug`, `:androidApp:assembleRelease` green (release must keep Glance working: check R8/proguard); privacy scan; commits with trailers `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey` (exactly "Claude Sonnet 5.5"). Never push, never dispatch subagents. Report to `C:/Users/Robert/AppData/Local/Temp/claude/C--Users-Robert-ClaudeProjects-ChapterPlayer/5951ad6b-9e9b-494e-886e-6d21b9103272/scratchpad/stage3-5-report.md`; return only status, commits, one-line test count, concerns.
