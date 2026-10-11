# Stage 1: Kotlin Multiplatform migration (design)

Status: approved by the project owner on 2026-10-12.
Scope source: `docs/ROADMAP.md`, Stage 1.

## Goal

Move the Android app onto a shared Kotlin Multiplatform module without
changing what it does, so an iOS app can reuse the shared code in Stage 5.

Done when:
- Library sync, ABS and Plex sign-in, Settings and the theme behave as they do on `main` at commit 54385e0.
- Models, API clients, database, ViewModels and screens live in `shared/commonMain`. Only playback, Android Auto and widgets stay platform-specific.
- API parsers have unit tests built from recorded server JSON, and CI runs them on every PR.

## Target structure

```
shared/        Kotlin Multiplatform module (commonMain, androidMain, iosMain, commonTest)
androidApp/    MainActivity, application class, manifest, resources
iosApp/        Empty Xcode project linking the shared framework (compiled in CI only)
```

- `applicationId` stays `com.chapterplayer`; debug keeps the `.debug` suffix.
- The Room schema and database file name stay unchanged so existing installs keep their data.
- Auth headers stay redacted in HTTP logging (CLAUDE.md rule).

## Approach: incremental, eight pull requests

Each PR builds, passes CI and gets an emulator pass before merge. Branches are `stage-1/<feature>`. The order follows dependencies: Compose Multiplatform needs Kotlin 2.x, and Hilt and kapt are Android-only, so they leave before code moves into `commonMain`.

| PR | Branch | Change | Risk |
|---|---|---|---|
| 1 | `stage-1/toolchain` | Kotlin 2.x, Compose compiler plugin, current AGP and Gradle, add KSP beside kapt, bump AndroidX only as required | Medium |
| 2 | `stage-1/module-split` | Create `shared/` (Android target only) and `androidApp/`; move code unchanged into `shared/androidMain`; retire `app/`; update CI paths | Medium |
| 3 | `stage-1/ktor` | Record and sanitize JSON fixtures; parser tests pass on Moshi first; port DTOs and clients to kotlinx.serialization and Ktor in `commonMain` | High |
| 4 | `stage-1/koin` | Hilt to Koin; remove Hilt and its kapt processor | Medium |
| 5 | `stage-1/room-kmp` | Room KMP with KSP; DAOs and entities to `commonMain`; DataStore multiplatform artifact; schema export and migration test | High |
| 6 | `stage-1/compose-mp` | Compose Multiplatform, JetBrains navigation-compose, Coil 3; ViewModels, screens, theme to `commonMain`; resources via Compose Multiplatform | Medium |
| 7 | `stage-1/interfaces` | `LibrarySource` and `PlaybackController` in `commonMain`; ABS and Plex clients implement `LibrarySource` | Low |
| 8 | `stage-1/ios-shell` | Empty `iosApp/`; macOS CI job compiles the framework; tick Stage 1 in the roadmap; tag `v0.1.0` | Low |

PR 6 is the largest. If it grows unwieldy it splits into theme and components first, then screens.

## Library changes

| Today | Becomes | PR |
|---|---|---|
| Kotlin 1.9 / AGP 8.4 | Kotlin 2.x / current AGP | 1 |
| Retrofit + OkHttp | Ktor client | 3 |
| Moshi | kotlinx.serialization | 3 |
| Hilt | Koin | 4 |
| Room (kapt) | Room KMP (KSP) | 5 |
| Coil 2 | Coil 3 | 6 |
| Navigation Compose | JetBrains navigation-compose | 6 |

Exact versions are chosen in PR 1 after checking the compatibility matrix for Kotlin, AGP, Compose Multiplatform and Room KMP.

## Interfaces (drafts, finalized in PR 7)

```kotlin
interface LibrarySource {
    val type: ServerSource
    suspend fun browse(page: Page): List<AudioBook>
    suspend fun search(query: String): List<AudioBook>
    suspend fun streamInfo(bookId: String): StreamInfo
    suspend fun reportProgress(update: ProgressUpdate)
}

interface PlaybackController {
    val state: StateFlow<PlaybackState>
    suspend fun load(stream: StreamInfo, startMs: Long)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun release()
}
```

`LibrarySource` is the only thing the UI uses to reach a server. `PlaybackController` has one implementation per platform (Media3 in Stage 2, AVPlayer in Stage 5). Both land in PR 7 with no playback behavior.

## Testing

- CI on every PR: build the debug APK, run unit tests. PR 8 adds a macOS job for the iOS framework.
- Parser tests (PR 3): fixtures recorded from real ABS, Plex and plex.tv responses with tokens and personal data removed. They pass against Moshi before the swap and must pass unchanged after it.
- Upgrade test (PR 5): install the previous build, add a server, install the new build over it, confirm data survives.
- Emulator pass on every PR: install, launch, open Library, Settings and Add Server, check logcat for crashes.
- Real server pass (PRs 3, 5): add an ABS or Plex server and sync a library. Needs a test server; none is available yet, and no baseline of today's sync behavior has been captured.

## Risks and open items

- **Ktor swap (PR 3):** Plex response shapes are loose; fixtures are the protection. Live behavior is unchecked until a test server exists.
- **Room KMP (PR 5):** a schema mismatch would crash or wipe existing installs. Export the schema and add the migration test before touching entities.
- **Koin (PR 4):** bindings resolve at runtime, so a missing binding crashes when a screen opens. Open every screen on the emulator.
- **iOS (PR 8):** Xcode is not available to the agent; iOS is verified by CI only until run on a Mac.
- **Unread code:** the plan was drawn from build files and the roadmap, not a full read of the app code. PR 1's task plan starts with that read, and any surprise updates this spec.
- **Token storage:** how `ServerConfigEntity` stores tokens has not been reviewed. Check during PR 5 and record the finding; changing storage is out of scope for Stage 1 unless it blocks the migration.
