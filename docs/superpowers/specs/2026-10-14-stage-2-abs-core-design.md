# Stage 2: Audiobookshelf core experience and Android Auto (design)

Status: draft for review by the project owner, 2026-10-14.
Scope source: `docs/ROADMAP.md` Stage 2, plus Android Auto moved in from Stage 3 at the owner's request.
Decisions below come from the design quiz with the project owner. Anything I assumed and did not ask about is listed under "Assumptions to confirm".

## Goal

You can listen to an Audiobookshelf book every day, on the phone and in the car, with progress staying in sync with the Audiobookshelf web app.

Done when:
- Sign in with a username and password, choose libraries, and play a book with chapters, speed, sleep timer and lock-screen controls.
- Progress syncs both ways, including after going offline and coming back.
- Android Auto lists your books and plays them (tested on the Desktop Head Unit and in a real car).
- The app works in dark, light and follow-system themes.

## What you will see

### Look
- Three theme choices in Settings: Dark, Light and Follow system. New installs use Follow system.
- ChapterPlayer keeps its identity in both themes: amber-gold accent, Playfair Display for titles and DM Sans for text. The light theme gets its own palette, with a darker amber so the accent stays readable.

### Opening the app
- The app opens on Home. A large Resume card for your last book sits at the top: cover, chapter, progress, time left and a Resume button. Below it are Continue Series and Recently Added.
- Nothing plays until you press Play. Playback pauses when headphones or Bluetooth disconnect, and resuming rewinds a few seconds.

### Tabs
- Four tabs: Home, Library, Downloads, Search.
- Downloads shows a "coming in a later update" placeholder until Stage 3.
- A mini player sits above the tab bar whenever something is playing. Tapping it opens the full player.

### Library
- Five views along the top: Library, Authors, Narrators, Series, Collections, plus sort and filter.
- Three layouts for the Library view: large covers two across, small covers three across, and a list. A switch in the toolbar changes it and the app remembers the choice. New installs start on large covers.
- An in-progress book shows a thin amber bar. A finished book shows a check mark.
- All servers appear as one combined library. A "Libraries" control at the top opens a check-list of every library, grouped by server. Unticking a library hides it everywhere: Home, Library, Search and the car. A library added to a server later appears ticked.

### Adding a server
- You enter the server address, username and password. Pasting an API token stays available under "Advanced".
- After sign-in the app lists the server's libraries so you can untick the ones you do not want.

### Book details
- A compact header with cover, title, author, narrator, length and a Resume button.
- Tabs: About, Chapters and Series. The screen opens on Chapters. The chapter you are on is highlighted, and tapping a chapter jumps to it.

### Player
- A full-screen player with a large cover, the chapter title, two progress bars (whole book and current chapter) and the time left.
- Skip back 15 seconds and forward 30, with the numbers on the buttons.
- A bottom row of Speed, Sleep timer, Bookmarks and History.
- Tapping the chapter title opens the chapter list.

### Android Auto
- Three tabs along the top: Continue, Recently added and Library.
  - Continue lists your in-progress books, most recent first, so one tap resumes.
  - Recently added lists the newest books.
  - Library holds Series, Authors, Collections and All books. All books is grouped into letter ranges ("A to C", "D to F" and so on) so no list is long.
  - Narrators are not in the car.
- On the Now Playing screen, Previous and Next skip back 15 and forward 30 seconds, matching the phone.
- The Now Playing queue button lists the book's chapters. Tapping one jumps to it.
- Speed and the sleep timer are on the phone and available by voice ("Hey Google, set a sleep timer"). They are not on the car screen.
- Voice works for playing a book: "Hey Google, play The Salt Road".
- The book resumes when the car connects, rewound a few seconds. This is on by default. Settings has an Android Auto section with a switch to turn it off.
- Android Auto draws most of the screen itself, so the car does not show the amber theme or the app's fonts. We control the lists, the cover art, the buttons and voice.

## How it will be built

### One playback engine for phone and car
A Media3 `MediaLibraryService` hosts one ExoPlayer. The phone's player, the mini player, the lock screen and Android Auto all talk to that one service. This avoids a second player and keeps position and progress in one place. The Android implementation of `PlaybackController` (the interface added in Stage 1) wraps a Media3 `MediaController`.

### Playing a book
- The Audiobookshelf stream address stored today (`/api/items/{id}/play`) starts a playback session and is not an audio address. The app will start a session, take the audio track addresses from the response, and queue them in the player.
- Chapters are positions in the whole book. A book with several audio files plays as one continuous timeline, and the player works out the file and offset from the book position. Single-file `.m4b` books are the simple case.
- The library list does not include chapters. The app loads them for one book from `/api/items/{id}` when its details screen or player opens, and caches them with the book.

### Progress
- The `progress` table already exists and is never written. Stage 2 writes the position locally first, about every 10 seconds while playing and on every pause, seek and stop.
- Progress is sent to Audiobookshelf through the playback session (sync every ~10 seconds, close on stop). When the server cannot be reached, updates queue on the phone and retry with backoff.
- On opening a book, the app compares the local and server positions and uses the most recent one.

### Sign-in and credentials
- Sign-in calls the server's login endpoint and keeps the token it returns. The password is never stored.
- Tokens are stored encrypted with a key in the Android Keystore. Today the token is plain text in the Room database (a known item in the roadmap), so this moves out of Room before sign-in is built.
- Newer Audiobookshelf servers hand out short-lived access tokens with a refresh token. The owner's server (2.37.1) is one. The app will use the refresh token and fall back to asking for the password again if it fails. The exact login and refresh calls are checked against the owner's server during the sign-in work.

### Libraries and the combined view
- The app stores which libraries are turned off for each server (not which are on), so a library added later shows by default.
- This needs a database migration (version 1 to 2). The exported schema and CI check will cover it.
- Home, Library, Search and the car all read the same "visible libraries" list.

### Settings and preferences
- Theme, Library layout, Android Auto resume and the other small settings use DataStore preferences, which are already a dependency.

### Chapters and letter ranges in the car
- Chapters become the Now Playing queue items, with their start positions as the jump targets.
- The letter ranges for All books are worked out from the visible libraries so each range holds a short list.

### Testing
- Unit tests for the pure logic: visible-library filtering, chapter at a position, book position to file and offset, letter ranges, and the offline progress queue.
- Host tests with recorded and synthetic server responses for login, library lists, item details and playback sessions. The repository is public, so fixtures stay synthetic.
- Koin `verify()` covers new classes.
- Emulator and Desktop Head Unit checks for the screens, playback and the car.

## Build order

Each step is its own pull request, branches named `stage-2/<feature>`, and each builds, passes CI and gets an emulator pass before merging. Stage 2 is tagged `v0.2.0` when step 9 is done.

1. Secure token storage and the preferences store. Moves the token out of Room.
2. Theme: a light palette, the Dark, Light and Follow system choice, and the Settings entry for it.
3. Sign-in: username and password, API token under Advanced, and the library picker (with the database migration).
4. Navigation shell and Library: four tabs, the Downloads placeholder, five Library views, three layouts with the switch, and the combined view with the Libraries check-list.
5. Book details with chapters loaded per book, and the Series tab.
6. The playback service: Media3, the playback session, chapters, speed, sleep timer, skip 15 and 30, lock-screen controls, rewind on resume and pause on disconnect.
7. The full player, the mini player and the Home Resume card.
8. Progress sync, the offline queue and bookmarks.
9. Android Auto: the browse tree, chapters as the queue, voice search, resume on connect and its Settings switch.

Steps 1 to 8 give you the phone app. Step 9 adds the car.

## Roadmap changes

- Android Auto moves from Stage 3 into Stage 2. Stage 3 keeps widgets, downloads and offline mode, and its "done when" no longer mentions the car.
- The Stage 2 library-selection item changes to the combined view with the Libraries check-list.
- The Stage 2 navigation item changes to four tabs, with Collections inside Library.

## Out of scope for Stage 2

Downloads and offline mode (Stage 3), widgets (Stage 3), Plex (Stage 4), the stats page, CarPlay and iOS (Stage 5). Playing without a network connection is not part of Stage 2.

## Assumptions to confirm

These are my choices where we did not discuss the detail. Tell me which to change.

- Resuming rewinds 5 seconds.
- Speed runs from 0.5× to 3.0×, with quick presets (1.0, 1.25, 1.5, 2.0).
- Sleep timer presets: 15, 30, 45 and 60 minutes, and end of chapter.
- Search is a tab that searches title, author, narrator and series across the visible libraries, with results as you type.
- Continue Series shows the next book in any series you have started.
- History in the player lists recent positions in the current book, so you can jump back after an accidental seek.
- Bookmarks are saved at the current position with an optional name, listed in the player, and synced with Audiobookshelf.
- A book counts as finished when Audiobookshelf marks it finished.
