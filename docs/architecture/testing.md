# Testing: screenshot and UI tests

How the app's screens are tested by image and by semantics on the host, and the plan to cover the rest.
Started by review finding R2 (no UI or screenshot tests; layout faults were only found by eye).

## What is set up (spike R2, November 2026)

- **Roborazzi on Robolectric in `androidHostTest`.** Plugin `io.github.takahirom.roborazzi` 1.77.0 on `:shared`,
  Robolectric 4.17, JUnit 4.13.2, `androidx.compose.ui:ui-test-junit4` (Compose BOM). `withHostTest {
  isIncludeAndroidResources = true }` puts the Android resources and the Compose resources (fonts) on the test
  classpath, so the app's own fonts draw. Robolectric settings in `shared/src/androidHostTest/resources/robolectric.properties`:
  `graphicsMode=NATIVE` (required to draw; without it `Bitmap.createBitmap` returns null) and `sdk=34`
  (Robolectric refuses SDK 35 and newer on Java 17: "Android SDK 36 requires Java 21"; the build and CI run JDK 17).
- **First screenshot test:** `ui/screenshots/FontCardScreenshotTest` draws Settings, Appearance's font picker
  (`FontCard`, Bookish selected) in dark and light at font scale 1.0 and 1.3. `ScreenshotFrame` applies the app
  theme on the page colour and sets the font scale through `LocalDensity`; `SCREENSHOT_QUALIFIERS` is a
  360 x 900 dp window at mdpi, which keeps each golden at 25 to 33 KB. Goldens: `shared/src/androidHostTest/screenshots/*.png`
  (the plugin's `outputDir`), committed.
- **First UI test (no image):** `ui/library/SelectionTopBarUiTest` draws selection mode's top bar over the real
  `BookSelectionViewModel` and the existing fakes with Compose's multiplatform `runComposeUiTest` under the
  Robolectric runner: "1 selected", Select all gives "3 selected", the actions menu offers the mark and favourite
  rows and (outside the app's `LocalPlatformFeatures` provider) no Up next row, Close leaves the mode.

## Screenshot tests by screen

- **Player (R2 step 2, R25):** `ui/screenshots/PlayerScreenshotTest` draws `PlayerContent` (already stateless; the Up next sheet is a stand-in) playing with chapters and a sleep timer in dark and light, at font scale 2.0, with very long titles and an error banner, in landscape, at a tablet width and as a failed start: 8 goldens.
- They found that the tool row broke labels inside words ("Bookmark" and "s") on a 360 dp phone and at large text; `toolsPerRow` now puts them in rows of 3 and 2 (or 2) when five do not fit.
- Known and left: the error banner shows two lines of its text at 2.0 scale.

- **Home (R2 step 2, R25):** `ui/screenshots/HomeScreenshotTest` draws `HomeBody` (extracted from `HomeScreen`: the spinner, the empty states and the shelves, from `HomeUiState` and callbacks) with the Resume card and every shelf in dark and light, at font scale 2.0, with a long title, at a tablet width, and the no-server, syncing and filters-hiding states: 8 goldens.
- The tablet image showed the Resume card and its button stretched across 1264 dp; the card is now at most 640 dp wide.
- Covers are the app's placeholders (no Coil in the test).
- **Add Server (R2 step 2):** `ui/screenshots/AddServerScreenshotTest` draws the internal `AbsAddSection` and `JellyfinAddSection` (they already take plain state, so no refactor was needed) for the Audiobookshelf form and its error, the Jellyfin form, the Quick Connect wait with a code and the libraries step, in dark, light and at font scale 2.0 (a tall window, since the page scrolls in the app):
  - 11 goldens.
- Plex's section needs a refactor and is not covered.
- **Mini player (R2 step 2, R25):** `ui/screenshots/MiniPlayerScreenshotTest` draws `MiniPlayerContent` with a short and a very long title in dark and light, at font scale 2.0, in landscape (800 x 360 dp) and at a 10 inch tablet width (1280 x 800 dp): 5 goldens.
- Nothing clipped; at scale 2.0 a long title shortens to one line (the full title is in the content description).
- Each test can set its own window with `@Config(qualifiers = ...)`, the way to add tablet and landscape cases for other screens.

- **Book details (R2 step 2, R25):** `ui/screenshots/DetailsScreenshotTest` draws `BookDetailsContent` (already stateless) with the chapters, about and series states, a long title, playing, finished, downloading, cannot play offline, a play error, loading and not found, in dark, light, at font scale 2.0, in landscape and at a tablet width: 14 goldens.
- They showed the title breaking inside words beside the cover at 2.0 (the cover now goes above the title from font scale 1.5) and the button, tabs and chapter times stretched across a tablet (the page is now a column at most 720 dp wide).
- **Library (R2 step 2, R25):** `ui/screenshots/LibraryScreenshotTest` draws `BookLayout` (large grid, small grid, list: long titles, a favourite, a downloaded, a downloading, a finished and a dimmed book, selection mode), `GroupList` and the sign-in-needed state in dark, light, at font scale 2.0, in landscape and at a tablet width: 13 goldens.
- The tablet images showed two grid tiles 614 dp wide and list rows 1264 dp wide; the grid now takes its column count from the width (`libraryGridColumns`: 2 large and 3 small on a phone, 6 and 10 on a 1280 dp tablet) and the list is at most 720 dp wide.
- Known and left: on a placeholder cover the favourite heart covers the first letters of the title (real covers show an image).

- **Settings pages (R25):** `ui/screenshots/SettingsPageScreenshotTest` draws the shared `SettingsPage` frame with switch cards on a phone, in landscape and on a tablet: 3 goldens.
- `SettingsPage` and Add Server now keep their content in a column at most 640 dp wide (`SETTINGS_MAX_WIDTH`), centred; before, the cards ran across the whole tablet.
- Other settings pages use `SettingsPage`, so they follow.

- **Widgets (R2 step 3):** `ui/screenshots/WidgetScreenshotTest` composes each Glance widget at an exact size with `GlanceAppWidget.compose` (a test widget with `SizeMode.Exact` that calls the internal `NowPlayingContent`, `ListenContent` or `ContinueContent`),
  - inflates the `RemoteViews` into a `FrameLayout` under Robolectric,
  - draws that into a bitmap and captures the bitmap (Roborazzi's View capture needs an Activity). 31 goldens:
  - Now Playing at three widths, with a long title, Resume and empty;
  - Continue Listening with a book and empty;
  - Listen at all eleven size buckets in dark, three of them in light and with long titles, plus Resume and empty.
- Colours are baked for the chosen theme (`widgetColors`), the cover is a drawn bitmap.
- The goldens showed the title squeezed to 36 to 62 dp at the smallest widths ("A R...", "An Extr..."); Now Playing now shows the skips from 250 dp and the cover from 300 dp, and the Listen bar its cover from 240 dp (a new BAR_MID bucket at 250), so a title keeps 100 dp (`WidgetDimens.ROW_TITLE_COMFORT_DP`).
- Continue Listening is a single size and was left as is (76 dp for the title at its 180 dp minimum).

- Checked on Windows (JDK 17): `verifyRoborazziAndroidHostTest` passes with all 3191 host tests; widening the
  rows' padding by 2 dp made all four screenshot tests fail with "... font_card_dark_100.png is changed"; plain
  `testAndroidHostTest` left the goldens untouched. `createComposeRule` warns that it is deprecated in favour of
  `androidx.compose.ui.test.junit4.v2.createComposeRule` (StandardTestDispatcher); move to v2 when more tests come.

## Recording and verifying goldens

- Record (after a deliberate UI change): `./gradlew :shared:recordRoborazziAndroidHostTest`, look at the changed
  PNGs in `shared/src/androidHostTest/screenshots` (git shows them), commit them with the change.
  Add `--tests "com.chapterplayer.ui.screenshots.*"` to run only the screenshot classes.
- Verify: `./gradlew :shared:verifyRoborazziAndroidHostTest` runs every host test and fails when a captured image
  differs from its golden (or a golden is missing); the diff images and an HTML report land in
  `shared/build/outputs/roborazzi` and `shared/build/reports/roborazzi/index.html`.
- Compare without failing: `compareRoborazziAndroidHostTest`.
- Plain `:shared:testAndroidHostTest` runs the screenshot tests too but only draws (no record, no compare), so
  it still catches crashes in the composables and stays as fast as before apart from Robolectric's start-up.
- The first run downloads Robolectric's Android 14 jar (about 150 MB) into the Gradle/Maven cache.
- Goldens are drawn by Robolectric's native graphics (the same Skia on Windows, macOS and Linux in practice, but
  font hinting can differ slightly between host OSes). If CI on Linux disagrees with goldens recorded on Windows,
  record them on CI (an artifact) or give `captureRoboImage` a small `changeThreshold` in `RoborazziOptions`;
  not needed so far, not yet tried on Linux.

## Options evaluated (versions: Kotlin 2.4.21, AGP 9.3.3 built-in Kotlin, KMP `com.android.kotlin.multiplatform.library`, CMP 1.12.1, Gradle 9.5, JDK 17)

- (a) Roborazzi + Robolectric in `androidHostTest`: **Works** (chosen).
- Roborazzi supports the KMP Android library plugin since PR #758 (Nov 2025); tasks are named after the test variant (`...RoborazziAndroidHostTest`).
- Gradle 9 race (#830, two Roborazzi test tasks in one invocation sharing an output dir) does not apply: only `androidHostTest` captures; the plugin also adds `...IosSimulatorArm64` tasks, which capture nothing (keep the iOS tests and the Android tests in separate Gradle invocations, as CI does).
- (b) Paparazzi: Not used. 1.3.x does not support AGP 9 or the KMP library plugin; 2.0.0-alpha05(.1) adds AGP 9 and the KMP plugin but is a pre-release and since 2.0.0-alpha04 requires **Java 21** (the project builds on JDK 17).
- (c) AGP Compose Preview Screenshot Testing (`com.android.compose.screenshot`): Not used. Google marks the standalone plugin unmaintained in favour of AGP test suites, which need **AGP 9.5** (we are on 9.3); its docs state it supports Android-only projects, not KMP modules; tests must be `@Preview` functions in a `screenshotTest` source set.
- (d) `runComposeUiTest`: **Works in `androidHostTest`** under `@RunWith(RobolectricTestRunner::class)` (the androidx `ui-test` artifact already carries it).
- Not in `commonTest`: there the same test would run on the JVM without the Robolectric runner (no Activity) and also on the iOS simulator; it would need an expect/actual runner base class and `compose.uiTest` in commonTest.
- Possible later, not worth it now.

## Plan for the full suite (later steps)

Each screen test takes plain state and fakes, never Koin or a real server; synthetic titles only (repo is public).
Every screenshot is drawn in dark and light at font scale 1.0 and 1.3 (four goldens, about 30 to 60 KB each at mdpi).

1. **Make screens testable** (the main cost): Home, Library, Details, the player and Add Server read view models
   from Koin. Split each into a stateless `XxxContent(state, callbacks)` that the screen calls, or start Koin in the
   test with the fakes already in `androidHostTest/testing`. About 0.5 day per screen. Covers load through Coil:
   use a Coil test `ImageLoader` (a fake engine with a fixed colour) so covers are deterministic.
2. **Screens** (after step 1): Home (Resume card, shelves, empty state), Library (grid, list, filter hint, selection
   mode), Details (ABS, Plex, merged with two sources, downloading), the full player (chapters, sleep timer on) and
   mini player, Add Server (ABS, Plex, Jellyfin, an error). About 1 to 1.5 days for the five, goldens included.
3. **Widgets:** Glance does not draw through Compose UI, so Roborazzi's Compose capture does not apply directly.
   Two routes: Glance's `glance-appwidget-testing` unit test (`runGlanceAppWidgetUnitTest`, semantic assertions per
   size bucket, no image), or render the Glance `RemoteViews` into a host view under Robolectric and capture that
   view with Roborazzi (`captureRoboImage` on a `View`). Start with the three buckets that break first (Listen
   `BAR`, `WIDE_MID`, `LARGE_ROOMY`), dark and light, 1.0 and 1.3. About 1 to 2 days, the RemoteViews route is
   the uncertain part.
4. **CI (done, R2):** the build job runs `:shared:verifyRoborazziAndroidHostTest` instead of `:shared:testAndroidHostTest` (the same
   tests plus the image check); a failed run uploads `shared/build/outputs/roborazzi` and `shared/build/reports/roborazzi` in the
   `test-reports` artifact, so a reviewer sees the diff. Found by the first CI comparison (19 Nov): the goldens recorded on Windows
   differ from what Linux draws in about 0.1 percent of the pixels, by at most 2 of 255 in a colour channel (glyph edges), which is
   just over Roborazzi's default per-pixel distance of 0.007, so every image read as changed. The tests now compare with
   `SimpleImageComparator(maxDistance = 0.05f)` (`SCREENSHOT_OPTIONS` in `FontCardScreenshotTest.kt`); a 2 dp padding change still
   fails all four images. Keep that options value for every new screenshot test.
5. **Instrumented smoke test on a CI emulator:** one `androidApp` `androidTest` that starts `MainActivity` with an
   in-memory fake server module (or a MockWebServer serving the fixtures), opens Library, a book's details and the
   player, and checks no crash; run with `reactivecircus/android-emulator-runner` (API 34, x86_64, KVM on
   ubuntu-latest), about 6 to 10 minutes per run, so on `main` and manual runs only. About 1 to 2 days, mostly
   the fake server wiring and emulator flakiness.

Rough total for the full suite: 5 to 8 days.
