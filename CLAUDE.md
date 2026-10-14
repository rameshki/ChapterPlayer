# ChapterPlayer

Audiobook player for Audiobookshelf, Plex and (later) Audible. Android first,
built with Kotlin Multiplatform so it can port to iOS. See `docs/ROADMAP.md`
for scope, stages and design direction. Keep that file updated as stages complete.

## Priorities
- Sources: Audiobookshelf > Plex > Audible (Audible scope undecided; never remove DRM)
- Must have: Android Auto, widgets, offline downloads; later CarPlay + iOS widgets
- Design reference: AudioBooth (iOS ABS client): tabs, home shelves, player sheet, mini player. Borrow structure, not code (MPL-2.0).

## Current state
- Three parts: `shared/` (Kotlin Multiplatform, Android + iOS targets; all app code), `androidApp/` (activity, manifest) and `iosApp/` (SwiftUI host; its Xcode project is generated from `iosApp/project.yml` with XcodeGen). `iosMain` is only a placeholder screen until Stage 5. The macOS CI job links the framework and builds the app, and cannot be run on Windows
- Stack: Compose, Koin, Room (KSP), Ktor + kotlinx.serialization on Kotlin 2.4, AGP 9.3 (built-in Kotlin), Gradle 9.5, compileSdk 37
- UI is Compose Multiplatform (plugin 1.12.1). The theme, fonts (`shared/src/commonMain/composeResources/font`), components, screens, view models and navigation are all in `commonMain` (JetBrains navigation and lifecycle). `androidMain` keeps only what is Android-specific: the HTTP client setup, the repository and API implementations, the Android client identifier and the Koin modules. Images load with Coil 3
- Dependency injection is Koin (Classic DSL, modules in `shared/.../di/KoinModules.kt`). Koin fails when a screen opens, not at build time, so `KoinModulesTest` runs `verify()` over every module: add a new class to a module and the test shows what it still needs
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
