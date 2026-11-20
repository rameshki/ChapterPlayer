# Credentials, dependency injection, host tests

Where tokens live, how Koin is organised and how the host tests are laid out.

Detail moved out of `CLAUDE.md` (review R8); keep it current when this area changes.

## Credentials and settings

- Server tokens live in an AES-GCM encrypted DataStore (`CredentialStore`) keyed by the Android Keystore (iOS: `KeychainCredentialStore`, generic-password items of service `com.chapterplayer.credentials`, the same keys, after first unlock, this device only), not in Room (the Room `token` column stays, empty).
- Audiobookshelf signs in with username and password (`AbsSignIn`, port `AbsSignInPort`) and keeps a refresh token (`<serverId>.refresh`); `AbsSessionManager` refreshes the access token before it expires and on a 401 (one refresh at a time per server; a rejected refresh means `SignInRequiredException`, shown as "Sign in again" in Settings).
- Libraries the user turned off are stored in `excluded_libraries`.
- App preferences (theme, library layout, car resume) live in `AppSettings`.
- Tests that write to a DataStore use `InMemoryPreferencesDataStore`, because DataStore cannot rewrite its file on Windows

## Dependency injection

- Dependency injection is Koin (Classic DSL).
- `commonMain/.../di/CommonModules.kt` holds the shared bindings and the qualifiers:
  - `commonModules`: DAOs, network, stores, sources, servers and sync, `commonRepositoryModule` (the book repository), `commonBrowseModule`, `commonPlaybackModule` (progress, queue, mirrors, bookmarks, history, stats, `PlaybackStarter`) and every view model;
  - each platform provides the database, the DataStores, the credential store, the Plex client id, connectivity, `AppInfo`, the debug flag, `BlockingIoQualifier`, `DayZone`, `ListeningClock`, and the player and download parts: `PlaybackController`, `LocalBookFiles`, `DownloadController`, `DownloadStorage`, `DownloadOverview`, `NotificationPermission` and `PlatformFeatures`
  - Android's modules in `androidMain/.../di/KoinModules.kt` include them (`sharedModules`, started by the Application);
  - iOS starts `commonModules` + `iosModules` (platform, `iosPlaybackModule`, `iosDownloadModule`) through `initKoin` (`di/KoinStart.kt`, once);
  - its platform module is `platformModule(PlatformParts)` (`di/PlatformModule.kt`), which `KoinModulesTest` builds from fakes so a missing qualified binding fails.
- Koin fails when a screen opens, not at build time, so `KoinModulesTest` runs `verify()` over every module: add a new class to a module and the test shows what it still needs

## Tests

- Host tests in `shared/src/androidHostTest` (`./gradlew :shared:testAndroidHostTest`), which also runs `shared/src/commonTest`:
  - tests of pure shared code (most `domain/*` rules, the iOS stand-ins, `ForegroundSync`) that also run on iOS (`:shared:iosSimulatorArm64Test`, macOS CI only). commonTest uses kotlin.test and coroutines-test only, no fakes from `androidHostTest/testing` and no fixtures, and Kotlin/Native rejects commas in backtick test names.
- Room migrations are tested on real database files: `DatabaseMigrationTest` builds a database at each exported schema version, writes rows, and opens it at the current version through Room's migration path on `sqlite-bundled-jvm` (test only; Android's app database uses the framework SQLite, which cannot run on the host).
- Parsers are tested against recorded server responses in `src/androidHostTest/resources/fixtures`: real structure, synthetic text, ids, paths and tokens.
- The repo is public, so never commit real server data
