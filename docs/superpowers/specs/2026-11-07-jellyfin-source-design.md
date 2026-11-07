# Jellyfin as a source: design

**Status: APPROVED 2026-11-08 (Robert), with Quick Connect added to J3.** Nothing is built yet; the J1 and J2 implementation plan is `docs/superpowers/plans/2026-11-08-jellyfin-j1-j2.md`. v1 2026-11-07; v2 2026-11-08 takes in Robert's answers (one `.m4b` per book, one address, today's badge rule, the same books on all three servers) and settles the builder's questions as marked calls (section 12). Written from the Jellyfin server source at tag `v12.4` (released 2026-10-26) and the official docs and release posts; every source is listed at the end with the date it was read. Statements marked **(unverified)** have not been checked against source code or a real server.

## 1. Intent and why
Robert has a Jellyfin server and wants ChapterPlayer to play audiobooks from it like it plays Audiobookshelf (ABS) and Plex books: in the same merged library, with the same player, progress and offline rules. Jellyfin is a common self-hosted media server; people who keep films and music there often keep some audiobooks there too, and Jellyfin 12.0 (2026-09-07) made books a first-class library type (chapters, authors and narrators for audiobooks). Adding it the same way Plex was added keeps the app's promise: one place for all your audiobooks, whichever server holds them.

His decisions (2026-11-07):
- Stage J1: browse, stream, and read the resume position.
- Stage J2: send progress back to Jellyfin (including Mark as finished), merged entries with ABS/Plex copies (Play from / Download from choices) and progress mirroring.
- Stage J3 (later): downloads, through the existing `DownloadSource` port. Only the slot is designed here.
- Sign-in with username and password first; Quick Connect later (since the approval: in J3, 5.1). Tokens never logged; the token lives in the `CredentialStore` like the others.
- Robert's server has no audiobooks yet, so everything is built against recorded-shape fixtures (synthetic text and ids; the repo is public) and checked on his server at the end.

His answers (2026-11-08):
- Every book on Jellyfin will be **one `.m4b` file** (he is converting his library). No folder-grouping feature.
- **One address** per Jellyfin server. No home / outside pair, no connection chooser.
- **Badge rule unchanged**: source badges show only when servers of two or more kinds are added; Jellyfin gets its own colour and label under that rule.
- **The same books will be on Jellyfin, Audiobookshelf and Plex.** From J2, a book is one entry with up to three copies, and progress mirrors across all three (section 9.1).
- Still open: his Jellyfin version (section 12). Not blocking.

**Approval (2026-11-08).** Robert approved this design with **one change: Quick Connect sign-in is added to stage J3, together with downloads** (designed in 5.1). Everything else stands as written; he said "apart from that it's good to start", so every builder's **call** in section 12 is an approved default (he can still change any of them later). His Jellyfin version stays the only open item and does not block J1.

## 2. Goals, non-goals, anti-goals
**Goals**
- A Jellyfin server can be added in Settings, Servers (address, username, password), shows its sync status like the others, and can be signed in again.
- Its audiobooks show in Home, Library, Search, the group pages, Android Auto and the widgets, with covers, authors, narrators (where the server knows them), descriptions and chapters.
- They stream on Android (Media3) and iOS (AVPlayer) without the token in any address, and open at the server's resume position when it is newer than the phone's.
- J2: listening is reported back (live session plus a direct position write), Mark as finished / not finished works, the offline queue delivers, and a book on Jellyfin, ABS and Plex is one entry with three copies and mirrored progress (section 9.1).

**Non-goals**
- Not now (later stages may add them): downloads and Quick Connect (both J3), Jellyfin collections (BoxSets) and playlists, transcoding.
- Not planned (Robert's answers, 2026-11-08): grouping a folder of several audio files into one book (multi-file books are unsupported and show as separate items, 4.2); several addresses per server or a connection chooser.
- Not offered: bookmarks on Jellyfin (it has none; Jellyfin copies keep bookmarks on the phone only), a server listening-history import (Jellyfin has none built in), music libraries as a book source (4.1), ebooks.

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
- Consequence for the app: **only one-file books are supported** (Robert's library will be one `.m4b` per book). Each `AudioBook` item is one book. A multi-file book is **unsupported**: whatever items Jellyfin makes of it pass through as they are, so it shows as several separate "books" (one per file, or none if Jellyfin hides them), the same as in Jellyfin's own apps. The app never groups them (a non-goal).
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
| Playback info (fallback only, not used: section 6) | `POST /Items/{Id}/PlaybackInfo` (device profile in the body) | old |
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
- **DeviceId must be unique per install and per server row.** A sign-in logs out every earlier token of the same user with the same DeviceId [S20], and an empty DeviceId fails [S20]. The app's ABS device id is a constant per platform, which would make two phones sign each other out. Rule: `DeviceId = "chapterplayer-" + <per-install id> + "-" + <server row id>`, computed, not stored separately (the per-install id is the one `PlexClientIdentifier` already keeps in DataStore / user defaults. **Call:** reuse it through a small `InstallId` interface that the Plex identifier implements, so Plex's stored value does not change).
- **Token**: `AccessToken` from the sign-in answer (also `User.Id`, `ServerId`). It goes into the `CredentialStore` under the server row id, the Room `token` column stays empty, `ServerConfig.toString()` never shows it. Jellyfin access tokens do not expire on their own; they end when the device is removed in the dashboard, the user signs in again with the same DeviceId, or the server signs the user out **(the last two verified in source, password-change behaviour unverified)**. There is no refresh token.
- **401**: like Plex. A 401 on any authorized call blanks the stored token (`markSignedOut`) and is `SignInRequiredException`, so Settings shows "Sign in again" and the queue stops. 403 is "The server does not let this account play this book". Sign in again asks for the password again (never stored).
- The sign-in answer and every request/response body with `AccessToken` must never be logged: the HTTP logger's redaction adds the `Authorization: MediaBrowser ...` header (already redacted as `Authorization`) and the body logger must not print sign-in bodies (check in step 1; the Diagnostics `redact()` already removes UUIDs and long opaque strings).
- **Identity check**: `/System/Info/Public` gives the server `Id`; **Call:** the app keeps it in `ServerStatusStore` (one more field in the server's JSON record, like Plex's probe data; no Room change). When Sign in again or an address edit then finds a different id, it refuses with "This address now belongs to another Jellyfin server. Remove this server and add it again." That way no token or progress is mixed between two servers. A sync never compares ids, so there is no extra call per sync.
- Quick Connect (J3, designed in 5.1): `GET /QuickConnect/Enabled`, `POST /QuickConnect/Initiate` (12.0 removed the GET alias [R1]), poll `GET /QuickConnect/Connect?secret=`, then `POST /Users/AuthenticateWithQuickConnect`. Enabled by default [D2].

### 5.1 Quick Connect (J3, added at approval)
Quick Connect lets a person sign the phone in without typing the password: the phone shows a short code, and the person approves it on another device where they are already signed in to Jellyfin (Jellyfin web or an app: the user menu, **Settings > Quick Connect**, enter the code) [D2]. It is offered next to username and password, in Add Server and in Sign in again. Calls (all in the OpenAPI spec; shapes from the docs and the v12.4 controller names, **(unverified)** until recorded on Robert's server):

| Step | Call | Answer |
|---|---|---|
| Is it on? | `GET /QuickConnect/Enabled` (no token) | a JSON boolean. Quick Connect is on by default; an administrator can turn it off in the Dashboard [D2] (where exactly the switch sits in 12.x is **(unverified)**) |
| Start | `POST /QuickConnect/Initiate` with the `Authorization: MediaBrowser Client=..., Device=..., DeviceId=..., Version=...` header and no `Token` (12.0 removed the GET alias [R1]) | `QuickConnectResult`: `Secret`, `Code`, `Authenticated` = false, `DeviceId`, `DeviceName`, `AppName`, `AppVersion`, `DateAdded` |
| Wait | `GET /QuickConnect/Connect?secret={Secret}` every **5 s** | the same result; `Authenticated` becomes true once the person approved the code |
| Sign in | `POST /Users/AuthenticateWithQuickConnect` body `{"Secret": "..."}` with the same header | the same `AuthenticationResult` as `AuthenticateByName` (`AccessToken`, `User.Id`, `ServerId`) |

- **After that it is the password sign-in.** The answer goes through the same path: version gate, identity check (the server `Id`), token into the `CredentialStore`, the books views check-list. The password fields are not needed.
- **DeviceId**: the rule of section 5 (`chapterplayer-<install id>-<server row id>`). Initiate and the final sign-in must send the same `DeviceId` (the server ties the request to the device, **(unverified)**), so the add flow creates the server row id before Initiate (the password flow does the same, see the plan).
- **Shown on the phone**: the code in large digits (6 digits at v12.4, **(unverified)**; the app shows whatever `Code` holds), "On a device where you are signed in to Jellyfin, open Settings > Quick Connect and enter this code", a spinner "Waiting for approval", and **Cancel**. The code is not put in a notification or anywhere else.
- **Availability**: setup reads `/QuickConnect/Enabled` after `/System/Info/Public`. False, or a failed read: the Quick Connect button is hidden and a line says "Quick Connect is off on this server. Sign in with your password, or ask the server's administrator to turn it on." Initiate answering 401 (turned off since the check) gives the same line.
- **Timing**: polling stops after **5 minutes** with "The code expired. Try again." (the server forgets unapproved requests after a while; at v12.4 about 10 minutes, **(unverified)**, so the app gives up first). A 401 or 404 from `/QuickConnect/Connect` (secret unknown or expired) gives the same text at once. A network error while waiting is retried at the next 5 s tick until the 5 minutes are up ("Waiting for the server..."). Only one wait runs at a time.
- **Cancel**: Cancel, Back, leaving the screen or the view model going away stops the polling and forgets the secret. No server call is made (there is no cancel endpoint; the request expires on its own). Nothing runs in the background.
- **Errors** in words: not approved in time (above); `AuthenticateWithQuickConnect` refused (400/401) "Quick Connect was not approved. Try again."; unreachable "Couldn't reach the server. Check the address and your network."; the version gate and "no Books library" as for the password sign-in.
- **The secret is a credential**: kept in memory only (never stored, never in `toString`, never in a log, an exception message or the Diagnostics report). It travels in the `Connect` query string, so the debug HTTP logger, which prints request addresses, must mask `secret=` values (one filter in the logging sink, tested). The code is useless without the secret but is not logged either.
- **Pure rules** (`domain/jellyfin/QuickConnect.kt`, commonTest): the next step from (elapsed time, last answer or error) = keep waiting / sign in / expired / not approved / off, and the wording.

## 6. Playback
- **No PlaybackInfo call (call).** `startPlayback` reads the item fresh (`GET /Items/{Id}?Fields=Chapters,People,MediaSources`, like Plex's `fetchContent`) and uses `MediaSources[0]`: its `Id`, `Container`, `Size` and `RunTimeTicks`. A one-file item has one source. `POST /Items/{Id}/PlaybackInfo` is only needed for transcoding decisions and device profiles, and it carries fields not verified here, so the app leaves it out. The app makes its own `PlaySessionId` (a random 32-hex value per load) and sends it in the session reports. If the first real recording shows that `MediaSources` is missing from `/Items/{Id}`, PlaybackInfo with a minimal profile is the fallback.
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
One address, typed by the user (http or https; local or remote; with an optional path). No plex.tv-style list of connections exists for Jellyfin, so **no connection chooser**: the Plex one stays Plex-only. `/System/Info/Public` (no auth) checks the address at setup and in Test connection (`ProductName`, `Version`, `Id`). Redirects: the shared Ktor client already drops credentials across origins (`OriginSafeRedirects`); the probe client does not follow redirects. A plain http address on a foreign network is the user's choice (as for ABS). One address per server is final (Robert, 2026-11-08). Away from home the server simply does not answer, and the app behaves as with an unreachable ABS server: sync fails with a reason, and downloaded copies or other servers' copies play.

## 9. Mapping onto the app
- **`ServerSource.JELLYFIN`** (new enum value). **Room impact: none.** `servers.source` and `books.source` store the enum name as a string (`ServerSource.valueOf`), `listening_log.source` stores the name too; no column, table or migration changes. Note: a build older than this one would crash on reading `JELLYFIN` (only reachable by a downgrade or a backup restore into an old build; acceptable, mentioned for review). Every `when (source)` and `ServerSource.PLEX` check (29 files today) gets a Jellyfin arm; the compiler finds the exhaustive ones, a grep finds the rest.
- **Book id**: `jf_<Id>` (the 32-hex id as sent). Helpers `jellyfinItemId(bookId)` / `isJellyfinBook` in `domain/jellyfin/JellyfinBooks.kt`.
- **Classes** (all `commonMain`): `data/remote/jellyfin/JellyfinApiService` (one method per call in 4.6, header built by `jellyfinAuthHeader(...)`), `dto/JellyfinDtos.kt`, `JellyfinPaging.kt` (pages of 200, follows `TotalRecordCount`); `data/source/JellyfinLibrarySource : LibrarySource` (`fetchSnapshot`, `fetchLibraries` = books views, `fetchCollections` = empty in J1, `fetchBookDetails`, `startPlayback`, J2: `syncPlayback`, `closePlayback`, `pushProgress`, `fetchProgress`, `fetchProgressReading`, `fetchAllProgress`; bookmarks and listening stats keep the empty defaults); `data/auth/JellyfinSignIn` (port `JellyfinSignInPort`, like `AbsSignInPort`); `domain/jellyfin/*` for the pure rules. `SourceSnapshot.failedLibraries` is used like Plex (one view failing keeps its cached books).
- **`ServerConfig`**: no new field. `libraryId` / `excludedLibraryIds` hold view ids; the user id is not needed (10.9+ routes take it from the token; kept from the sign-in answer only if a call turns out to need it).
- **DI**: `commonSourceModule` (or wherever the Plex source is bound) adds the API service, the source (bound into the list of `LibrarySource`s), the sign-in port and `markSignedOut`; J3 adds `JellyfinDownloadSource` to the `DownloadSource` list. `KoinModulesTest` covers them.
- **Server setup screen**: the source chips come from `ServerSource.entries`, so a Jellyfin chip appears; it shows Name, Address, Username, Password and Sign in (the ABS sign-in section's layout and keyboard padding), then the books libraries check-list. Errors in words: "This is not a Jellyfin server", "This Jellyfin version is too old (10.10 or newer is needed)", "Wrong username or password", "The server has no Books library. Create one with the type Books." Edit Server: address, name, Sign in again.
- **Settings text and badges**: `ServerSource` labels "Jellyfin"; `serverDisplayNames` unchanged; the source badge **rule stays as it is in meaning** (Robert, 2026-11-08): badges show only when servers of two or more kinds are added. The code today checks for ABS and Plex both, so `showSourceBadges` is rewritten as "two or more distinct kinds", which keeps the ABS + Plex case and adds ABS + Jellyfin and Plex + Jellyfin. **Call:** the Jellyfin badge reads "Jellyfin" (short form "JF" where the ABS / Plex badges use a short form), in a new palette colour (`JellyfinPurple`, a dark and a light value) that `PaletteContrastTest` checks in both themes. Source choices: `type:JELLYFIN` works as stored (`SourceChoice.OfType` reads `ServerSource.entries`); `SourceChoiceText` shows a Jellyfin line only when such a server exists. Servers page: sync status and Test connection as for ABS; Diagnostics: the UserData fields of the copy.
- **Merging and mirroring**: see 9.1. Jellyfin exposes no ISBN/ASIN (4.3), so there is no id-based merge.
- **ABS-only helpers stay ABS-only** (checked): `BookmarkSyncer` syncs only ABS servers (so a Jellyfin copy's bookmarks stay on the phone and are never deleted by an empty server list), `ListeningStatsImporter` imports only ABS history, and `ServerProgressRefresher` refreshes only ABS servers. **Call:** in J2 step 5, `ServerProgressRefresher` also refreshes Jellyfin servers (its `fetchAllProgress` is one list read per library, like ABS's). Plex stays out, because it would need one call per book.
- **Lengths**: `RunTimeTicks` comes with each listed `AudioBook`, so no `PlexLengthFiller`-like pass is needed **(verify on the first recording; if lists lack it, the details read fills it like Plex details do)**.
- **Covers**: `coverUrl` = the image address of 4.4; `CoverRules`/`CoverBytesLoader` treat Jellyfin like ABS (origin check), no token interceptor.
- **Android Auto, widgets, Up next, favourites, stats**: generic over `PlaybackStarter` and book ids; the stats source split gets a "Jellyfin" name (`StatsText`), `ListeningRecorder` records the source name.
- **Downloads slot (J3, not detailed)**: `JellyfinDownloadSource : DownloadSource`: `plan` = one `PlannedFile` per book (ino column = item id, size from `MediaSources[0].Size`, extension from the container), `authorized` adds the header to `GET /Items/{Id}/Download` (403 when the user lacks the Download permission: a plain message) or the static stream, 401 = sign in again, `fileLengths` = `RunTimeTicks`. The offline play path (`playableFromPhone`) matches by `PlaybackTrack.fileId` as for Plex.

### 9.1 One book, three copies (ABS, Plex and Jellyfin)
Robert will have the same books on all three servers: the ABS copy is usually the original folder of MP3s, the Plex copy is an album of the same tracks, and the Jellyfin copy is one `.m4b` converted from them. Everything below reuses today's rules (`domain/browse/MergedSources.kt`, `domain/playback/MirrorProgress.kt`, `MarkFinished.kt`); only the order rules and a few Jellyfin specifics are new.

**When merging starts.** **Call:** `mergeDuplicates` does not care about the server kind, so a Jellyfin book joins its ABS / Plex entry from J1 on. J1 reads the Jellyfin copy's progress, and any mirror to it is local only (its `pushProgress` is a no-op until J2). J2 is where the three-copy cases are tested and tuned. If Robert would rather keep Jellyfin books apart until J2, one filter line in `mergeDuplicates` does it.

**Matching.**
- The candidates are copies with the same normalised title and first author, on different servers. Two copies of one server never join.
- A pair matches when the lengths are within **5 min or 2% of the longer one** (`lengthsAgree`, either is enough). A Plex copy whose length is still unknown (0, until `PlexLengthFiller` has run) matches only when every author agrees.
- Pairs are joined closest length first, through a union-find. Three copies make one entry when two pairs match. The third pair is not checked, so A~B and B~C join A and C even when A and C alone would not match (the known "chained matches" note; at most 10 min or 4% apart). **Call:** keep it, because the corrections handle the rare bad case.
- A "Not the same book" split between two copies is never crossed by a chain. With A~B~C and A/C split, A joins B and C stays alone, even though B~C matches. That is acceptable; "Merge with…" can join C to B afterwards, and a forced merge never crosses a split.
- Titles: Jellyfin's `Name` comes from the file or folder name (with a year such as "(2019)" parsed off) or the tags. "Book Title (Unabridged)" against "Book Title" stays two entries. The "Merge with…" correction (`book_merges`) fixes it once. Authors: Jellyfin's `Author` is the album artist, ABS has its own author field, and Plex uses the album artist (`parentTitle`). The first author is compared after normalisation.

**Lengths after the `.m4b` conversion** (estimates, **unverified** until his converted books are synced):
- The ABS copy's length is the sum of each MP3's probed length. The Plex copy's length is the sum of its tracks. The `.m4b` is re-encoded to AAC, so each joined file gains or loses a little: encoder delay and padding are a few tens of ms per file (MP3 frames are about 26 ms, AAC priming about 48 ms at 44.1 kHz). A 50-file book typically ends up a few seconds apart.
- Larger gaps come from VBR MP3s whose length was estimated without a header, or from a converter that trims silence, adds gaps or drops an intro or outro. A 10 h book has a 12 min allowance (2%), and every book has at least 5 min, so normal conversions still merge.
- A book outside the allowance shows as two entries, and "Merge with…" joins them.
- **Call:** the 2% / 5 min rule stays. Tests use a 50-file book with the `.m4b` 4 s shorter, and one with a 6 min trimmed intro, which still merges because 6 min is under 2% of a 10 h book.

**Mirroring by fraction.**
- A position `p` on a copy of length `La` maps to `p × Lb / La`, in whole seconds, never past the end. The error against "the same moment in the audio" is at most the length difference: a few seconds for a normal conversion, which is about the size of the app's rewind on resume after a pause.
- A difference concentrated at one place (a trimmed intro) is spread across the book, so the worst case is the trimmed amount near that place.
- A round trip A to B to A comes back within rounding. It stays inside the 15 s echo window (`MIRROR_ECHO_MS`), so there is no drift loop.
- **Call:** no chapter-based or audio-aligned mapping.

**Chapters differ between copies.**
- ABS has its own chapter list, Plex has chapters per track (often one per file), and Jellyfin has the `.m4b`'s embedded chapters. A conversion with Audiobookshelf's M4B encoder probably carries ABS's chapters (**unverified**).
- The player shows the loaded copy's chapters. Chapter skips and "sleep at end of chapter" follow that copy. Opening another copy shows its own list at the mapped place.
- Bookmarks and history stay per copy (Jellyfin copies keep theirs on the phone only).
- No chapter mapping across copies.

**Fan-out while listening.** Copy A is the loaded one, and B and C are its peers (`ProgressMirrorPeers`). Every local save writes B's and C's local rows in the same transaction, flagged `mirrored`. `ProgressMirrorPusher` queues the mapped position for each of B and C on their own servers: at most once per 60 s per copy while playing, once on a real pause, stop or book change, never for a server whose last sync failed. Per target kind:

| Target copy | What the queued mirror sends | Effect on that server |
|---|---|---|
| ABS | `PATCH /api/me/progress/{id}` (today) | exact place |
| Plex | the timeline / scrobble plan (today) | Plex keeps no offset for these tracks (finding 2026-11-06); only finished / scrobbled tracks stick |
| Jellyfin (J2) | `POST /UserItems/{Id}/UserData` `{PlaybackPositionTicks, Played, LastPlayedDate}` | exact place, no 5-min rules; finished: `POST /UserPlayedItems/{Id}` |

- With Jellyfin loaded, the Jellyfin copy reports through its live session plus the band writes (section 7), and ABS and Plex get mirrors as today.
- **Call:** a mirror's `LastPlayedDate` is the source row's `lastPlayedAt`, not "now". The next Jellyfin sync then reads back an echo no newer than the source, and `mergeServerProgress` keeps the mirrored row.
- The flusher's drop rules (split, setting off, newer own listening, source moved, source server holds news) apply per target unchanged.

**Opening a book.** `PlaybackStarter` weighs every copy's own row and, online, every other copy's server progress, all in parallel with the same 4 s wait (now up to two other servers), and maps the newest across copies (`newestAcrossCopies`). A Jellyfin reading of 0 that is not played counts as no progress (section 6).

**Mark as finished / not finished.** This works as today: the loaded or played-last copy is marked locally first, then the other copies locally, then all three servers at once, each waited for at most 8 s before it is queued. `PARTIAL` / `PARTIAL_QUEUED` are reported when one of them failed. Jellyfin uses `/UserPlayedItems` (section 7).

**Play from / Download from.**
- Choices: Ask, Audiobookshelf, Plex, Jellyfin. A type line shows only when a server of that type exists. A line per server sits under a type with two or more servers.
- The source sheets list up to three rows with the copy played last marked.
- Member order (primary copy) is unchanged except for the final tie-break by kind: ABS, then Jellyfin, then Plex (**call**; today it is ABS, then the rest). Metadata order (series, narrator, description) is ABS, Jellyfin, Plex (**call**: Jellyfin 12 has narrators and chapters, Plex has neither).
- **Call:** until J3 the Jellyfin copy cannot download. There is no `DownloadSource` for it (`forServer` gives null), so the Download from sheet, the Settings card and bulk Download leave Jellyfin out: no "Jellyfin" line under Download from and no Jellyfin rows in the sheet. Play from includes Jellyfin from J1.
- A downloaded ABS or Plex copy still always plays first.

**Removing a server** deletes its rows. The entry falls back to the remaining copies, and choices naming it read as Ask (today's rules).

## 10. Stage and step plan
Each step: its own plan in `docs/superpowers/plans/`, branch `stage-j/<name>`, fixtures in `fixtures/jellyfin`, host tests, debug and release builds, iOS klib compile, review, one fix wave, then `PROJECT_STATUS.md`, `CLAUDE.md`, `ROADMAP.md`.

**Stage J1: browse, stream, read the resume position**
1. **Server kind and sign-in.** `ServerSource.JELLYFIN` everywhere (labels, badges, `when` arms), the per-install DeviceId, `jellyfinAuthHeader`, `JellyfinApiService` (`/System/Info/Public`, `AuthenticateByName`, `/UserViews`), `JellyfinSignIn`, the setup screen section, Sign in again, 401 handling, HTTP-log redaction check. Fixtures: `system-info-public.json` (12.x and a 10.10 one), `authenticate-by-name.json` (synthetic token), `user-views.json` (a books, a music and a movies view). Tests: header text and escaping, version gate, view filter, wrong password (401) wording, token never in `toString`/logs, Koin.
2. **Library sync.** Paged `/Items` for `AudioBook` in each books view, `jellyfinItemToBook` (author/narrator fallbacks, ticks, cover address, `DateCreated`), `fetchSnapshot` with per-view failures, details with chapters and overview. Fixtures: `items-audiobooks-page-1.json` / `page-2.json` (12.x shape: People with Author and Narrator), `items-audiobooks-10-10.json` (no People roles, `AlbumArtist` only), `item-detail-chapters.json`, `item-detail-no-chapters.json`, `items-folder-of-parts.json` (a multi-file folder as Jellyfin shows it: one-file `AudioBook` items under a folder). Tests: mapping, paging to `TotalRecordCount`, chapters end times, a view failing keeps its books, the folder-of-parts items pass through as separate books (no grouping), a merged entry with an ABS and a Plex copy (it already merges by title and author).
3. **Streaming and resume read.** `startPlayback` (fresh item read with `MediaSources`, static stream address, one track, format gate per platform), Android data source branch, iOS `StreamAuthorizer` case, `fetchProgress`, `fetchAllProgress` from the list, "0 and not played = no progress". `syncPlayback` / `pushProgress` stay no-ops (J1 writes nothing to the server: `ProgressSyncer` sees a success and queues nothing; the position is kept on the phone and mirrored locally). Fixtures: `item-detail-media-sources.json` (m4b), `item-detail-ogg.json` (for the iOS format gate), `user-data-in-progress.json`, `user-data-played.json`, `user-data-zero.json`. Tests: address has no token, header added only for the server's origin, redirect rules, formats, start position rules, starter newest-wins with Jellyfin readings.
4. **Check on Robert's server** (after he follows `docs/JELLYFIN_SETUP.md`): sign in, sync, covers, chapters, play from the start and from a Jellyfin web resume point, seek, Android Auto list, iPhone stays compile-only. Record the real shapes of the step 1 to 3 calls with the Diagnostics page or a script, replace every text, id and path with synthetic ones, and update the fixtures where the real shape differs.

**Stage J2: progress back, merged copies, mirroring**
5. **Reporting.** Live session calls, the threshold bands with `/System/Configuration`, UserData writes, `pushProgress` for the queue, Mark as finished / not finished (`/UserPlayedItems`), 401 / 403 handling in `ProgressSyncer` and the flusher, `fetchProgressReading` for Diagnostics. Fixtures: `system-configuration.json`, `user-played-items.json`. Tests: the call plan table, band edges (4:59 / 5:00, last 5 min), finished pin, no stop without a position, queue delivers only UserData.
6. **Three copies: merging and mirroring** (9.1). Member and metadata order with three kinds; Play from / Download from lines and sheets (Jellyfin out of Download from until J3); mirror pushes to Jellyfin copies with the source's `LastPlayedDate`; the flusher's drop rules with a Jellyfin source or target; `ServerProgressRefresher` for Jellyfin; Diagnostics merge facts for three copies. Tests (the existing merge and mirror suites, extended):
   - Merging: an ABS (50 MP3s) + Plex (50 tracks) + Jellyfin (`.m4b` 4 s shorter) book is one entry. A 6 min trimmed intro on a 10 h book still merges; the same on a 3 h book does not (2% = 3.6 min). A Plex copy with unknown length joins by authors. A~B~C chaining and an A/C split. A title with "(Unabridged)" stays apart until a forced merge.
   - Mirroring: listening on each copy in turn fans out to the other two (local rows in one transaction, one queued row per other server, never the loaded copy, nothing to a failed server). The mapped positions and the 15 s echo window hold across three lengths.
   - Marks and opening: Mark as finished on three copies with one server failing gives `PARTIAL_QUEUED`. `PlaybackStarter` picks the newest of three readings.
7. **Check on Robert's server**: listen 10 min on the phone, Jellyfin web shows the place; listen 2 min (below the band), the server keeps the real place; stop in the last 5 min, the book is not finished unless it ended; Mark as finished both ways; a book on ABS, Plex and Jellyfin is one tile with "3 sources" and the Play from sheet lists three copies; listening on the Jellyfin copy moves the ABS place (ABS web) and listening on ABS moves the Jellyfin place (Jellyfin web) to within a few seconds of the same moment in the audio; chapters differ per copy as expected; offline listening is delivered later to both servers.

**Stage J3 (later): downloads and Quick Connect.** Downloads through `JellyfinDownloadSource` (9, last bullet; its own design note when it starts). Quick Connect sign-in as designed in 5.1 (added by Robert at approval, 2026-11-08). The J1/J2 plan carries a short J3 outline; J3 gets its own plan when it starts.

## 11. Risks
- **Multi-file books** (4.2) are unsupported: Jellyfin shows them as one "book" per file (or hides them), and the app shows what Jellyfin shows. Robert is converting his library to one `.m4b` per book. A book he missed shows up as several items, which tells him to convert it.
- **Conversion changes lengths and chapters** (9.1): normal conversions stay far inside the merge allowance. A trimmed intro or added gaps shift mirrored places by up to the trimmed amount. A book outside the allowance needs one "Merge with…".
- **Version spread**: 10.10/10.11 servers have no audiobook chapters or narrators; 12.0 changed `GetItems` results [R1]. Mitigation: version gate, fallbacks, fixtures for both.
- **Threshold rules** (4.5) can mark a book finished from a pause near the end. Mitigation: the band rule in 7; a wrong guess of the server's values (unreadable config) leaves at most the default 5/5 behaviour.
- **Anonymous stream and image endpoints** (6, 4.4): good for covers, but if a future server or a proxy requires auth for images, covers would need the header (the cover loader can add it like Plex's interceptor). Low risk, easy fix.
- **DeviceId collisions** sign other installs out (5). Mitigation: per-install, per-row id from step 1.
- **Server-side session list**: every start opens a server session; a crash leaves it until the server times it out. Harmless.
- **Public repo**: fixtures must be synthetic; Jellyfin ids are GUIDs, so new random-looking synthetic ids are used, never copied ones.

## 12. Open questions
**Approved 2026-11-08.** Robert approved the design with Quick Connect added to J3 and said "apart from that it's good to start": the calls below are the approved defaults. The one open question does not block J1.

**Robert must answer (non-blocking)**
1. Which Jellyfin version does your server run (Dashboard, top of the page)? This does not block building: the app is built for 10.10.7 and newer, with chapters and narrators from 12.0. Below 10.10 the app refuses; on 10.10 / 10.11 books play with one chapter and no narrator. If the server is older than 12.0, updating it is the fix.

**Answered by Robert (2026-11-08)**: one `.m4b` per book and no folder grouping; one address; badge rule unchanged; the same books on ABS, Plex and Jellyfin (section 1).

**Builder check (2026-11-08).** The spec was re-read as an engineer new to the repo would read it. Every question that came up is settled below as a call (marked **call** where it appears in the text). Robert can overrule any of them at approval.
- Minimum server 10.10.7, full features 12.0; the version gate reads `/System/Info/Public` (4.6). **call**
- `Fields=`: the list asks for `People,DateCreated,Genres,Overview,ProviderIds` with `EnableUserData=true`, `EnableImageTypes=Primary`, pages of 200; details and `startPlayback` ask for `Chapters,People,Overview,Genres,Studios,MediaSources`. If the list lacks `RunTimeTicks`, the list also asks for `MediaSources` (heavier) rather than adding a filler pass. **call**
- No PlaybackInfo call; own `PlaySessionId`; `PlayMethod=DirectPlay`; mime types as in section 6 (6). **call**
- `DeviceId` from the existing per-install id behind an `InstallId` interface; `Device` = "Android" / "iPhone"; `Version` = the app version from `AppInfo` (5). **call**
- The server `Id` is kept, and a different id on Sign in again or an address edit is refused (5). **call**
- Setup flow: address, then `/System/Info/Public`, then username / password, then the books views. All views are on by default and can be turned off in the Libraries sheet (`excludedLibraryIds`). With no books view it shows the "no Books library" error. **call**
- Servers page: Sync now and Test connection (`/System/Info/Public`: "Jellyfin 12.4 answered" or the reason; no token sent). **call**
- `/System/Configuration` is read once per successful sync and kept in memory per server; unreadable means 5 / 5 (7). **call**
- One report at a time per live session (a lock, like Plex's `LiveSession`): first the session report, then the band's UserData write. **call**
- `ServerSource.JELLYFIN` goes last in the enum, so the server-kind chips read Audiobookshelf, Plex, Jellyfin. **call**
- Badge: "Jellyfin" / "JF", a new `JellyfinPurple` colour checked in both themes (9). **call**
- Merging starts in J1 because the rule is generic; mirrors to Jellyfin are local only until J2 (9.1). **call**
- Kind order for ties ABS, Jellyfin, Plex; metadata order the same (9.1). **call**
- Jellyfin left out of Download from until J3 (9.1). **call**
- A mirror's `LastPlayedDate` is the source row's `lastPlayedAt` (9.1). **call**
- `ServerProgressRefresher` covers Jellyfin from J2; bookmarks and history import stay ABS-only (9). **call**
- Diagnostics: the existing `redact()` already replaces 32-hex ids (`LONG_HEX`), so Jellyfin item ids, tokens and server ids are covered; only the user's own book ids are kept, as today. One test with a Jellyfin report. **call**
- Error and status texts as written in sections 6 and 9; the step plans may polish the wording. **call**

## 13. Unverified until Robert's server has books
- The real JSON of every call (fixtures are built from the v12.4 source and DTO definitions, not recorded).
- Which fields the list returns without `Fields=`; whether `RunTimeTicks` is in list results.
- Chapters on a real `.m4b` (12.0+), and on MP3 with ID3 CHAP frames.
- Author and narrator roles from real tags (album artist / composer), and whether `SeriesName` is ever filled for `AudioBook` items.
- That `static=true` Range requests seek correctly in Media3 and AVPlayer.
- That the stream works with only the header (and also without it, as the source suggests).
- That a non-admin user can read `/System/Configuration`.
- The threshold behaviour as seen in Jellyfin web after the app's reports and UserData writes.
- How a multi-file folder appears through `/Items` on Robert's version (separate items or hidden).
- How far the converted `.m4b` lengths really are from the ABS and Plex copies, and whether the converter kept ABS's chapters (9.1).
- That `MediaSources` comes with `GET /Items/{Id}` (else PlaybackInfo is needed).
- The status code of a wrong password, the token's character set, cover behaviour for embedded art, password change and token lifetime.
- Quick Connect (J3): the exact answers of `/QuickConnect/Enabled`, `/Initiate`, `/Connect` and `/Users/AuthenticateWithQuickConnect`, the code length, how long the server keeps an unapproved request, whether the DeviceId must match between Initiate and the sign-in, and where the switch sits in the 12.x Dashboard.

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
