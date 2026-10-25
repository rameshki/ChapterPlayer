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
- Each source implements a common `LibrarySource` interface, so the UI never knows which server a book came from. It lists books today (`fetchBooks`); search, stream resolution and progress reporting are added in Stages 2 and 4, when they have behaviour to carry.

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
- [x] Upgrade Kotlin/AGP; restructure into `shared/` + `androidApp/` (Android target only so far; `iosApp/` is below)
- [x] Port models, ABS client, Plex + plex.tv clients to Ktor + kotlinx.serialization
- [x] Room → Room KMP with KSP; Hilt → Koin
- [x] Move ViewModels and Compose screens into `commonMain`
- [x] Define the `LibrarySource` and `PlaybackController` interfaces (`PlaybackController` has no implementation until Stage 2)
- [x] Unit tests for the API parsers using recorded JSON responses
- [x] Empty `iosApp/` that compiles the shared framework (CI on a macOS runner). Not yet done: the iOS database driver, HTTP engine and Koin start-up (`iosMain` is only a placeholder screen); these come in Stage 5

**Done when:** the Android app has the same features as today, running entirely on the shared module.

### Stage 2: Audiobookshelf core experience (priority 1)
- [x] Move the server token out of the Room database into encrypted storage (before sign-in is built). After a phone restore the saved servers return without their tokens, so each token is entered again
- [x] Light theme and a Dark / Light / Follow system setting
- [x] ABS sign-in: username and password (the access token is refreshed before it expires; sign in again from Settings when the server asks) or an API token under Advanced; choose libraries when adding a server (only the ticked ones sync). The Room `token` column is still there, always empty, and is dropped in a later cleanup. Changing the library choice of a saved server comes with the Library tab (step 4)
- [x] Combined library across servers with a Libraries check-list (grouped by server): untick a library to hide it everywhere; libraries added later show by default. Today there is one optional library id, or every book library
- [x] Four tabs: Home (Resume card), Library (Authors, Narrators, Series and Collections views; large-cover, small-cover and list layouts with a remembered switch), Downloads (placeholder until Stage 3; the real list came in Stage 3 step 3), Search (the Home Resume card arrived with the player, step 7)
- [x] Downloaded state in the UI (built now, filled in by Stage 3): badges on covers, Downloaded filter chip and Filter sheet on the Library views and group lists (Home and Search apply the saved filter), Downloads tab empty state, offline dimming. Per-book download state, always "not downloaded" until Stage 3
- [x] Book details screen (description, chapters, series, narrator), with links to the series and to more books by the author and narrator. Resume and chapter taps stay inactive until playback (steps 6-7)
- [x] Load chapters per book from `/api/items/{id}`: the library list response has none, only `numChapters`
- [x] Replace the ABS stream URL (`/api/items/{id}/play` starts a playback session, it is not an audio URL)
- [x] Media3 playback: chapters, speed, sleep timer, ±30s skip, lock-screen controls (the service and controller support speed, sleep timer, skips and chapter jumps; the controls for speed and sleep timer appear on screen with the full player in step 7; today Play/Pause and chapter taps are on the book details screen)
- [x] Full player sheet + mini player (full player with whole-book and chapter sliders, skip 15/30, previous/next chapter, speed, sleep timer and chapter list; mini player above the tab bar; tapping the notification opens the player; Home Resume card and Continue Series. Bookmarks and History come with step 8)
- [x] ABS playback sessions and progress sync: save locally first, sync every ~10s, queue while offline and retry with backoff (AudioBooth's approach) (done in steps 6 and 8a: the position is saved every 10 s and synced through the playback session; failed syncs queue one row per book and are delivered with backoff, newest position wins; progress from other devices is merged on library sync and chosen when a book opens)
- [x] Bookmarks synced with ABS, and a History sheet (add, rename, delete and jump from the player; kept locally in Room v5 and synced with the server in both directions, offline changes are queued; History remembers where a big jump left you, last 10 per book)
- [x] Android Auto (moved up from Stage 3): Media3 `MediaLibraryService` browse tree (Continue, Recently added, Library and a Downloaded tab (a placeholder until Stage 3 step 4 filled it); Library has Series, Authors, Collections and All books in letter ranges), skip 15 / 30 on Previous / Next, voice search, resume on connect with a Settings switch. Built and checked against the real service on an emulator; not yet tried on the Desktop Head Unit or in a car (the emulator's Android Auto app is a stub). Known gaps: no cover art in the car (the cover address needs the token), the Now Playing queue button lists audio files while chapters are a Chapters folder under Continue, dimmed books have no cloud icon

**Done when:** you can listen to an ABS book daily, on the phone and in the car (Desktop Head Unit test + a real car), across phone and the ABS web UI, with progress staying in sync. Design: `docs/superpowers/specs/2026-10-14-stage-2-abs-core-design.md`.

### Stage 3: Widgets & offline
- [x] Glance widgets: Now Playing (play/pause, skip) and Continue Listening (step 5, Glance 1.2.0: Now Playing is play/pause only when narrow and title + back 15 / play-pause / forward 30 from 180 dp, the body opens the player, the buttons act on the loaded book only and never load one; Continue Listening shows the Resume-card book among the books that can play now, so offline a book that is not downloaded is not offered, and Resume starts it through `PlaybackStarter` and opens the player. Redrawn on state changes, not position ticks. No covers (a placeholder; covers need the token). The state, the buttons, the refresher and Resume are host-tested and both debug and release build; not yet checked: how the widgets look and behave on an emulator or phone (theme, sizes, the picker preview), Resume from a widget while the app is closed, a stale Now Playing button opening the app (a start from the background may be refused), Play / Skip from a widget after the media service has left the foreground, no APPWIDGET_UPDATE redraw loop, the redraw after a reboot, and the release (R8) build on a device)
- [x] Player More options menu (top right): Book details, More by <author>, Open series, Download / Pause / Resume / Remove download (with confirmation), Mark as finished / not finished, Settings. Mark as finished changes the book at once (works offline), tells Audiobookshelf (`PATCH /api/me/progress/{id}`, behaviour checked on a real 2.37.1 server) and queues the mark when the server cannot be reached; "not finished" starts the book over at 0 because that is what the server does. Built and host-tested (view model, repository against a mock server, queue and flush). Not yet checked: the menu on an emulator or phone, the offline queue against a real server, and what the server does with the mark when playback of that book continues
- [ ] Downloads that survive a very slow link (ABS behind a Cloudflare Tunnel; a book may take 30 hours):
  - one request per audio file with HTTP `Range` resume, never the all-in-one zip, so a dropped connection continues where it stopped
  - no overall request timeout (stall detection only), retry with backoff, same file and offset after app backgrounding, process death and reboot
  - percent complete from bytes received, and ETA from a rolling average speed, per book and overall
  - foreground worker with a progress notification. Android 15 limits `dataSync` foreground services to 6 hours per 24 hours and calls `onTimeout`, so the worker must stop cleanly and resume rather than assume one long run (research user-initiated data transfer jobs as the alternative)
  - Settings toggle: download on Wi-Fi only, or on Wi-Fi and cellular
  - single-file books (`.m4b`) are the preferred layout, so one resumable request per book is the common case; multi-file books still work, one file after another
  - storage limits and a download-location choice, pause and resume controls
  - [x] Book details download control: Download (with the size), progress with percent and time left, Pause / Resume / Cancel, Try again with the reason, Remove download with a confirmation; disabled offline; Audiobookshelf books only. Asks for notifications once at the first download (built and host-tested; not yet checked on an emulator)
  - [x] Downloads tab: every download with cover, status line and Pause / Resume / Try again / Cancel / Remove, active first, storage used and free space at the top (built and host-tested; not yet checked on an emulator)
  - [x] Settings, Downloads: Wi-Fi only (a change reschedules waiting and running downloads), download location (internal storage or each app folder on an external volume / SD card, with free space; new downloads only), storage limit (No limit, 1 to 50 GB) (built and host-tested; the SD card choice and the rescheduling are not yet checked on a device)
  - Progress (steps 1-4 of 5 in the Stage 3 spec): the rules, Room v6 tables (v7 adds each file's length and type), the downloader core and the worker are built and host-tested against a fake server (Range resume, 200-instead-of-206, 416, a body cut short, stalls before and after the headers, refresh once on 401, no redirect to another host, full disk, a 404 that plans the book again, cancel and pause-resume races). One book downloads at a time. Not yet checked: a real download on an emulator or phone, the WorkManager worker, notification and Pause/Cancel actions, restore after reboot, and the Android 15 time limit (the app targets SDK 34, so that limit does not apply yet). The UI arrived in step 3 (above) and offline playback in step 4 (below)
- [x] Offline mode: play downloaded books, sync progress when back online (step 4: a finished download plays from its files; online it still opens an ABS session so listening syncs live, offline it plays with no session and the position goes through the pending-progress queue; a book that is not downloaded says "Needs a connection to play" offline; a download whose files are gone is marked failed so Try again fetches them. Built and host-tested; playing offline on an emulator is checked next)
- [x] Android Auto Downloaded tab: lists the downloaded books (by title, playable offline); "No downloaded books yet" when there are none (built and host-tested)
- [ ] Check the Downloaded tab and offline playback in the car (Desktop Head Unit or a real car)

**Done when:** the widgets work and books play without network. Downloads also appear in the Android Auto browse tree.

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
