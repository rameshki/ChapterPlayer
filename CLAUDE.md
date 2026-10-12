# ChapterPlayer

Audiobook player for Audiobookshelf, Plex and (later) Audible. Android first,
built with Kotlin Multiplatform so it can port to iOS. See `docs/ROADMAP.md`
for scope, stages and design direction. Keep that file updated as stages complete.

## Priorities
- Sources: Audiobookshelf > Plex > Audible (Audible scope undecided; never remove DRM)
- Must have: Android Auto, widgets, offline downloads; later CarPlay + iOS widgets
- Design reference: AudioBooth (iOS ABS client): tabs, home shelves, player sheet, mini player. Borrow structure, not code (MPL-2.0).

## Current state
- Two modules: `shared/` (Kotlin Multiplatform, Android target only for now; all app code) and `androidApp/` (activity, manifest). Compose, Hilt, Room (KSP), Ktor + kotlinx.serialization on Kotlin 2.4, AGP 9.3 (built-in Kotlin), Gradle 9.5, compileSdk 37
- Stage 1 of the roadmap migrates this to `shared/` (KMP) + `androidApp/` + `iosApp/`
- Host tests in `shared/src/androidHostTest` (`./gradlew :shared:testAndroidHostTest`). Parsers are tested against recorded server responses in `src/androidHostTest/resources/fixtures`: real structure, synthetic text, ids, paths and tokens. The repo is public, so never commit real server data

## Building
- Java: JDK 17 (CI uses Temurin 17); full builds are tested on it. Gradle 9.5 also configures the project on Android Studio's bundled JBR 25 (`help` checked; a full compile on JBR 25 has not been run).
- `./gradlew :androidApp:assembleDebug` (needs JAVA_HOME set to JDK 17 or newer)
- SDK: platform `android-37.0` and build-tools `36.0.0` (AGP 9.3 minimum). With `sdkmanager` on Windows use slash paths: `sdkmanager "platforms/android-37.0"`

## Conventions
- Branches `stage-N/<feature>`, PRs into protected `main`, tags `v0.N.0` per stage
- Server tokens never logged (auth headers are redacted in the HTTP logger)

## New dev machine
- `scripts/setup-dev-machine.ps1` installs Git, GitHub CLI, Android Studio, SDK + emulator + Android Auto DHU, Claude Code, and clones the repo
