# ChapterPlayer

Audiobook player for Audiobookshelf (ABS), Plex and Jellyfin. Android first, built with Kotlin Multiplatform so it also
runs on iOS. The repo is public (Apache-2.0). Scope, stages and design direction: `docs/ROADMAP.md`.

**Start here in every session:** read `docs/PROJECT_STATUS.md` (what is merged and open, what is verified on devices,
next steps, traps). Update it, this file and the area file you touched whenever a block of work finishes. Then read ONLY
the `docs/architecture/<area>.md` files your task touches (map below); they hold the detail that used to live here.
Before re-opening a settled question, or asking Robert something already decided, check `docs/DECISIONS.md` (dated decisions and the open ones).

## Priorities
- Sources: Audiobookshelf > Jellyfin > Plex (Audible skipped for now; never remove DRM)
- Must have: Android Auto, widgets, offline downloads; later CarPlay + iOS widgets
- Design reference: AudioBooth (iOS ABS client): tabs, home shelves, player sheet, mini player. Borrow structure, not code (MPL-2.0)

## Rules that must not be broken
- No real server addresses, ids, titles, tokens or passwords in commits, fixtures, logs or prompts to subagents. The repo is public; fixtures are real structure with synthetic content.
- Tokens never in URLs, logs, exceptions, notifications or media metadata. ABS and Jellyfin send them in a header; Plex in `X-Plex-Token`; covers and redirects drop them when they leave the server's origin.
- Never read the user's screen or other apps. Phone testing only in the debug app (`com.chapterplayer.debug`); capture only ChapterPlayer screens.
- Never circumvent device management (the owner's iPhone is MDM-managed). Never remove DRM.
- English only. Release builds may offer a redacted "Report a problem" text report; no analytics, no network call for it.
- Merges: PRs into protected `main`, squash, only with green CI and a clean review.

## Layout
- `shared/` Kotlin Multiplatform (Android + iOS): all app code. `commonMain` has the UI (Compose Multiplatform), view models, navigation, domain rules, data, Koin modules; `androidMain` / `iosMain` hold only platform parts.
- `androidApp/` activity, manifest, R8 rules, release signing (key outside the repo). `iosApp/` SwiftUI host, project generated from `iosApp/project.yml` with XcodeGen.
- Stack: Compose, Koin, Room (KSP, schema in `shared/schemas`), Ktor + kotlinx.serialization, Coil 3, Media3, Glance, Kotlin 2.4, AGP 9.3, Gradle 9.5, compileSdk 37, targetSdk 36 (Android 15 and 16 behaviour: `docs/architecture/downloads.md` time limit, `playback.md` audio focus), minSdk 26.
- Pure rules live in `domain/*` and are host-tested; keep new logic there, not in view models or platform code.

## Where the detail is (`docs/architecture/`)
| Task touches | Read |
|---|---|
| Library, filters, favourites, selection, merging copies, Play from / Download from | `browse-and-merge.md` |
| Player, service, Up next, offline playback | `playback.md` |
| Progress sync, mirroring queue, bookmarks, history, stats | `progress.md` |
| Downloads (any platform) | `downloads.md` |
| Widgets | `widgets.md` |
| Android Auto | `car.md` |
| Plex | `plex.md` |
| Jellyfin | `jellyfin.md` |
| iOS | `ios.md` |
| Theme, fonts, Settings, Diagnostics | `ui-theme-settings.md` |
| Credentials, Koin, test layout | `platform-and-infra.md` |

## Building and testing
- JDK 17 (CI uses Temurin 17). `./gradlew :androidApp:assembleDebug`; release: `:androidApp:assembleRelease` and `:androidApp:lintRelease` (baseline `androidApp/lint-baseline.xml`); `scripts/check-r8-seeds.sh` checks R8 keeps the widget callbacks.
- Host tests: `./gradlew :shared:testAndroidHostTest` (also runs `commonTest`). iOS type check on Windows: `:shared:compileKotlinIosSimulatorArm64`; linking and Xcode build run only in the macOS CI job.
- `KoinModulesTest` runs `verify()` over every module: a new class in a module shows what it still needs.
- SDK: platform `android-37.0`, build-tools `36.0.0`. With `sdkmanager` on Windows use slash paths.
- CI: two jobs (Build & unit test about 5 min, iOS about 19 min). `main` is strict: rebase after other merges.

## Traps
- No JVM-only calls in `commonMain` (no `java.*`, no `String.format`); Kotlin/Native rejects commas in backtick test names.
- Line endings: `.gitattributes` makes every text file LF in the repo and in the working tree (batch and PowerShell files keep CRLF); `.editorconfig` sets utf-8, 4 spaces and 120 columns for Kotlin. A normal editor edit gives a clean diff. If an older checkout still has CRLF files, run `git add --renormalize .` once.
- Run one Gradle command at a time. Set `MSYS_NO_PATHCONV=1` for adb from Git Bash.
- DataStore cannot rewrite its file on Windows: tests use `InMemoryPreferencesDataStore`.
- Koin fails when a screen opens, not at build time: run `KoinModulesTest`.
- Palette colours (`Color.kt`) are composable properties: read them inside composables only.
- Room changes need a migration and an updated `shared/schemas`; CI fails when the schema differs.
- Restore `stay_on_while_plugged_in` to 0 after phone sessions.

## Conventions
- Branches `stage-N/<feature>` (or `stage-r/<finding>` for review findings), one PR per feature, tags `v0.N.0` per stage.
- Commit trailers: `Co-Authored-By: Claude <noreply@anthropic.com>` and the session line.
- Process: plan, implement, independent review, CI, merge. Record each finished block in `docs/PROJECT_STATUS.md`.

## New dev machine
- `scripts/setup-dev-machine.ps1` installs Git, GitHub CLI, Android Studio, SDK + emulator + Android Auto DHU, Claude Code, and clones the repo.
