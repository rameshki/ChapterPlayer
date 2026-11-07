# Jellyfin as a source: design

**Status: DRAFT, awaiting Robert's approval.** Nothing is built yet. Written 2026-11-07 from the Jellyfin server source at tag `v12.4` (released 2026-10-26) and the official docs and release posts; every source is listed at the end with the date it was read. Statements marked **(unverified)** have not been checked against source code or a real server.

## 1. Intent and why
Robert has a Jellyfin server and wants ChapterPlayer to play audiobooks from it like it plays Audiobookshelf (ABS) and Plex books: in the same merged library, with the same player, progress and offline rules. Jellyfin is a common self-hosted media server; people who keep films and music there often keep some audiobooks there too, and Jellyfin 12.0 (2026-09-07) made books a first-class library type (chapters, authors and narrators for audiobooks). Adding it the same way Plex was added keeps the app's promise: one place for all your audiobooks, whichever server holds them.

His decisions (2026-11-07):
- Stage J1: browse, stream, and read the resume position.
- Stage J2: send progress back to Jellyfin (including Mark as finished), merged entries with ABS/Plex copies (Play from / Download from choices) and progress mirroring.
- Stage J3 (later): downloads, through the existing `DownloadSource` port. Only the slot is designed here.
- Sign-in with username and password first; Quick Connect later. Tokens never logged; the token lives in the `CredentialStore` like the others.
- Robert's server has no audiobooks yet, so everything is built against recorded-shape fixtures (synthetic text and ids; the repo is public) and checked on his server at the end.

## 2. Goals, non-goals, anti-goals
**Goals**
- A Jellyfin server can be added in Settings, Servers (address, username, password), shows its sync status like the others, and can be signed in again.
- Its audiobooks show in Home, Library, Search, the group pages, Android Auto and the widgets, with covers, authors, narrators (where the server knows them), descriptions and chapters.
- They stream on Android (Media3) and iOS (AVPlayer) without the token in any address, and open at the server's resume position when it is newer than the phone's.
- J2: listening is reported back (live session plus a direct position write), Mark as finished / not finished works, the offline queue delivers, and a book on Jellyfin and ABS/Plex is one entry with mirrored progress.

**Non-goals (for now)**
- Downloads (J3), Quick Connect, Jellyfin collections (BoxSets) and playlists, bookmarks (Jellyfin has none), server-side listening history import (Jellyfin has none built in), several addresses per server (a Plex-style connection chooser), transcoding, music libraries as a book source (see 4.2), ebooks.

**Anti-goals (never)**
- The token in a URL (no `api_key` / `ApiKey` query parameter), a log, an exception message, a notification or media metadata.
- Storing the password. Sign in again asks for it.
- A token sent to another origin than the server's own (redirects off or same-origin, as for ABS and Plex).
- Writing to the user's Jellyfin library metadata. The app only writes the user's own play state.
- Using the Jellyfin Kotlin SDK if it pulls JVM-only code into `commonMain` (it is built for the JVM/Android; its multiplatform status is **(unverified)**). The app keeps its own small Ktor client, as for Plex.

## 3. Constraints
- All Jellyfin code in `commonMain` (Kotlin Multiplatform; Android and iOS). No JVM-only calls (`String.format`, `java.*`, `System.currentTimeMillis`, `putIfAbsent`, ...): CI has failed on these before.
- Ktor (OkHttp / Darwin engines from `createHttpEngine`), kotlinx.serialization with the shared `ApiJson` (ignore unknown keys), Koin Classic DSL, `KoinModulesTest.verify()` covers every new binding.
- Pure rules in `domain/jellyfin` (mapping, ticks, progress plan, format gate, header building) with commonTest tests; parsers tested in `androidHostTest` against fixtures in `src/androidHostTest/resources/fixtures/jellyfin` (real structure, synthetic text, ids and paths, never real server data).
- `:shared:compileKotlinIosSimulatorArm64` must pass on Windows before each push; the macOS CI job links.

## 4. How Jellyfin models audiobooks
### 4.1 Library and item types
- Libraries are "views" (`GET /UserViews`), each with a `CollectionType`. Audiobooks belong in a **Books** library (`CollectionType` = `books`); the docs show `Audiobooks/Author/Book/Book.flac` with an optional author folder and `cover.jpg` [D1].
- In a Books library the resolver makes an **`AudioBook`** item (type name `AudioBook`) from an audio file; ebooks are `Book` items [S1]. In a Music library the same file is an `Audio` item inside a `MusicAlbum` [S1].
- `AudioBook` extends `Audio` and sets `SupportsPositionTicksResume = true` and `SupportsPlayedStatus = true`; plain `Audio` (music) keeps the base class default `SupportsPositionTicksResume = false` [S2, S3, S4]. So **only `AudioBook` items keep a resume position on the server**; music tracks only keep played / play count (verified in source; the Plex finding of 2026-11-06 was the same problem in another server).

### 4.2 One file vs many files (the big limitation)
- **One file per book (for example one `.m4b` with chapters) is the case Jellyfin handles.** It is one `AudioBook` item with its own `RunTimeTicks`, chapters and user data.
- **A folder of several audio files is not one book.** In `ResolveMultipleAudio` (v12.4) the files are grouped by `AudioBookListResolver`; a group with more than one file is skipped ("For now, until we sort out naming for multi-part books"), and the commented-out `AdditionalParts` line shows multi-part books are not built yet [S1, S5]. In practice (issue #10668, open, "bug" and "confirmed", reported on 10.8.13, still reported in Oct 2026): the book shows as a folder of separate one-file items with no play button, each file listed as its own audiobook in "New audiobooks"; files whose names stack as parts can be hidden entirely [I1, F1]. The forum workaround is to merge the book into one `.m4b` [F1].
- Consequence for the app: stage J1 supports **one-file books**. Each `AudioBook` item is one book. A multi-file book would show as several "books" (one per file), exactly as Jellyfin's own clients show it. Grouping the sibling items of a folder into one book is possible (each file keeps its own user data, like Plex tracks) but is an open question (section 10), not part of J1.
- A Music library would give albums of tracks (Plex's model), but with no server resume at all (4.1), so it is not offered as a book source.

### 4.3 Fields the app reads (`BaseItemDto`)
Declared in `MediaBrowser.Model/Dto/BaseItemDto.cs` [S6]; whether each appears without asking for it in `Fields=` is **(unverified)** and settled by the first real recording.

| App field | Jellyfin | Notes |
|---|---|---|
| id | `Id` (GUID, sent as 32 hex chars) | app id `jf_<Id>` |
| title | `Name` (`SortName` for sorting is not needed: the app sorts by its own rules) | |
| author | People with `Type` = `Author` (12.0+), else `AlbumArtist`, else `Artists[0]` | 12.0 maps album artist (or artist when there is none) to `Author` for audiobooks [S7]; 10.11 does not [S8] |
| narrator | People with `Type` = `Narrator` (12.0+) | from the **Composer** tag ("Audiobookshelf and other tools use Composer for narrator") [S7]; a comment mentions a NARRATOR tag but no code reads it [S7]; 10.11: none |
| duration | `RunTimeTicks` / 10 000 (ms) | 1 tick = 100 ns, 10 000 000 per second |
| chapters | `Chapters[]`: `StartPositionTicks`, `Name` (`ImageTag` ignored) | needs `Fields=Chapters`; extracted only for `AudioBook` items, only from 12.0 [S7, S8, R1]; no end times: each chapter ends where the next starts, the last at `RunTimeTicks` |
| series | `SeriesName` (+ `IndexNumber`?) | `AudioBook` has a `SeriesName` property [S2] but the audio prober does not fill it [S7]; 12.0 reads "name, index, year, and series" from book file names [R1], whether that covers `AudioBook` is **(unverified)** |
| description | `Overview` | 12.0: from the Description tag, else Comment [S7] |
| year, genres, publisher | `ProductionYear`, `Genres`, `Studios` | |
| added | `DateCreated` | Recently added shelf |
| library | `ParentId` of the query (the view id) | |
| cover | `ImageTags["Primary"]` | see 4.4 |
| progress | `UserData` (`EnableUserData=true`) | see 4.5 |
| merge ids | `ProviderIds` (string map) | the built-in providers are film, TV and MusicBrainz ones only; there is no ISBN or ASIN provider in `MetadataProvider` [S9]. A plugin could add keys; none is relied on |

### 4.4 Covers
- `GET /Items/{Id}/Images/Primary?tag={ImageTags.Primary}&maxWidth=600` (the tag changes when the image changes, so it is a safe cache key). The image controller's `GetItemImage` has no `[Authorize]` attribute and the base controller adds none [S10], so covers load **without** a token. The cover loader still only talks to the server's own origin. 12.0: external covers (`cover.jpg` next to the file) work for audiobook files [R1]; embedded art is used otherwise **(unverified)**.
- Consequence: no Plex-style `PlexCoverInterceptor` is needed; the stored cover address carries no secret. The phone's cover provider (notification, car) can fetch it like any address under the server.

### 4.5 User data and resume
`UserData` (`UserItemDataDto`): `PlaybackPositionTicks`, `Played`, `PlayCount`, `LastPlayedDate`, `PlayedPercentage`, `IsFavorite`, `Key`, `ItemId`.
- **Resume positions persist for `AudioBook` items** (4.1). How a reported position is stored depends on the path:
  - **Session reports** (`/Sessions/Playing/Progress` and `/Stopped`) go through `UserDataManager.UpdatePlayState` [S11]. For `AudioBook` items: a position under `MinAudiobookResume` minutes (default 5) is stored as **0**; less than `MaxAudiobookResume` minutes left (default 5), or at the end, stores 0 **and sets `Played = true`**; an item with no runtime is marked played [S11, S12]. Played is never cleared by a later report in the middle of the book. Progress reports are written on every non-automated report that carries a position [S13].
  - **The direct write** `POST /UserItems/{Id}/UserData` with an `UpdateUserItemDataDto` copies each field it is given (position, played, play count, last played date, ...) with **no thresholds** [S11, S14]. Available from 10.9 [S15].
  - `POST /Sessions/Playing` (start) adds 1 to `PlayCount` and sets `LastPlayedDate` = now when the item is not played [S13].
  - A stop report **without** a position counts as finished (play count +1, played, position 0); a stop with `Failed = true` saves nothing [S13].
- The thresholds are server settings; `GET /System/Configuration` carries `MinAudiobookResume` / `MaxAudiobookResume` and needs only a signed-in user at v12.4 (`[Authorize]` on the controller, elevation only for writes) [S16]. Whether a non-admin user really gets them is **(unverified)**.
- Mark played / unplayed: `POST /UserPlayedItems/{Id}` (optional `datePlayed`) and `DELETE /UserPlayedItems/{Id}`, both return the new user data [S17]. Legacy `/Users/{userId}/PlayedItems/{Id}` routes are hidden but still present at v12.4.

### 4.6 The calls (all relative to the server address, which may include a path such as `/jellyfin` behind a reverse proxy)
| Purpose | Call | Since |
|---|---|---|
| Is this Jellyfin, which version, its id | `GET /System/Info/Public` (no auth) | old |
| Sign in | `POST /Users/AuthenticateByName` body `{"Username","Pw"}` | old |
| Libraries | `GET /UserViews` (keep `CollectionType` = `books`) | 10.9 (`/Users/{id}/Views` before) |
| Books, paged | `GET /Items?ParentId={view}&IncludeItemTypes=AudioBook&Recursive=true&Fields=People,Overview,Genres,DateCreated,ProviderIds&EnableUserData=true&EnableImageTypes=Primary&StartIndex={n}&Limit=200&EnableTotalRecordCount=true` | 10.9 for `userId` optional |
| One book (details, chapters) | `GET /Items/{Id}?Fields=Chapters,People,Overview,Genres,MediaSources` | 10.9 |
| Resume read | `GET /UserItems/{Id}/UserData` | 10.9 |
| Server thresholds | `GET /System/Configuration` | old |
| Playback info | `POST /Items/{Id}/PlaybackInfo` (device profile in the body) | old |
| Stream | `GET /Audio/{Id}/stream?static=true&mediaSourceId={msId}&playSessionId={ps}` | old |
| Live report | `POST /Sessions/Playing`, `/Sessions/Playing/Progress`, `/Sessions/Playing/Stopped` | old |
| Direct position write | `POST /UserItems/{Id}/UserData` | 10.9 |
| Mark (un)played | `POST` / `DELETE /UserPlayedItems/{Id}` | 10.9 |
| Download (J3) | `GET /Items/{Id}/Download` (user needs the Download permission) [S18] | old |

Versions: 12.0 removed the `/emby/` and `/mediabrowser/` prefixes, made `GetItems` async ("the same query can return a different result set than it did on 10.11"), and says only endpoints in the OpenAPI spec should be used [R1]. All calls above are in it. **Minimum server: 10.10.7** (the version 12.0 upgrades from; every route above exists from 10.9). **Chapters and narrators need 12.0 or newer**; on 10.10/10.11 books play with one chapter and the author from `AlbumArtist`. Setup reads the version from `/System/Info/Public` and refuses below 10.10 with a plain message.

## 5. Authentication
- **Header** on every call after sign-in, and on the sign-in itself without the token:
  `Authorization: MediaBrowser Client="ChapterPlayer", Device="<Android|iPhone>", DeviceId="<id>", Version="<app version>", Token="<token>"`.
  The server reads `Client`, `Device`, `DeviceId`, `Version` and `Token` from it; the `MediaBrowser` scheme is matched without case [S19]. `X-Emby-Authorization`, `X-Emby-Token`, `X-MediaBrowser-Token` and the `api_key` query parameter are "legacy" and only work when `EnableLegacyAuthorization` is on [S19]; 12.0 turned that off by default, also on existing servers [R1]. The `ApiKey` query parameter is always accepted [S19] but puts the token in a URL: **never used**.
- **Device name** is a generic "Android" / "iPhone" (no personal device names leave the phone). The server URL-decodes each value (`WebUtility.UrlDecode`) [S19], so a `+` would become a space and `%` starts an escape: the app sends only letters, digits, `-`, `.` and spaces in the client fields and URL-encodes the token defensively (tokens are hex at v12.4 **(unverified)**). A wrong password throws a `SecurityException` in `AuthenticateUserByName`; that the middleware answers it with 401 is **(unverified)**.
- **DeviceId must be unique per install and per server row.** A sign-in logs out every earlier token of the same user with the same DeviceId [S20], and an empty DeviceId fails [S20]. The app's ABS device id is a constant per platform, which would make two phones sign each other out. Rule: `DeviceId = "chapterplayer-" + <per-install id> + "-" + <server row id>`, computed, not stored separately (the per-install id is the one `PlexClientIdentifier` already keeps in DataStore / user defaults; it gets a neutral name in step 1).
- **Token**: `AccessToken` from the sign-in answer (also `User.Id`, `ServerId`). It goes into the `CredentialStore` under the server row id, the Room `token` column stays empty, `ServerConfig.toString()` never shows it. Jellyfin access tokens do not expire on their own; they end when the device is removed in the dashboard, the user signs in again with the same DeviceId, or the server signs the user out **(the last two verified in source, password-change behaviour unverified)**. There is no refresh token.
- **401**: like Plex. A 401 on any authorized call blanks the stored token (`markSignedOut`) and is `SignInRequiredException`, so Settings shows "Sign in again" and the queue stops. 403 is "The server does not let this account play this book". Sign in again asks for the password again (never stored).
- The sign-in answer and every request/response body with `AccessToken` must never be logged: the HTTP logger's redaction adds the `Authorization: MediaBrowser ...` header (already redacted as `Authorization`) and the body logger must not print sign-in bodies (check in step 1; the Diagnostics `redact()` already removes UUIDs and long opaque strings).
- **Identity check**: `/System/Info/Public` gives the server `Id`; the app keeps it (in `ServerStatusStore`, like Plex's `machineId`) and a later sign-in that finds another id asks before replacing the server row. **(builder can decide whether this is worth it in J1.)**
- Quick Connect (later): `GET /QuickConnect/Enabled`, `POST /QuickConnect/Initiate` (12.0 removed the GET alias [R1]), poll `GET /QuickConnect/Connect?secret=`, then `POST /Users/AuthenticateWithQuickConnect`. Enabled by default [D2].

## 6. Playback
- **PlaybackInfo first** (`POST /Items/{Id}/PlaybackInfo`, body: a minimal `DeviceProfile` with `DirectPlayProfiles` for the containers this platform plays): returns `MediaSources[]` (id, container, `SupportsDirectPlay`, size, bitrate) and a `PlaySessionId`. The app uses the first media source that can be played directly. Exact profile fields that make the server say "direct play" for an `.m4b` are **(unverified)**; fallback if PlaybackInfo is awkward: `mediaSourceId` = the item id and the container from `MediaSources` of the details read (one-file items have one source).
- **Stream address**: `{base}/Audio/{Id}/stream?static=true&mediaSourceId={msId}&playSessionId={ps}`. `static=true` sends the original file; `GetStaticFileResult` returns a `PhysicalFileResult` with `EnableRangeProcessing = true` [S21], so Range requests and seeking work like ABS files. **The address carries no token.** At v12.4 `AudioController` has no `[Authorize]` attribute [S21] (the stream works without a token); the app sends the header anyway, so a reverse proxy or a later server that requires it still works.
- **Token as a header, never in the URL**: Android: `AuthorizingDataSourceFactory.openAuthorized` gets a third branch (a Jellyfin server matched by `streamServerFor`/`serverFor` adds `Authorization: MediaBrowser ... Token="..."`; a 401 is `SignInRequiredException`, no refresh); redirects stay manual (`redirectTarget`, same origin only with a credential). iOS: `StreamAuthorizer` adds the Jellyfin case (the same one-byte `Range: bytes=0-0` check on the probe client, then the header in `AVURLAssetHTTPHeaderFieldsKey`, the same known limitation as ABS and Plex for later redirects).
- **One file = one track**: `PlaybackSession` with one `PlaybackTrack` (startMs 0, the item's duration, mime type from the container: `m4b`/`m4a`/`mp4` = `audio/mp4`, `mp3` = `audio/mpeg`, `flac` = `audio/flac`, `ogg`/`opus` = `audio/ogg`), `fileId` = the item id (for J3 download matching), chapters from 4.3 (none: one chapter "Part 1", as Plex downloads do).
- **Formats**: Android Media3 plays MP4/AAC (m4b, m4a), MP3, FLAC, Ogg Vorbis/Opus, WAV. AVPlayer plays MP4/AAC, MP3, FLAC, WAV, not Ogg/Opus (the reason ABS is asked for HLS on iOS). J1 rule: a book whose container the platform cannot play fails with the Plex text "This book's audio format cannot be played on this phone" (`applePlays`-style gate in `domain/jellyfin`). **No transcoding in J1/J2** (the universal endpoint `GET /Audio/{Id}/universal` [S22] can transcode; seeking in a transcode restarts it server-side; later, if Robert has Ogg books and an iPhone).
- **Resume**: `startPositionMs` = the server's `PlaybackPositionTicks` / 10 000 unless `Played` (then 0, as Plex); `PlaybackStarter` weighs it against local progress (newest wins on `LastPlayedDate` vs `lastPlayedAt`). A server position of 0 that is not played reads as **no progress** (`null`), so the 5-minute rule (4.5) can never pull a book back to the start.
- **Session id**: the app's own id (`jf-<item>-<n>`) holding the `PlaySessionId` and `MediaSourceId`, like Plex's `LiveSession`; closed on the next load.

## 7. Progress reporting (J2)
Jellyfin's session reports are what make the dashboard show "now playing" and what other Jellyfin clients resume from, but they apply the audiobook thresholds (4.5): reported in the first 5 min the server keeps 0; in the last 5 min it marks the book **finished** and keeps 0. The app's rule is that the phone's own progress is the truth, so the design uses both paths.

| When | Call(s) | Why |
|---|---|---|
| Book loads and starts playing | `POST /Sessions/Playing` (ItemId, MediaSourceId, PlaySessionId, PositionTicks, CanSeek=true, PlayMethod=DirectPlay, IsPaused=false) | dashboard; the server adds the play count |
| Every ~10 s while playing (`ProgressSyncer` live sync) | `POST /Sessions/Playing/Progress` (same ids, PositionTicks, IsPaused=false, `EventName`=`timeupdate`) | server resume in the middle of the book |
| ...when the position is in the threshold bands (`position < Min` or `remaining < Max`, values read once per server sync from `/System/Configuration`, defaults 5/5 when unreadable) | the same report **plus** `POST /UserItems/{Id}/UserData` `{PlaybackPositionTicks, Played=false, LastPlayedDate=now}` | undo the server's 0 / finished, so the real position stays |
| Pause, or a real stop | `/Sessions/Playing/Progress` with IsPaused=true, plus the UserData write | the pause place survives |
| Book closed (next load, idle 10 min, app stop) | `POST /Sessions/Playing/Stopped` (with PositionTicks, never without: no position = finished) then the UserData write when in a band | end the server session |
| Live report failed (offline, server down) | queue in `pending_progress`, flushed later with **only** `POST /UserItems/{Id}/UserData` (`pushProgress`) | no sessions for old positions; no thresholds |
| Mirror from another copy (J2) | `pushProgress` = UserData write (finished: `POST /UserPlayedItems/{Id}`) | same as the queue |
| Mark as finished | `POST /UserPlayedItems/{Id}` (and the live session reports nothing more, as Plex) | Jellyfin's own played flag, position 0 |
| Mark as not finished | `DELETE /UserPlayedItems/{Id}` then UserData `{PlaybackPositionTicks=0}` | as ABS: an unfinished book restarts at 0 |
| The book really ends while playing | `/Sessions/Playing/Stopped` at the end (server marks it played) | matches the app's own finished rule |

- **Unknown session after a restart**: unlike Plex, Jellyfin finds or creates the session from the request's auth header on every `/Sessions/Playing*` call (`RequestHelpers.GetSessionId` calls `LogSessionActivity`) [S23], so a report after a server restart is accepted (a progress report for a session the server truly cannot find returns quietly, a stop throws not found [S13]). The app still treats any failed live report like Plex/ABS: queue the position and let the flusher write it with the UserData call. After an app restart the app's own session id is unknown to `JellyfinLibrarySource`, so `syncPlayback` fails and the position is queued, exactly like Plex today.
- **`fetchProgress`** = `GET /UserItems/{Id}/UserData`, **`fetchAllProgress`** = the user data that comes with the library list (`EnableUserData=true`), so the sync merges Jellyfin progress like ABS (no extra calls; Plex cannot). `updatedAt` = `LastPlayedDate` (none: not newer than anything).
- Pure rules (`domain/jellyfin/JellyfinProgress.kt`): ticks to ms and back, "in a threshold band", "server reading is no progress", the call plan per event (the table above as a function), so the decisions are tested without a server.

## 8. Connections
One address, typed by the user (http or https; local or remote; with an optional path). No plex.tv-style list of connections exists for Jellyfin, so **no connection chooser**: the Plex one stays Plex-only. `/System/Info/Public` (no auth) checks the address at setup and in Test connection (`ProductName`, `Version`, `Id`). Redirects: the shared Ktor client already drops credentials across origins (`OriginSafeRedirects`); the probe client does not follow redirects. A plain http address on a foreign network is the user's choice (as for ABS). Two addresses (LAN and remote) are an open question.

## 9. Mapping onto the app
- **`ServerSource.JELLYFIN`** (new enum value). **Room impact: none.** `servers.source` and `books.source` store the enum name as a string (`ServerSource.valueOf`), `listening_log.source` stores the name too; no column, table or migration changes. Note: a build older than this one would crash on reading `JELLYFIN` (only reachable by a downgrade or a backup restore into an old build; acceptable, mentioned for review). Every `when (source)` and `ServerSource.PLEX` check (29 files today) gets a Jellyfin arm; the compiler finds the exhaustive ones, a grep finds the rest.
- **Book id**: `jf_<Id>` (the 32-hex id as sent). Helpers `jellyfinItemId(bookId)` / `isJellyfinBook` in `domain/jellyfin/JellyfinBooks.kt`.
- **Classes** (all `commonMain`): `data/remote/jellyfin/JellyfinApiService` (one method per call in 4.6, header built by `jellyfinAuthHeader(...)`), `dto/JellyfinDtos.kt`, `JellyfinPaging.kt` (pages of 200, follows `TotalRecordCount`); `data/source/JellyfinLibrarySource : LibrarySource` (`fetchSnapshot`, `fetchLibraries` = books views, `fetchCollections` = empty in J1, `fetchBookDetails`, `startPlayback`, J2: `syncPlayback`, `closePlayback`, `pushProgress`, `fetchProgress`, `fetchProgressReading`, `fetchAllProgress`; bookmarks and listening stats keep the empty defaults); `data/auth/JellyfinSignIn` (port `JellyfinSignInPort`, like `AbsSignInPort`); `domain/jellyfin/*` for the pure rules. `SourceSnapshot.failedLibraries` is used like Plex (one view failing keeps its cached books).
- **`ServerConfig`**: no new field. `libraryId` / `excludedLibraryIds` hold view ids; the user id is not needed (10.9+ routes take it from the token; kept from the sign-in answer only if a call turns out to need it).
- **DI**: `commonSourceModule` (or wherever the Plex source is bound) adds the API service, the source (bound into the list of `LibrarySource`s), the sign-in port and `markSignedOut`; J3 adds `JellyfinDownloadSource` to the `DownloadSource` list. `KoinModulesTest` covers them.
- **Server setup screen**: the source chips come from `ServerSource.entries`, so a Jellyfin chip appears; it shows Name, Address, Username, Password and Sign in (the ABS sign-in section's layout and keyboard padding), then the books libraries check-list. Errors in words: "This is not a Jellyfin server", "This Jellyfin version is too old (10.10 or newer is needed)", "Wrong username or password", "The server has no Books library. Create one with the type Books." Edit Server: address, name, Sign in again.
- **Settings text and badges**: `ServerSource` labels "Jellyfin"; `serverDisplayNames` unchanged; the source badge rule `showSourceBadges` becomes "two or more kinds of server" (today ABS and Plex both); the badge for Jellyfin is a third colour/letter checked by `PaletteContrastTest`. Source choices: `type:JELLYFIN` works as stored (`SourceChoice.OfType` reads `ServerSource.entries`); `SourceChoiceText` shows a Jellyfin line only when such a server exists. Servers page: sync status and Test connection as for ABS; Diagnostics: the UserData fields of the copy.
- **Merging (J2)**: the same rule as today (normalised title and first author, lengths within 2% or 5 min). Jellyfin exposes no ISBN/ASIN (4.3), so no id-based merge. Ordering: `memberOrder`/`metadataOrder` today put ABS first and everything else second; new order ABS, Jellyfin, Plex for metadata (Jellyfin 12 has narrators and chapters, Plex has neither), and the play-choice and reachability rules unchanged. Mirroring (`mirroredProgress`, `ProgressMirrorPusher`, the flusher's drop rules) is server-kind agnostic; Jellyfin needs only its `pushProgress`. Tests: the existing merge and mirror suites gain Jellyfin members.
- **Lengths**: `RunTimeTicks` comes with each listed `AudioBook`, so no `PlexLengthFiller`-like pass is needed **(verify on the first recording; if lists lack it, the details read fills it like Plex details do)**.
- **Covers**: `coverUrl` = the image address of 4.4; `CoverRules`/`CoverBytesLoader` treat Jellyfin like ABS (origin check), no token interceptor.
- **Android Auto, widgets, Up next, favourites, stats**: generic over `PlaybackStarter` and book ids; the stats source split gets a "Jellyfin" name (`StatsText`), `ListeningRecorder` records the source name.
- **Downloads slot (J3, not detailed)**: `JellyfinDownloadSource : DownloadSource`: `plan` = one `PlannedFile` per book (ino column = item id, size from `MediaSources[0].Size`, extension from the container), `authorized` adds the header to `GET /Items/{Id}/Download` (403 when the user lacks the Download permission: a plain message) or the static stream, 401 = sign in again, `fileLengths` = `RunTimeTicks`. The offline play path (`playableFromPhone`) matches by `PlaybackTrack.fileId` as for Plex.

## 10. Stage and step plan
Each step: its own plan in `docs/superpowers/plans/`, branch `stage-j/<name>`, fixtures in `fixtures/jellyfin`, host tests, debug and release builds, iOS klib compile, review, one fix wave, then `PROJECT_STATUS.md`, `CLAUDE.md`, `ROADMAP.md`.

**Stage J1: browse, stream, read the resume position**
1. **Server kind and sign-in.** `ServerSource.JELLYFIN` everywhere (labels, badges, `when` arms), the per-install DeviceId, `jellyfinAuthHeader`, `JellyfinApiService` (`/System/Info/Public`, `AuthenticateByName`, `/UserViews`), `JellyfinSignIn`, the setup screen section, Sign in again, 401 handling, HTTP-log redaction check. Fixtures: `system-info-public.json` (12.x and a 10.10 one), `authenticate-by-name.json` (synthetic token), `user-views.json` (a books, a music and a movies view). Tests: header text and escaping, version gate, view filter, wrong password (401) wording, token never in `toString`/logs, Koin.
2. **Library sync.** Paged `/Items` for `AudioBook` in each books view, `jellyfinItemToBook` (author/narrator fallbacks, ticks, cover address, `DateCreated`), `fetchSnapshot` with per-view failures, details with chapters and overview. Fixtures: `items-audiobooks-page-1.json` / `page-2.json` (12.x shape: People with Author and Narrator), `items-audiobooks-10-10.json` (no People roles, `AlbumArtist` only), `item-detail-chapters.json`, `item-detail-no-chapters.json`, `items-folder-of-parts.json` (a multi-file folder as Jellyfin shows it). Tests: mapping, paging to `TotalRecordCount`, chapters end times, a view failing keeps its books, merged list with an ABS copy (it already merges by title and author).
3. **Streaming and resume read.** `startPlayback` (PlaybackInfo, static stream address, one track, format gate per platform), Android data source branch, iOS `StreamAuthorizer` case, `fetchProgress`, `fetchAllProgress` from the list, "0 and not played = no progress". `syncPlayback` / `pushProgress` stay no-ops (J1 writes nothing to the server). Fixtures: `playback-info-direct.json`, `playback-info-no-direct.json`, `user-data-in-progress.json`, `user-data-played.json`. Tests: address has no token, header added only for the server's origin, redirect rules, formats, start position rules, starter newest-wins with Jellyfin readings.
4. **Check on Robert's server** (after he follows `docs/JELLYFIN_SETUP.md`): sign in, sync, covers, chapters, play from the start and from a Jellyfin web resume point, seek, Android Auto list, iPhone stays compile-only. Record the real shapes of the step 1 to 3 calls with the Diagnostics page or a script, replace every text, id and path with synthetic ones, and update the fixtures where the real shape differs.

**Stage J2: progress back, merged copies, mirroring**
5. **Reporting.** Live session calls, the threshold bands with `/System/Configuration`, UserData writes, `pushProgress` for the queue, Mark as finished / not finished (`/UserPlayedItems`), 401 / 403 handling in `ProgressSyncer` and the flusher, `fetchProgressReading` for Diagnostics. Fixtures: `system-configuration.json`, `user-played-items.json`. Tests: the call plan table, band edges (4:59 / 5:00, last 5 min), finished pin, no stop without a position, queue delivers only UserData.
6. **Merged entries and mirroring.** Member and metadata order with three kinds, Source sheets and Settings lines for Jellyfin, mirror pushes to Jellyfin copies, the flusher's drop rules with a Jellyfin source copy, Diagnostics merge facts. Tests: the existing merge/mirror suites extended with Jellyfin members.
7. **Check on Robert's server**: listen 10 min on the phone, Jellyfin web shows the place; listen 2 min (below the band), the server keeps the real place; stop in the last 5 min, the book is not finished unless it ended; Mark as finished both ways; a book on ABS and Jellyfin mirrors; offline listening is delivered later.

**Stage J3 (later): downloads** through `JellyfinDownloadSource` (9, last bullet), its own design note when it starts.

## 11. Risks
- **Multi-file books** (4.2) are the main gap: most ABS-style libraries are folders of MP3s. Jellyfin shows them as one "book" per file. Mitigation: the setup guide asks for one `.m4b` per book; grouping is an open question.
- **Version spread**: 10.10/10.11 servers have no audiobook chapters or narrators; 12.0 changed `GetItems` results [R1]. Mitigation: version gate, fallbacks, fixtures for both.
- **Threshold rules** (4.5) can mark a book finished from a pause near the end. Mitigation: the band rule in 7; a wrong guess of the server's values (unreadable config) leaves at most the default 5/5 behaviour.
- **Anonymous stream and image endpoints** (6, 4.4): good for covers, but if a future server or a proxy requires auth for images, covers would need the header (the cover loader can add it like Plex's interceptor). Low risk, easy fix.
- **DeviceId collisions** sign other installs out (5). Mitigation: per-install, per-row id from step 1.
- **Server-side session list**: every start opens a server session; a crash leaves it until the server times it out. Harmless.
- **Public repo**: fixtures must be synthetic; Jellyfin ids are GUIDs, so new random-looking synthetic ids are used, never copied ones.

## 12. Open questions
**Robert must answer**
1. Which Jellyfin version does your server run (Dashboard, top of the page)? Below 10.10 the app refuses; below 12.0 there are no chapters or narrators from the server.
2. Are you willing to keep books on Jellyfin as one file each (`.m4b` with chapters)? If many of your books are folders of MP3s, do you want the app to group a folder's files into one book (each file keeps its own Jellyfin position; it would not match how Jellyfin's own apps show them)?
3. Is the Jellyfin server reached at one address (for example only at home, or only through a reverse proxy with https), or do you need a home address and an outside address like Plex?
4. Should a book that is only on Jellyfin show the Jellyfin badge like Plex and ABS do (a third colour), or only when two kinds are present (today's rule)?
5. Will the same books be on Jellyfin and Audiobookshelf (so merging matters from J2), or different books?

**Builder can decide**
- Exact `Fields=` list and page size; whether the list carries `RunTimeTicks` and `People` without asking.
- The PlaybackInfo device profile, or skipping PlaybackInfo when one-file items always have one source.
- Keeping the server `Id` to catch a changed server behind the same address.
- How often to read `/System/Configuration` (each sync is enough).
- Mime type table and whether to send `PlayMethod=DirectPlay` or `DirectStream`.
- Wording of the setup errors and the Servers page lines.

## 13. Unverified until Robert's server has books
- The real JSON of every call (fixtures are built from the v12.4 source and DTO definitions, not recorded).
- Which fields the list returns without `Fields=`; whether `RunTimeTicks` is in list results.
- Chapters on a real `.m4b` (12.0+), and on MP3 with ID3 CHAP frames.
- Author and narrator roles from real tags (album artist / composer), and whether `SeriesName` is ever filled for `AudioBook` items.
- That PlaybackInfo with the app's profile says direct play, and that `static=true` Range requests seek correctly in Media3 and AVPlayer.
- That the stream works with only the header (and also without it, as the source suggests).
- That a non-admin user can read `/System/Configuration`.
- The threshold behaviour as seen in Jellyfin web after the app's reports and UserData writes.
- How a multi-file folder appears through `/Items` on Robert's version (separate items or hidden).
- The status code of a wrong password, the token's character set, cover behaviour for embedded art, password change and token lifetime.

## Sources (all read 2026-11-07)
Server source at tag `v12.4` (released 2026-10-26) unless noted; `https://github.com/jellyfin/jellyfin/blob/v12.4/<path>`.
- [S1] `Emby.Server.Implementations/Library/Resolvers/Audio/AudioResolver.cs` (`ResolveMultipleAudio`, AudioBook only in `books` collections)
- [S2] `MediaBrowser.Controller/Entities/AudioBook.cs`
- [S3] `MediaBrowser.Controller/Entities/Audio/Audio.cs`
- [S4] `MediaBrowser.Controller/Entities/BaseItem.cs` (`SupportsPositionTicksResume => false` by default)
- [S5] `Emby.Naming/AudioBook/AudioBookListResolver.cs`
- [S6] `MediaBrowser.Model/Dto/BaseItemDto.cs`
- [S7] `MediaBrowser.Providers/MediaInfo/AudioFileProber.cs` (chapters for AudioBook, Author / Narrator roles)
- [S8] the same file on branch `release-10.11.z` (no chapters, no Author / Narrator roles)
- [S9] `MediaBrowser.Model/Entities/MetadataProvider.cs`; `Jellyfin.Data/Enums/PersonKind.cs`
- [S10] `Jellyfin.Api/Controllers/ImageController.cs`, `Jellyfin.Api/BaseJellyfinApiController.cs`, `Jellyfin.Server/Extensions/ApiServiceCollectionExtensions.cs` (no fallback policy)
- [S11] `Emby.Server.Implementations/Library/UserDataManager.cs` (`UpdatePlayState`, `SaveUserData` from the DTO)
- [S12] `MediaBrowser.Model/Configuration/ServerConfiguration.cs` (master; defaults MinResumePct 5, MaxResumePct 90, MinResumeDurationSeconds 300, MinAudiobookResume 5, MaxAudiobookResume 5)
- [S13] `Emby.Server.Implementations/Session/SessionManager.cs` (start, progress, stop)
- [S14] `MediaBrowser.Model/Dto/UpdateUserItemDataDto.cs`
- [S15] `Jellyfin.Api/Controllers/ItemsController.cs` on `release-10.8.z` (no UserData routes) and `release-10.9.z` (`UserItems/{itemId}/UserData` GET and POST)
- [S16] `Jellyfin.Api/Controllers/ConfigurationController.cs`
- [S17] `Jellyfin.Api/Controllers/PlaystateController.cs`
- [S18] `Jellyfin.Api/Controllers/LibraryController.cs` (`Items/{itemId}/Download`, Download policy)
- [S19] `Jellyfin.Server.Implementations/Security/AuthorizationContext.cs` (master for the token sources; v12.4 for the URL decoding)
- [S20] `SessionManager.GetAuthorizationToken` (same file as S13)
- [S21] `Jellyfin.Api/Controllers/AudioController.cs`, `Jellyfin.Api/Helpers/AudioHelper.cs`, `Jellyfin.Api/Helpers/FileStreamResponseHelpers.cs`
- [S22] `Jellyfin.Api/Controllers/UniversalAudioController.cs`
- [S23] `Jellyfin.Api/Helpers/RequestHelpers.cs` (`GetSession` calls `LogSessionActivity`)
- [R1] Jellyfin 12.0 release post, 2026-09-07: https://jellyfin.org/posts/jellyfin-release-12.0 ; releases list via the GitHub API (v12.1 2026-09-15 to v12.4 2026-10-26)
- [D1] Jellyfin docs, Books: https://jellyfin.org/docs/general/server/media/books/ (undated page)
- [D2] Jellyfin docs, Quick Connect: https://jellyfin.org/docs/general/server/quick-connect/ (undated page)
- [I1] Issue #10668 "Multi-Part Audiobook being treated as folder with individual files instead of one book": https://github.com/jellyfin/jellyfin/issues/10668 (opened 2023-12-03, open, last comment 2026-10-17)
- [F1] Forum thread "Audiobooks with multi parts", 2023-12-02: https://forum.jellyfin.org/t-audiobooks-with-multi-parts
- Third-party, background only: Help Net Security on 12.0, 2026-09-08 (https://www.helpnetsecurity.com/2026/09/08/jellyfin-12-0-security-fixes-arrive-alongside-the-removal-of-legacy-client-logins/); the Bookshelf plugin repository, archived 2026-06-30 (https://github.com/jellyfin/jellyfin-plugin-bookshelf).
