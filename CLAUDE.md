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
- The theme follows `AppSettings.themeMode` (Dark, Light, Follow system; new installs follow the system). `ui/theme/Palette.kt` holds the dark and light palettes; the colour names in `Color.kt` (`AmberGold`, `TextSecondary` etc.) are composable properties that read the current palette, so read them inside composables only (a draw lambda must read the colour first). `PaletteContrastTest` guards text contrast in both themes. The launch window colour is in `values/themes.xml` (light) and `values-night/themes.xml` (dark)
- Browsing: pure rules live in `domain/browse` (filters, sort, search, grouping by author/narrator/series/collection, offline rules; tested without Android). `libraries`, `collections` and `collection_books` tables hold the server's library list and collections (Room v3, auto-migration). `LibrarySyncCoordinator` (`data/sync`, an app-scoped Koin single) is the only thing that syncs: it keeps one sync per server at a time, queues one follow-up when libraries change, and survives cancellation. Library views, layouts and filters are in `ui/library`; the Libraries check-list is `LibrariesSheet`
- Playback: one Media3 `MediaLibraryService` (`PlaybackService`, in `shared/androidMain/.../data/playback`) hosts one ExoPlayer; the UI talks to it through `PlaybackController` (`AndroidPlaybackController` wraps a `MediaController`). Audiobookshelf is asked for a playback session (`POST /api/items/{id}/play`); a book with several audio files plays as ONE timeline (one media item per file; `domain/playback` maps book position to file and offset). Audio is fetched with the bearer token added per request by `AuthorizingDataSourceFactory` (refreshed once on a 401). The pure rules (timeline, sleep timer, rewind on resume, skips, speeds) are in `domain/playback` with host tests; the service behaviour itself is checked on an emulator (`adb shell dumpsys media_session` shows state and position)
- Player UI: `ui/player` holds the full player (`PlayerScreen`, speed/sleep/chapter sheets), the mini player (above the tab bar, hidden on the player and Settings routes) and `PlayerViewModel`, all reading `PlaybackController.state`. `PlaybackStarter` (`data/playback`) is the one way to start a book (Home, details); `PlaybackProgressSaver` saves progress app-wide. Pure display rules live in `domain/playback/NowPlaying.kt`, `domain/browse/ResumeCard.kt` and `ContinueSeries.kt`. The notification opens the player through the `open_player` intent extra
- Server tokens live in an AES-GCM encrypted DataStore (`CredentialStore`) keyed by the Android Keystore, not in Room (the Room `token` column stays, empty). Audiobookshelf signs in with username and password (`AbsSignIn`, port `AbsSignInPort`) and keeps a refresh token (`<serverId>.refresh`); `AbsSessionManager` refreshes the access token before it expires and on a 401 (one refresh at a time per server; a rejected refresh means `SignInRequiredException`, shown as "Sign in again" in Settings). Libraries the user turned off are stored in `excluded_libraries`. App preferences (theme, library layout, car resume) live in `AppSettings`. Tests that write to a DataStore use `InMemoryPreferencesDataStore`, because DataStore cannot rewrite its file on Windows
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
