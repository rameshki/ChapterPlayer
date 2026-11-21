# UI, theme, fonts, settings, diagnostics

Compose Multiplatform UI, the theme and palette, fonts, the app icon, the Settings hub and the debug Diagnostics page.

Detail moved out of `CLAUDE.md` (review R8); keep it current when this area changes.

## UI structure and edge to edge

- UI is Compose Multiplatform (plugin 1.12.1).
- The theme, fonts (`shared/src/commonMain/composeResources/font`), components, screens, view models and navigation are all in `commonMain` (JetBrains navigation and lifecycle).
- The HTTP client set-up (`data/remote/HttpClientFactory.kt`, engine from `createHttpEngine`: OkHttp / Darwin), the Audiobookshelf and Plex APIs, DTOs, both library sources and `ServerRepositoryImpl` are in `commonMain` too (per-platform constants in `ClientPlatform.kt`: the `X-Plex-Platform` name and the ABS device id).
- `BookRepositoryImpl` and the cover image loader (`data/images/CoverImageLoader.kt`) are in `commonMain` too.
- `androidMain` keeps what is Android-specific: download scheduling (WorkManager, the foreground notification), the download location choice and `AndroidDownloadFiles`, playback, widgets, the Keystore cipher, connectivity, local files on disk, the clocks and day zone, the Android client identifier and Android's Koin modules.
- Images load with Coil 3.
- The app draws edge to edge, so the keyboard does not shrink the window: a screen with a text field pads by it (Add / Edit Server `imePadding`; Settings pages and Search `ui/components/keyboardOverlapPadding`, which pads only by the part of the keyboard that covers the screen, so the tab bar and navigation bar below are not counted twice);
  - `ModalBottomSheet` pads for the keyboard itself and dialogs are their own windows;
  - iOS sets `onFocusBehavior = OnFocusBehavior.DoNothing` in `MainViewController` so Compose does not also lift the view (never run on iOS)

## Theme and palettes

- The theme follows `AppSettings.themeMode` (Dark, Light, Follow system; new installs follow the system).
- `ui/theme/Palette.kt` holds the dark and light palettes:
  - dark = "Navy cards": the page stays warm black `0E0D0B`, cards, sheets and chips are navy (`surfaceElevated` `151A2B`, `surfaceSecondary` `1E2742`);
  - the tab bar keeps the old warm `navBar` `2B261A` (Robert preferred it), amber accent unchanged;
  - the widgets read the same palette, and their picker previews in `shared/src/androidMain/res` copy its dark colours by hand
  - the colour names in `Color.kt` (`AmberGold`, `TextSecondary` etc.) are composable properties that read the current palette, so read them inside composables only (a draw lambda must read the colour first).
- `PaletteContrastTest` guards text contrast in both themes.
- The launch window colour is in `values/themes.xml` (light) and `values-night/themes.xml` (dark, the warm black page colour)

## Fonts

- Fonts:
  - the user picks one in Settings, Appearance (`AppFont` in `data/settings/ThemeMode.kt`, stored as `AppSettings.appFont`, key `app_font`, unknown names read as Classic):
  - Classic (the default and original look: Playfair Display titles + DM Sans), Bookish (Literata + Source Sans 3), Inter, Manrope, Lexend (drawn at `LEXEND_SIZE_SCALE` 0.94 because it runs wide) and System (`FontFamily.Default`).
- Names, descriptions, order (`FONT_CHOICES`) and the size scale are pure code in `domain/settings/FontChoice.kt`;
  - `ui/theme/Typography.kt` builds the Material typography from `appFontFamilies(font)` (display family for display/headline styles, body family for the rest) and `ChapterPlayerTheme(themeMode, font)` applies it;
  - MainActivity reads the saved font before the first frame like the theme.
- `ui/settings/FontCard.kt` lists the choices, each name drawn in its own font.
- Static 400/500/700 TTFs from Google Fonts (OFL, listed in NOTICE) in `composeResources/font`.
- Widgets and notifications keep the phone's font (Glance and the system draw them).
- Checked on the emulator (picker, live switch, hub summary); not yet looked at on a phone in every font

## App icon

- App icon "Midnight and Gold" (gold headphones over an open book, on a navy gradient):
  - Android adaptive icon in `androidApp/src/main/res` (`drawable/ic_launcher_background.xml` gradient, `ic_launcher_foreground.xml` centred and scaled 0.85, the most that keeps the cup corners inside the 66 dp safe zone circle, `ic_launcher_monochrome.xml` for themed icons with a 1.5 gap between the pages; minSdk 26, so no PNG mipmaps);
  - iOS `iosApp/ChapterPlayer/Assets.xcassets/AppIcon.appiconset` (one 1024 px PNG, no alpha; `ASSETCATALOG_COMPILER_APPICON_NAME` in `project.yml`);
  - `docs/images/icon.png` (256 px) heads the README.
- The PNGs were rendered from the same shapes with a Pillow script that is not in the repo.
- The notification small icon is the system download icon and is unchanged

## Settings hub

- Settings: the gear opens a hub (`SettingsHubScreen`, route `settings`) of rows with live one-line summaries; each row opens its own page (routes `settings/servers`, `/appearance`, `/downloads`, `/car`, `/about`, one small screen file each in `ui/settings`, shared cards in `SettingsComponents.kt`).
- Every page uses the same `SettingsViewModel` class, but each page gets its own instance (scoped to its back-stack entry; the state is saved settings, so they agree); the summary text rules are pure code in `domain/settings/SettingsSummary.kt`.
- The empty-state "Add a server" buttons (Home, Library) go straight to the Servers page.
- The About page reads the version through `AppInfo` (`data/AppInfo.kt`), which `androidApp` provides in `AppModule` (iOS: `IosAppInfo`, the bundle's `CFBundleShortVersionString`).
- The hub is host-tested for text and routes only, not checked on a device

## Back up and restore (review R27)
- Settings, Back up and restore (`BackupSettingsScreen`, `BackupViewModel`, route `settings/backup`) shares one JSON text and reads one back. The text goes through the system share sheet (`TextSharer`, else the clipboard); a restore is a pasted text. No file picker, so Android and iOS behave the same.
- `BackupService` (`data/backup`) reads the DAOs and `AppSettings`; the format, the matching and the plan are pure code in `domain/backup/Backup.kt` (`BackupFile`, `BookIndex`, `restorePlan`), versioned (`BACKUP_VERSION`; a newer file is refused).
- Version 1 holds favourites, Up next, the corrections of merged books and eight simple settings (theme, font, layout, speed, skip lengths, mirror progress, cross sync on add); the phone-bound Wi-Fi only and Android Auto resume are not carried. Not in it: statistics, the position of Plex-only books, per-book speeds, filters and source choices (they name this phone's server ids).
- A book is named by `serverKey(baseUrl)` (no scheme, lower case) and its id, with title and author as the fallback when the address changed and only one book matches. No token and no id of this phone's servers is written.
- Restore adds favourites and queue entries it does not have, upserts corrections and sets settings. Entries whose book is missing are counted and said ("add the server, let it sync, then restore again"). Not checked on a phone.

## First run and What's new

- Home with no server shows `FirstRunGuide` (`ui/home`): a welcome and three steps (add a server, pick libraries, add a widget) with the "Add a server" button. It is the empty state, so it needs no saved flag and goes away with the first server.
- `WhatsNewPrompt` (`ui/settings`, shown from `AppNavigation` next to `BatteryPrompt`) shows a bottom sheet with the notes of the installed version once. The text is `CHANGELOG.md`, read by the pure parser `domain/whatsnew/WhatsNew.kt` (`parseChangelog`, `notesToShow`).
- The app reads a bundled copy at `shared/src/commonMain/composeResources/files/CHANGELOG.md`; `scripts/check-docs.sh` fails when it differs from the root file. After editing `CHANGELOG.md`, copy it over the bundled one.
- The saved value is `AppSettings.lastSeenVersion` (release part only: `0.5.4-debug.12` counts as `0.5.4`). A fresh install (no servers) records the version silently and sees no sheet; an install that already has servers and no record sees the notes once.
- A new version needs a `## <version> (<date>)` section in `CHANGELOG.md` or nothing is shown. Not checked: the sheet on a real phone after an update.

## Diagnostics

- Diagnostics (debug builds only): Settings, About shows a Diagnostics row only when `DebuggableQualifier` is true (`showDiagnosticsRow`; the route `settings/diagnostics` is registered only then).
- `DiagnosticsScreen` / `DiagnosticsViewModel` (`ui/settings`) pick a merged book and show, per copy,
  - the local progress,
  - the server progress read on request (`BookRepository.readServerProgress` over `LibrarySource.fetchProgressReading`: ABS the same read as `fetchProgress`, Plex the track list with offsets, play counts and last viewed times),
  - the pending queue rows and the download rows (`DiagnosticsData`, read only),
  - plus the merge corrections and what the Download from / Play from rules pick (`describeMerge`).
- The text is built by `buildReport` in `domain/diagnostics` and always passes through `redact()` (no tokens, URLs, hosts, addresses, machine ids; only the user's own book ids stay).
- It is shown selectable, copied through `TextClipboard` and written to the log with tag `ChapterPlayerDiag` (`adb logcat -s ChapterPlayerDiag`; `DiagnosticsLogger`, Logcat on Android, `NSLog` on iOS) when "Read from server" finishes and on "Log report".
- The page changes no data and no server state: the progress reads use the stored token as it is (no refresh, no sign-out; a 401 is `ReadRefusedException`), and a failed read is worded as the exception type plus a fixed sentence (`diagnosticsFailureText`), never its message

## Report a problem

- Report a problem (every build; review R7): Settings, About has a "Report a problem" card.
- `ProblemReporter` (`data/diagnostics`) builds a text from
  - the app version,
  - the system line (`platformDescription`),
  - the servers by kind with their last sync (`ServerSyncPhase`, no names or addresses),
  - the size of the pending progress queue,
  - the last crash
  - and the last 300 events (`ProblemLog`, in memory only: failed HTTP calls as method and status, never an address),
  - and `buildProblemReport` (`domain/diagnostics/ProblemReport.kt`) passes the whole text through `redact()` (stack frames are kept readable).
- Android saves the trace of an uncaught exception, redacted, in `cacheDir/last_crash.txt` (`installCrashRecorder` in the Application, `FileCrashStore`); iOS has no crash capture yet (`NoCrashStore`).
- The text goes to the system share sheet (`LocalTextSharer`, provided by `MainActivity`) or, where none is provided, to the clipboard.
- No network call, no analytics.
- A long press on the version line opens the book-level Diagnostics page in any build (its route is always registered; its About row stays debug-only).
- The sync line per server uses fixed words (a failed sync's own message can quote a library name); crash traces keep stack frames and exception class names (`redactTrace`) and carry the crash time.
- Diagnostics' "Log report" now also writes its redacted report to logcat in release builds.
- Host-tested; not yet seen on a phone.

## Plain http

- Plain http (review R11): cleartext traffic stays allowed because home servers need it.
- `addressExposure` (`domain/settings/CleartextAddress.kt`, on the output of `normalizeServerAddress`, so a missing scheme counts as http) sorts an address into encrypted, private network (RFC 1918, loopback, link local, 100.64/10 and Tailscale `.ts.net`, one-word names, `.local`/`.lan`/`.home`/`.internal`/`.home.arpa`) or public cleartext.
- Signing in to a public http address (the four sign-in actions on the Add and Edit Server screen, through `CleartextGate`) asks first: "Use https" rewrites the address and does not sign in; "Continue anyway" signs in as typed.
- Any http server shows "Not encrypted (http)" on the Servers page (`ServerRowUi.notEncrypted`).
- Plex addresses come from plex.tv and are not gated.
