# ChapterPlayer Roadmap

_Last updated: 2026-10-25_

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
- [x] Empty `iosApp/` that compiles the shared framework (CI on a macOS runner). The iOS database driver, HTTP engine and Koin start-up followed in Stage 5 step 1

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
  - [x] Book details download control: Download (with the size), progress with percent and time left, Pause / Resume / Cancel, Try again with the reason, Remove download with a confirmation; disabled offline; Audiobookshelf books only (Plex books too since the Stage 4 Plex downloads). Asks for notifications once at the first download (built and host-tested; not yet checked on an emulator)
  - [x] Downloads tab: every download with cover, status line and Pause / Resume / Try again / Cancel / Remove, active first, storage used and free space at the top (built and host-tested; not yet checked on an emulator)
  - [x] Settings, Downloads: Wi-Fi only (a change reschedules waiting and running downloads), download location (internal storage or each app folder on an external volume / SD card, with free space; new downloads only), storage limit (No limit, 1 to 50 GB) (built and host-tested; the SD card choice and the rescheduling are not yet checked on a device)
  - [x] Settings hub: Settings is a home of rows (Servers, Appearance, Downloads and offline, Android Auto, About) with live summaries, each opening its own page; the old single page is regrouped with no change to any setting. Built and host-tested (summary text, routes, storage-used flow); not yet checked on an emulator or phone
  - Progress (steps 1-4 of 5 in the Stage 3 spec): the rules, Room v6 tables (v7 adds each file's length and type), the downloader core and the worker are built and host-tested against a fake server (Range resume, 200-instead-of-206, 416, a body cut short, stalls before and after the headers, refresh once on 401, no redirect to another host, full disk, a 404 that plans the book again, cancel and pause-resume races). One book downloads at a time. Not yet checked: a real download on an emulator or phone, the WorkManager worker, notification and Pause/Cancel actions, restore after reboot, and the Android 15 time limit (the app targets SDK 34, so that limit does not apply yet). The UI arrived in step 3 (above) and offline playback in step 4 (below)
- [x] Offline mode: play downloaded books, sync progress when back online (step 4: a finished download plays from its files; online it still opens an ABS session so listening syncs live, offline it plays with no session and the position goes through the pending-progress queue; a book that is not downloaded says "Needs a connection to play" offline; a download whose files are gone is marked failed so Try again fetches them. Built and host-tested; playing offline on an emulator is checked next)
- [x] Android Auto Downloaded tab: lists the downloaded books (by title, playable offline); "No downloaded books yet" when there are none (built and host-tested)
- [ ] Check the Downloaded tab and offline playback in the car (Desktop Head Unit or a real car)

**Done when:** the widgets work and books play without network. Downloads also appear in the Android Auto browse tree.

### Stage 4: Plex parity (priority 2)
- [x] Fix the Plex library call: a Plex music library lists artists at the top level, so the app imported artists (no author, duration, audio or chapters). Audiobooks are albums (type 9, author in `parentTitle`), the audio is in each album's tracks, and chapters come from the track detail (`/library/metadata/{id}?includeChapters=1`), not the track list (step 1: sections are listed as albums in pages of 200, a server that still sends artists has them opened for their albums, one failing section keeps its cached books and is reported; cover and stream addresses carry no token, covers get `X-Plex-Token` in a header from the image loader; Room v9 drops the artist rows stored before. Host-tested against recorded-shape fixtures only (synthetic text); not yet tried on a real Plex server or a phone)
- [x] Plex section picker; chapters from Plex metadata (step 1: the Libraries check-list shows the music sections by name, as before; opening a book reads its tracks and the first track's chapters (every track's when that one has some), chapters span the tracks and a track without chapters is one chapter; the details store the book's length and the ordered tracks are ready for playback in step 2. (Plex books could not play until step 2.) Fixtures only (a one-file book with 24 chapters, a three-track book, a track without chapters, missing lengths); not yet tried on a real server)
- [x] Smart connection choice: local → remote (plex.direct) → relay, re-checked on network change. plex.tv reports `dnsRebindingProtection` and `natLoopbackSupported` per server: on a network whose router blocks the `*.plex.direct` name (seen on a Fritz!Box) the app failed with `UnknownHostException`, so it must fall back to the server address or another connection (step 3: built and tested with fixtures, not tried on a real server. Connections ranked local, raw local `http://<address>:<port>` (right after the local name when `dnsRebindingProtection`, else after remote), remote, relay, IPv6 only as a last resort; probed with `GET /identity`, the first two at once, 3 s timeouts; the winner becomes the server's address and is re-checked after a connection failure, on a network change and before a first sync, at most once per 30 s unless the user asks. Servers added earlier get their list from plex.tv by their address. Settings, Servers shows each server's connection, last sync or failure reason, Sync now and Test connection)
- [ ] Progress sync via the Plex timeline/scrobble API. Step 2 built, NOT verified: built against fixtures and mock servers, not tried on a real Plex server or a phone. Plex books stream online (and play from the phone once downloaded, see below), one item per track on one timeline, the token in an `X-Plex-Token` header added by the player's data source (never in an address); resume from the tracks' `viewOffset`/`viewCount`/`lastViewedAt` (newest wins against local progress); a `/:/timeline` report every ~10 s while playing, `paused` on pause and `stopped` on close, `/:/scrobble` when a track is played through (not when skipped by a seek), failures queued in `pending_progress`; Mark as finished / not finished scrobbles / unscrobbles every track; playable in Android Auto and the widgets. Still to check on a real server: that the timeline call keeps `viewOffset` for audio tracks, that scrobble clears it, the unscrobble call, playback of the part addresses with the header, and whether a timeline without a player session should use the older `/:/progress` call instead (not changed)
- [ ] Unified library: ABS + Plex side by side, with filters and source badges
  - [x] Filter by server: a Server section in the Library filter sheet (two or more servers; name, ABS/Plex label, unfiltered book count) and a server chip in the chip row (opens the sheet, its cross clears it). Applies to every Library view, Home shelves and Search; a deleted server drops out of the choice, a stale id never hides the library and the choice is dropped when fewer than two servers remain; Home and Search show a "Server filter on" hint with Show all servers. The choice is kept in memory with the other filters (resets on restart). Host-tested only; not yet checked on an emulator or phone
  - [x] A to Z fast scroller on the right edge of the Library list and grids (Title and Author sorts) and the Authors, Narrators and Series lists, from 20 items: drag to jump, letter bubble, haptic tick, fades when idle, one TalkBack node with an action per letter. Author and narrator names are lettered as written ("The Great Courses" under T), titles skip a leading article, accented letters count as their base letter in both the sorts and the strip, and the '#' group sorts first. Collections keep the server's order, so no scroller there. The letter rules are host-tested against the real sorts; Robert tried the scroller on his phone (first version); the review fixes (narrower touch area, single TalkBack node, accent folding) are host-tested and built only
  - [x] One entry for a book several servers have (Phase 1): conservative matching (same title and first author after folding case, accents, punctuation, a leading article and an Unabridged/Abridged suffix; lengths within 2% or 5 minutes; never two books of one server, at most one per server in a group); a "2 sources" pill on tiles and rows; the details page lists the sources, and Play and Download use the Settings choice "When a book is on several servers" (Always ask / Prefer Audiobookshelf / Prefer Plex / Prefer the downloaded copy; default the downloaded copy, then Audiobookshelf, then ask) or a sheet that greys copies that cannot play, with the reason; "Not the same book" and "Merge with…" are kept in `book_merges` (Room v10). Library (every view, counts, A to Z, server filter: shown as the filtered server's copy), Home, Search, the car and the widgets all use the merged list. Host-tested only; not yet checked on an emulator, a phone or real servers. Phase 2 is below
  - [x] Keep progress in sync between copies (Phase 2, branch `stage-4/mirror-progress`, not merged yet): a save of one copy's progress (player save, server merge) moves the other copies' local rows at once, mapped by fraction of the whole book (whole seconds, never past the end; a finished copy mirrors as finished), flagged `mirrored` (`progress.mirrored`, Room v12); newest wins, a copy's own newer listening is never overwritten, a mirrored row is never mirrored on, and a mirror never rewrites the copy loaded in the player or a copy carrying the user's finished mark. The other copies' servers hear the mapped position through the pending queue, marked as mirrors (`pending_progress.mirrorOf`, Room v13): at most once a minute per copy while playing, once on a real pause or stop (buffering and seeks do not count); "finished" once while the source copy is finished, never a plain position that would un-finish it; never to the copy that is loaded or a server whose last sync failed; no playback session on the other server. A queued mirror is dropped when the pair was split or the setting is off, when either copy has newer listening (a source server that only holds what this phone delivered is not newer listening), or when it is older than the server's own progress; it waits while its book is loaded (that book's live sync replaces it) and when the copies cannot be looked up. The server merge keeps a mirrored row the server only echoes and stores a server time in the future as now. A book that opens weighs every copy's progress (rows on the phone and, online, each copy's server) and starts at the newest. Mark as finished / not finished applies to every copy through its own server (the book itself first: when its own mark fails no other copy is touched), all servers at once, each waiting at most 8 s (then queued), and says so when a copy could not be changed. The review found a mark being undone by the push; fixed, and a scoped re-review's findings fixed too. "Not the same book" stops it at once and leaves each copy its progress; bookmarks and history stay per copy. Settings, Downloads and offline: "Keep progress in sync between copies" (on by default). Host-tested only (rules, repository, pusher with the flusher, starter); not checked on a phone or against real Audiobookshelf and Plex servers
- [x] Plex downloads (branch `stage-4/plex-downloads`): Plex books download like Audiobookshelf books through the same downloader, worker, tables and folders (`<root>/downloads/<serverId>/<bookId>/`). A `DownloadSource` per server kind says which files a book has and how to fetch one; the Plex source plans one file per album track (the rating key is the file id, kept in the `ino` column; extension from the part's file name, else its container; size and length from the track; a book with a track of unknown length is refused before anything is fetched; a size Plex leaves out is read from a one-byte request, only a definite answer without a size fails the book, other answers are tried again later), no request with the token to a plain http address that is not verified on the current network (the same check as a sync, `forTokenRequest`), fetches each part under the server's address in use with the `X-Plex-Token` header (never in an address), and a 401 blanks the token ("Sign in again"). A connection lost mid-file asks `PlexConnectionManager` to re-probe and resumes the same `.part` file at the new address with Range. A finished Plex download plays from the phone (online the app's Plex session still sends timelines and scrobbles, and when the album changed since the download the book streams instead so no position lands on the wrong track; offline the position is queued); without stored chapters it gets one chapter per file. The Download control shows for Plex books in details, the player menu and the Downloads tab (with a source badge); a merged book downloads the copy the source preference picks or asks with a "Download from" sheet showing each copy's size and reachability. Reviewed (PR #52: no Critical; the two Important findings, the token guard and books of unknown length, and the minor ones are fixed). Built against fixtures and mock servers only: NOT tried on a real Plex server or a phone
- [x] Listening stats page: a Statistics page (chart icon in the Library top bar; Settings, Listening) with Week / Month / Year / All time (calendar periods; the change compares the average per day with the whole previous period): time listened, books finished, current streak (days with at least a minute, ending today or yesterday), time saved above 1x, longest day, an Every day heat grid (this week, or the last 14 weeks), weekday bars, Most listened authors / narrators / series, Audiobookshelf / Plex split, Your library, Series finished and Series to finish ("3 of 7 finished · 14 h left", fewest books left first; tapping opens the series). The app logs listening itself for every source, online or offline (`listening_log`, Room v11: per local day and book, written every ~30 s and on pause), and imports each Audiobookshelf server's `/api/me/listening-stats` once per server (retried with a growing wait after a failure; Refresh on the page reads it again). A day counts the larger of the app's log and the imported history. Per-book server totals add to All time only: the part beyond what the app had logged at the import, so the lists keep growing with new listening. A merged book counts once. Host-tested only (rules, recorder, importer, view model, a synthetic fixture); not yet checked on an emulator, a phone or a real server, and the page itself has not been looked at on a device. The review findings are fixed. Deferred: finished books are dated by their last play, and a series counts as finished when all of its books in the library are finished

**Done when:** a Plex audiobook works end-to-end exactly like an ABS one, including Auto and widgets.

### Stage 5: iOS port
Design: `docs/superpowers/specs/2026-10-30-stage-5-ios-design.md` (seven steps; free Apple ID + Sideloadly for the first builds, Robert's iPhone 12 is the target).
- [x] Step 1, shared foundations (branch `stage-5/shared-foundations`, plan `docs/superpowers/plans/2026-10-30-stage-5-step-1-shared-foundations.md`): the HTTP client set-up in commonMain with the engine per platform (OkHttp / Darwin), the Audiobookshelf and Plex APIs, DTOs, both library sources and the server list (`ServerRepositoryImpl`) in commonMain; `PathProvider` and a common DataStore factory, the iOS database builder (bundled SQLite) and the 8-to-9 migration on the connection API; iOS Keychain credential store, `NWPathMonitor` connectivity, bundle version, Plex client id; Koin split into common and platform modules with `initKoin` for Swift; the iOS app starts Koin and shows a start page plus the real Settings, Servers pages (add, edit, delete an Audiobookshelf or Plex server, sync status), with `NotYetOnIosBookRepository` standing in for the book repository ("Library sync is not available on iOS yet"); Info.plist local network text and an ATS exception for plain http servers. Android behaviour unchanged (host tests green). iOS code is type-checked (it compiles to klibs on Windows too) and the CI Mac links the framework and builds the app, but it has NEVER RUN on a simulator or an iPhone
- [ ] Step 2, repositories and sync in common: the iOS app syncs and shows Library, Home, Search and Details
- [ ] Step 3, Kotlin/Native tests in CI (`iosSimulatorArm64Test`), pure-domain tests moved to commonTest
- [ ] Shared Compose UI running on iOS (the whole app: steps 2 to 5)
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
