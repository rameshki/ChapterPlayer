<p align="center"><img src="docs/images/icon.png" width="128" height="128" alt="ChapterPlayer icon: gold headphones over an open book on navy"></p>

# ChapterPlayer

An audiobook player for the places your books already live: **Audiobookshelf** and **Plex** today (Audible is being researched). Chapter-first playback, progress that follows you between devices and servers, downloads for offline listening, car and widget support.

> **Work in progress.** ChapterPlayer is under active development and is not released. The Android app is feature-rich and used day to day by its author; the iOS app compiles and runs its shared tests in CI but has never been run on a device. Expect rough edges and changes. There is no store listing and no stable install yet.

## What it does

- **Sources:** sign in to one or more Audiobookshelf servers (username and password) and Plex servers (plex.tv). Plex servers are reached through the best connection (local, remote or relay) that actually answers.
- **One library:** every book from every server in one place, with filters (progress, downloaded, server), sorting, Authors / Narrators / Series / Collections views, search, an A to Z scroller and a Stats page (listening time, streaks, series finished and series to finish).
- **One entry per book:** a book that several servers have shows once; Play and Download ask which copy to use (or follow your default), and progress stays in step between the copies.
- **Playback:** chapters, speed, sleep timer, skip 15 / 30 seconds, bookmarks, position history, lock-screen controls, a full player and a mini player. Books that are many files play as one timeline.
- **Progress sync:** saved locally first, then sent to the server in the background; offline listening is queued and delivered later. Plex progress uses the timeline and scrobble calls.
- **Downloads:** resumable, one request per file, built for very slow links; Wi-Fi-only option, storage limit, offline playback.
- **Android extras:** Android Auto browse tree and voice search, Glance home-screen widgets (Now Playing, Continue Listening).
- **Privacy:** server tokens are encrypted on the device and never put in URLs or logs. No analytics.
- **Never:** removing DRM. Audible support, if it happens, will be limited to what the service permits.

## Status by platform

| Platform | State |
|---|---|
| Android | Working app; being verified on real devices. Debug builds only for now. |
| iOS | Shared code, sync, AVPlayer playback and downloads are written and compile; CI links the framework and builds an unsigned `.ipa`. Not yet run on a phone. |

See [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) for the live state of the project (what is merged, what is verified on devices, next steps) and [`docs/ROADMAP.md`](docs/ROADMAP.md) for the stages and scope.

## How it is built

- **Kotlin Multiplatform** with Compose Multiplatform for the UI. Almost everything lives in `shared/` (UI, view models, navigation, networking, database, sync, rules); `androidApp/` and `iosApp/` are thin hosts.
- Ktor + kotlinx.serialization, Room (KMP, bundled SQLite), DataStore, Koin, Coil 3, Media3 (Android playback), AVFoundation (iOS playback), WorkManager (Android downloads).
- Pure logic (merging, filters, sync rules, stats, progress) is plain Kotlin with host tests, so it can be checked without a device. Parsers are tested against recorded-shape server responses in `shared/src/androidHostTest/resources/fixtures` (synthetic text, ids and tokens).
- The design reference for the app's structure is the AudioBooth iOS client; no code is copied from it.

```
shared/      Kotlin Multiplatform code: commonMain, androidMain, iosMain, tests
androidApp/  Android application module (manifest, activity)
iosApp/      SwiftUI host; the Xcode project is generated from project.yml with XcodeGen
docs/        Roadmap, project status, design specs and implementation plans
```

## Building

Requirements: JDK 17, the Android SDK (platform 37, build-tools 36), and Android Studio or the command line.

```
./gradlew :androidApp:assembleDebug           # debug APK
./gradlew :shared:testAndroidHostTest         # unit tests on the JVM
./gradlew :shared:compileKotlinIosSimulatorArm64   # compile the iOS Kotlin code (works on Windows too)
```

The iOS app itself needs macOS and Xcode; continuous integration builds it on a macOS runner and publishes an unsigned `.ipa` (see [`docs/IOS_SIDELOAD.md`](docs/IOS_SIDELOAD.md) for installing it with a free Apple ID). `GETTING_STARTED.md` is an older, longer walk-through for a first Android setup and may be out of date.

## Contributing and licence

This is a personal project and is not currently accepting outside contributions, but issues and suggestions are welcome. The project is licensed under the [Apache License 2.0](LICENSE) (see also `NOTICE`); contributions, if ever accepted, would be under the same licence. Third-party skills and tools used in development are listed in `.claude/skills/THIRD-PARTY.md`.

Development uses AI assistance (Claude Code); every change goes through a pull request, CI and a review before it is merged.
