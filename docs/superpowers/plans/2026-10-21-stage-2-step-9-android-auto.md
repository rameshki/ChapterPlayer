# Stage 2 step 9: Android Auto

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the car (Android Auto) you see Continue, Recently added, Library and Downloaded; one tap on a book plays it; "Hey Google, play <book>" works; the Now Playing queue lists the book's chapters; the book resumes by itself when the phone connects to the car (switch in Settings). Android Auto never gets a second player: it talks to the same `PlaybackService` as the phone.

**Architecture:** `PlaybackService` (a `MediaLibraryService`) already exists with a root-only `LibraryCallback`. The callback becomes the car's entry point: it serves a browse tree built from the same repository data as the phone (visible libraries only), resolves media ids it handed out, and starts books through the one load path (`PlaybackStarter`: server session, listened time, saver). Who may do what is decided by a pure trust rule. The tree, ids, letter ranges, voice matching and trust rule are pure code in `domain/car` with host tests; the Media3 mapping is thin Android code checked on an emulator with the Desktop Head Unit (the emulator image has the Android Auto app).

**Tech Stack:** Media3 `MediaLibraryService` / `MediaLibrarySession.Callback`, Koin, Room-backed `BookRepository`, `PlaybackStarter`, `AppSettings.resumeOnCarConnect`.

**Spec:** `docs/superpowers/specs/2026-10-14-stage-2-abs-core-design.md` (section Android Auto, build-order step 9). Step 6 plan "For step 9" lists what the service gate must become.

## Rulings made while planning
- Four top tabs under the root: **Continue** (in-progress books, most recent first), **Recently added** (newest 50), **Library** (Series, Authors, Collections, All books; All books grouped into letter ranges "A to C"... so no list is long; Narrators are not in the car), **Downloaded** (placeholder item "Downloads arrive in a later update" until Stage 3). Only books of visible libraries appear; the car shows ABS and Plex books alike but only ABS books play (Plex shows the existing "cannot play yet" as a non-playable item with that subtitle).
- Offline: books that are not downloaded are shown dimmed with a cloud icon and cannot be played when there is no connection (until Stage 3 nothing is downloaded, so offline all items are dimmed; a banner item at the top of each list offers the Downloaded tab). Playing something already loaded still works.
- Media ids: `car:<kind>[:<arg>]` for browse nodes (`car:root`, `car:continue`, `car:recent`, `car:library`, `car:series`, `car:series:<name-key>`, `car:authors`, `car:author:<key>`, `car:collections`, `car:collection:<id>`, `car:all`, `car:all:<range>`, `car:downloaded`) and the book id itself (`abs_<id>` / the Plex id) for playable books. Book ids from the car are resolved against the repository; unknown ids fail.
- Trust: the controller's package decides. Own app: full access. Allowlist (constant in `domain/car`): `com.google.android.projection.gearhead` (Android Auto), `com.google.android.googlequicksearchbox` (Assistant), `com.google.android.carassistant`, `com.android.systemui`, `com.google.android.autosimulator`: browse and start books by id or search only (never arbitrary media items/uris). Anything else is refused. `MediaSession.ControllerInfo.isTrusted` alone is not enough (it needs the system signature).
- Starting from the car: `onSetMediaItems` / `onAddMediaItems` for a trusted foreign controller resolves the requested book id (or the search query) to an `AudioBook` and runs `PlaybackStarter.start(book)` in the service scope (same load path as the phone: server session, listened time, progress saver). The callback then answers with the player's CURRENT items and start position once the starter finished, so the car's request is satisfied by the one load, never by a second set of items.
- Resume on connect: when a trusted controller connects and `resumeOnCarConnect` is on and nothing is loaded or playing, start the book that was last played (most recent progress) through the starter, rewound by the existing resume rule. Never when the user paused it manually in the last minute (use the saved pause time if available, otherwise skip this guard and say so in the report).
- `onPlaybackResumption` (headset or system "play" after the process died): resolve the last played book through the starter's data and return its items with the start position.
- Now Playing buttons in the car: Previous/Next skip back 15 s / forward 30 s (already the player's behaviour); no speed or sleep timer in the car (voice only: the Assistant handles "set a sleep timer"). The queue button lists the book's CHAPTERS; tapping one jumps. The player holds one media item per audio FILE, so chapters are exposed as the session's queue through a car-only view: if Media3 cannot present chapters as the queue without changing the phone's player, show them as a browsable "Chapters" list on the Now Playing screen (`car:chapters`) and record the limitation in the PR; do NOT change the player's item structure for the phone.
- Cover art: use the same cover URL the phone uses; the car loads it through the service, so the token must NOT be put in a URL; use the app's cover loading (an artwork `Uri` served through a content provider or the already-cached file) and say what you chose in the report. If this cannot be done safely in this step, show no artwork in the car and list it as a known gap (never expose a token in a URI).
- Settings: an "Android Auto" section with a switch "Resume when connected to the car" (setting exists: `resumeOnCarConnect`, default on); the line explains what it does. Add the switch if the Settings screen does not have it yet.

## Global Constraints
- One player only; the phone UI, notification and lock screen keep working exactly as before (regression-check play/pause, chapters, mini player).
- Tokens never in URIs, logs or exceptions; the service stays exported only for the media session actions already declared.
- Android Auto manifest: `com.google.android.gms.car.application` meta-data pointing to `res/xml/automotive_app_desc.xml` (`<automotiveApp><uses name="media"/></automotiveApp>`), no other permissions.
- Lists stay short for driving: no list over ~100 items (letter ranges, series and authors are paged by the data they have); strings are plain and short, no emoji.
- `commonMain` portable; public repo: synthetic data only.
- Commits end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey`; CRLF working tree: check every edit applied.

## Review Focus
1. A foreign controller asking for arbitrary media items or uris (must be refused), a spoofed package name (the check uses the framework-verified `controllerInfo.packageName`), ids from an old or deleted book, an empty library, a server removed while the car is connected.
2. Starting a book from the car while the phone is already playing another book (switches cleanly, saves the old position first), while offline, for a Plex book, for a book with no chapters.
3. Voice search: empty query ("play something": resume the last book), a query matching several books (best match first, never an arbitrary one), a series or author name (plays the next unfinished book of it), no match (clear spoken failure, not a crash).
4. Resume on connect: connecting twice, connecting while the phone is already playing, switch off, no history yet, a finished book (do not restart it).
5. Lists: 5,000 books (All books ranges), duplicate titles, very long titles, a book with a missing cover, children requested for an unknown parent, paging parameters (`page`, `pageSize`) respected.

---

## File structure
- `shared/src/commonMain/kotlin/com/chapterplayer/domain/car/CarRules.kt` (new, pure): ids, trust, letter ranges, voice matching, subtitles.
- `shared/src/commonMain/kotlin/com/chapterplayer/domain/car/CarBrowse.kt` (new, pure): the node model and the tree from lists of books/progress/collections.
- `shared/src/androidMain/kotlin/com/chapterplayer/data/playback/CarLibraryCallback.kt` (new): the Media3 callback; `PlaybackService` uses it instead of the inline `LibraryCallback`.
- `androidApp/src/main/AndroidManifest.xml`, `androidApp/src/main/res/xml/automotive_app_desc.xml`; Settings switch in `ui/settings`.

### Task 9.1: Car rules (pure)  [local-model task: tests first]
- `CarRules.kt`: `const` node ids; `fun carNodeId(kind: String, arg: String? = null): String`; `fun parseCarNodeId(id: String): CarNode?` (`CarNode(kind, arg)`; null for a book id or anything not starting `car:`); `val TRUSTED_CAR_PACKAGES: Set<String>`; `fun isTrustedCarController(callerPackage: String?, ownPackage: String): CarTrust` where `enum class CarTrust { OWN, CAR, NONE }`; `fun letterRanges(titles: List<String>): List<LetterRange>` (`LetterRange(label, from, to)`: group the sorted sort-titles (ignoring a leading "The ", "A ", "An ", case-insensitive; non-letters group as "#") into ranges of at most 25 titles, labelled "A to C" / "D" / "#"; deterministic); `fun booksForRange(...)`; `fun matchBooksForQuery(query: String, books: List<BookSearchEntry>): List<String>` (ids best first: exact title match, then title starts with, then title or author or series contains all words, case/punctuation-insensitive; empty query returns empty); `fun carSubtitle(author, progressPercent, offline: Boolean): String`.
- [ ] Tests first with exact values; implement; commit "Stage 2: Android Auto rules".

### Task 9.2: Browse tree (pure)
- `CarBrowse.kt`: `data class CarItem(id, title, subtitle, coverKey: String?, browsable: Boolean, playable: Boolean, dimmed: Boolean)`; `fun carRoot(): List<CarItem>` (the four tabs); `fun carChildren(nodeId: String, data: CarData, online: Boolean): CarPage?` where `CarData` holds visible books, progress, collections, series/author groups (reuse `domain/browse` grouping and Continue/Recently-added rules) and `CarPage(items, total)`; paging (`page`, `pageSize`) applied by the callback via `slice`.
- [ ] Tests (each tab, ordering, dimming offline, placeholder Downloaded, ranges, paging, unknown node null, Plex books non-playable with subtitle); commit.

### Task 9.3: The service callback
- `CarLibraryCallback` implements `onGetLibraryRoot`, `onGetChildren` (pages), `onGetItem`, `onSearch`/`onGetSearchResult`, `onSetMediaItems`/`onAddMediaItems` (own app: existing track rule; car: resolve a book id or search query, run `PlaybackStarter.start`, answer with the player's current items), `onConnect` (accept own + car controllers; others get a session without browse/command rights), `onPlaybackResumption`, and the resume-on-connect rule. Chapters in the car per the ruling. Replaces `LibraryCallback` in `PlaybackService`.
- Manifest meta-data and `automotive_app_desc.xml`; Koin gets what the callback needs (the service resolves them through `KoinComponent`).
- [ ] Host tests for everything with logic (resolution, trust decisions, resume rule using fakes); the Media3 plumbing is checked on the emulator.

### Task 9.4: Settings switch, verification, docs, PR (controller)
- Settings "Android Auto" section with the resume switch; emulator verification with the Desktop Head Unit (Android Auto app is on the emulator image): browse each tab, play a book, skip buttons, chapters, voice search where possible (Assistant may not work on the emulator: say so), resume on connect on and off, phone UI regressions; privacy scan; docs (`ROADMAP.md` Android Auto items, `CLAUDE.md`); final whole-branch review (opus), ONE fix wave, scoped re-review; PR; merge as authorised once CI is green; then tag `v0.2.0` after the user is told Stage 2 is complete (the owner asked tags per stage).

## Delegation
Task 9.1 and 9.2 pure logic go to gemma4:26b through OpenClaw (tests first, one file per call). 9.3 goes to a Claude implementer; 9.4 is the controller.
