# ChapterPlayer Roadmap

_Last updated: 2026-10-12_

## Vision

One audiobook app for every place your books live: **Audiobookshelf**, **Plex**
and (eventually) **Audible**, with a unified library, chapter-first playback,
reliable progress sync, offline listening, car integration and widgets.

**Android first, built to port to iOS.**

## Scope

### Source priority

| Priority | Source | Scope |
|---|---|---|
| 1 | Audiobookshelf | Full: browse, stream, download, progress & session sync, bookmarks |
| 2 | Plex | Full: sign-in via plex.tv, browse music/audiobook sections, stream, download, progress via Plex timeline |
| 3 | Audible | **Undecided**: research spike in Stage 6. No DRM removal under any option |

### Platforms & surfaces

| Surface | Android | iOS (later) |
|---|---|---|
| Phone/tablet app | Compose (shared UI) | Compose Multiplatform (shared UI) |
| Playback engine | Media3 / ExoPlayer | AVPlayer (Swift) |
| Car | **Android Auto** (Media3 `MediaLibraryService`) | **CarPlay** (Swift, `CPTemplate`, needs Apple's CarPlay audio entitlement) |
| Widgets | Glance app widgets | WidgetKit + Live Activities (Swift) |

### Out of scope (for now)

Podcasts, ebooks/read-along, Wear OS / Apple Watch, an equalizer, social
features. AudioBooth has several of these; revisit after Stage 5.

## Architecture: Kotlin Multiplatform

```
ChapterPlayer/
├── shared/                 Kotlin Multiplatform module
│   ├── commonMain/         models, ABS/Plex/plex.tv clients, repositories,
│   │                       sync engine, database, ViewModels, Compose UI
│   ├── androidMain/        Media3 player actual, Android-specific bits
│   └── iosMain/            AVPlayer bridge actual, iOS-specific bits
├── androidApp/             Activity, MediaLibraryService (Android Auto), Glance widgets
└── iosApp/                 Xcode project: SwiftUI host, CarPlay scene, WidgetKit
```

Library changes from today's Android-only stack:

| Today | Move to | Why |
|---|---|---|
| Retrofit + OkHttp | **Ktor client** | Runs on Android and iOS |
| Moshi | **kotlinx.serialization** | Multiplatform |
| Hilt | **Koin** | Multiplatform DI |
| Room (kapt) | **Room KMP (KSP)** | Room 2.7+ supports iOS; keeps our existing schema |
| Coil 2 | **Coil 3** | Multiplatform image loading |
| Navigation Compose | **JetBrains navigation-compose** | Multiplatform port of the same API |
| Kotlin 1.9 / AGP 8.4 | **Kotlin 2.x / current AGP** | Required by Compose Multiplatform |

Rules:
- Everything that isn't playback, car, widgets or OS integration goes in `commonMain`.
- Playback sits behind a common `PlaybackController` interface with one implementation per platform.
- Each source implements a common `LibrarySource` interface (`browse`, `search`, `streamInfo`, `reportProgress`), so the UI never knows which server a book came from.

## Design direction

Primary reference: **AudioBooth** (github.com/AudioBooth/AudioBooth, MPL-2.0).
Borrow the structure and feel; write our own code and assets.

- **Tabs:** Home · Library · Collections · Downloads · Search, with Settings in the Home toolbar.
- **Home:** Continue Listening (cover cards with % badge and "time remaining"), Continue Series, Recently Added.
- **Library:** cover grid with segmented Library / Authors / Narrators / Series, sort and filter.
- **Player:** full-screen sheet; large cover; chapter title (tap for chapter list); two progress bars (whole book and current chapter); ±30s skip; bottom row of Speed · Sleep timer · Bookmarks · History.
- **Mini player** above the tab bar; swipe up for the full player.
- **Stats page** (Stage 4+): daily goal ring, streaks, listening time totals.

From **Plex**: multi-server switching, the source badge on each book.
From **Audible**: series grouping and ordering, "finished" state and library filters.

Keep ChapterPlayer's identity: dark theme with the amber-gold accent, and the Playfair Display + DM Sans fonts.

## Stages

Each stage ends with a tagged release (`v0.N.0`) and a working debug APK.

### Stage 0: Repo & tooling foundation
- [x] Merge `plex-signin` into `main`
- [x] Add the Gradle wrapper (`gradlew`) so builds work without Android Studio
- [x] GitHub Actions CI: build + unit tests on every PR
- [x] Protect `main`: changes land through PRs
- [x] Set up issues and milestones on GitHub, one milestone per stage

**Done when:** CI is green on `main`, and `./gradlew assembleDebug` works from a clean clone.

### Stage 1: Kotlin Multiplatform migration
- [ ] Upgrade Kotlin/AGP; restructure into `shared/` + `androidApp/` (toolchain upgrade done in PR 1, module split done in PR 2; Android target only so far)
- [x] Port models, ABS client, Plex + plex.tv clients to Ktor + kotlinx.serialization
- [ ] Room → Room KMP with KSP; Hilt → Koin (Hilt → Koin done in PR 4)
- [ ] Move ViewModels and Compose screens into `commonMain`
- [ ] Define the `LibrarySource` and `PlaybackController` interfaces
- [x] Unit tests for the API parsers using recorded JSON responses
- [ ] Empty `iosApp/` that compiles the shared framework (CI on a macOS runner)

**Done when:** the Android app has the same features as today, running entirely on the shared module.

### Stage 2: Audiobookshelf core experience (priority 1)
- [ ] ABS sign-in: username/password and API key; library picker
- [ ] Library selection per server: show some libraries and exempt others (multi-select, remembered per server). Libraries added to the server later are shown by default and can be turned off. Today there is one optional library id, or every book library
- [ ] AudioBooth-style navigation: Home, Library, Collections, Downloads, Search
- [ ] Book details screen (description, chapters, series, narrator)
- [ ] Load chapters per book from `/api/items/{id}`: the library list response has none, only `numChapters`
- [ ] Replace the ABS stream URL (`/api/items/{id}/play` starts a playback session, it is not an audio URL)
- [ ] Media3 playback: chapters, speed, sleep timer, ±30s skip, lock-screen controls
- [ ] Full player sheet + mini player
- [ ] ABS playback sessions and progress sync: save locally first, sync every ~10s, queue while offline and retry with backoff (AudioBooth's approach)
- [ ] Bookmarks synced with ABS

**Done when:** you can listen to an ABS book daily, across phone and the ABS web UI, with progress staying in sync.

### Stage 3: Android Auto, widgets & offline
- [ ] Android Auto: Media3 `MediaLibraryService` browse tree (Continue Listening, Library, Downloads), voice search, chapter skip
- [ ] Glance widgets: Now Playing (play/pause, skip) and Continue Listening
- [ ] Downloads that survive a very slow link (ABS behind a Cloudflare Tunnel; a book may take 30 hours):
  - one request per audio file with HTTP `Range` resume, never the all-in-one zip, so a dropped connection continues where it stopped
  - no overall request timeout (stall detection only), retry with backoff, same file and offset after app backgrounding, process death and reboot
  - percent complete from bytes received, and ETA from a rolling average speed, per book and overall
  - foreground worker with a progress notification. Android 15 limits `dataSync` foreground services to 6 hours per 24 hours and calls `onTimeout`, so the worker must stop cleanly and resume rather than assume one long run (research user-initiated data transfer jobs as the alternative)
  - Settings toggle: download on Wi-Fi only, or on Wi-Fi and cellular
  - single-file books (`.m4b`) are the preferred layout, so one resumable request per book is the common case; multi-file books still work, one file after another
  - storage limits and a download-location choice, pause and resume controls
- [ ] Offline mode: play downloaded books, sync progress when back online

**Done when:** it works in the car (Desktop Head Unit test + a real car) and without network.

### Stage 4: Plex parity (priority 2)
- [ ] Fix the Plex library call: a Plex music library lists artists at the top level, so the app currently imports artists (no author, duration, audio or chapters). Audiobooks are albums (type 9, author in `parentTitle`), the audio is in each album's tracks, and chapters come from the track detail (`/library/metadata/{id}?includeChapters=1`), not the track list
- [ ] Plex section picker; chapters from Plex metadata
- [ ] Smart connection choice: local → remote (plex.direct) → relay, re-checked on network change. plex.tv reports `dnsRebindingProtection` and `natLoopbackSupported` per server: on a network whose router blocks the `*.plex.direct` name (seen on a Fritz!Box) the app currently fails with `UnknownHostException`, so it must fall back to the server address or another connection
- [ ] Progress sync via the Plex timeline/scrobble API
- [ ] Unified library: ABS + Plex side by side, with filters and source badges
- [ ] Listening stats page

**Done when:** a Plex audiobook works end-to-end exactly like an ABS one, including Auto and widgets.

### Stage 5: iOS port
- [ ] Shared Compose UI running on iOS
- [ ] AVPlayer `PlaybackController`; background audio; lock-screen / Control Center
- [ ] CarPlay (Swift): Home / Library / Downloads tabs + Now Playing
- [ ] WidgetKit widgets + sleep-timer Live Activity (Swift)
- [ ] TestFlight beta

**Prerequisites:** a Mac with Xcode, an Apple Developer account, and an approved CarPlay audio entitlement from Apple. Apply for the entitlement early, because approval takes time.

### Stage 6: Audible (priority 3, scope to be decided)
- [ ] Research spike: unofficial Audible API state, terms-of-service risk, account-flag risk
- [ ] Decide between: (a) library view + hand-off to the Audible app, (b) via Audiobookshelf only (Libation/OpenAudible imports), or (c) skip
- [ ] Never: in-app DRM removal

## Git workflow

- `main` is always releasable; protected; changes land through pull requests.
- Branch names: `stage-N/<short-feature>`, e.g. `stage-2/abs-player`.
- One milestone per stage on GitHub; one issue per checklist item above.
- Tags: `v0.N.0` at the end of each stage; `v1.0.0` = Stage 4 shipped on Android.
- Commits: present-tense summary line; CI must pass before merging.
