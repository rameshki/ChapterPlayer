# ChapterPlayer project review, 14 November 2026

Reviewed at `main` cfd6ed1 (version 0.5.3): the code, the requirements and roadmap, the recorded decisions, CI and the docs. Written to be handed to the agents that work on the project.

Updated the same day with Robert's answers to the open decisions.

## How to use this file (for agents)

- Each finding has an id (R1, R2, ...), a priority, the evidence it rests on, what to do and a 'done when' test. Take one finding or one batch per branch and PR.
- 'Confidence' says how the finding was established. 'Needs a device check' means the logic was read but the behaviour was not observed.
- Follow the project's usual process: a plan in `docs/superpowers/plans/`, an implementer, an independent review, one fix wave, CI, squash merge.
- A finding's 'Decision / update' line, where present, is Robert's answer and overrides the text above it. Do not start work that depends on a decision still marked open.
- When a finding is done, record it in `docs/PROJECT_STATUS.md` and tick it here.

## The project in numbers

| | |
|---|---|
| App code | 45,600 lines of Kotlin in 412 files (shared, Android, iOS) |
| Test code | 44,200 lines; 2,989 host tests |
| Findings | 31: 9 to do first, 11 to do next, 11 later |

## What is strong (keep doing it)

- The rules are pure code with tests: 2,989 host tests, about 44,000 lines of test code against 45,000 lines of app code.
- Token handling is disciplined and was checked in every review: tokens only in headers, never in addresses, logs, exceptions or media metadata; redirects are origin-safe.
- Every feature had a written plan, an independent review and a fix wave before merge; the reviews found a real fault in almost every PR.
- Parsers are tested against recorded-shape fixtures with synthetic data, and the public repo holds no real server data.
- CI checks the Room schema, links the iOS framework and runs the shared tests on Kotlin/Native on every PR.
- Dependency injection is verified by a test (KoinModulesTest), so a missing binding fails the build, not a screen.

## Summary

| Id | Priority | Area | Effort | Finding |
|---|---|---|---|---|
| R1 | Do first | Verification | M | Most features since Stage 2 have never been checked on a device |
| R2 | Do first | Verification | L | There are no UI tests and no screenshot tests |
| R3 | Do first | Verification | S | CI never builds the release (R8) build and runs no lint |
| R4 | Do first | Correctness | S | Restoring from a phone backup can leave 'Downloaded' books that cannot play |
| R5 | Do first | Correctness | M | targetSdk is 34 while the app compiles against 37 |
| R6 | Do first | Correctness | M | Jellyfin stage 2 is half built and stopped; 0.5.3 never sends progress to Jellyfin |
| R7 | Do first | Product | M | Release testers cannot report a problem: no crash capture, Diagnostics is debug-only |
| R8 | Do first | Docs and process | M | CLAUDE.md is 78 KB and is loaded into every session and every subagent |
| R9 | Do first | Docs and process | S | PROJECT_STATUS.md is stale and contradicts itself |
| R10 | Do next | Architecture | M | The merged library is recomputed separately by every screen and service |
| R11 | Do next | Security | S | A password can be sent over plain http with no warning |
| R12 | Do next | Verification | M | Releases are built by hand on one PC and are not reproducible |
| R13 | Do next | Architecture | L | A few classes have grown to do several jobs |
| R14 | Do next | Architecture | S | No static analysis, coverage report or dependency updates |
| R15 | Do next | Docs and process | S | No .gitattributes or .editorconfig: the CRLF working tree keeps costing time |
| R17 | Do next | Docs and process | S | README and ROADMAP no longer match the app |
| R18 | Do next | Docs and process | S | Decisions are scattered and several are still open |
| R23 | Do next | Verification | S | The M4B converter has never encoded a real file, and its tests are not in CI |
| R24 | Do next | Product | M | Accessibility has been designed for but never tested |
| R31 | Do next | Product | L | Nothing is in place yet for a Google Play release |
| R16 | Later | Architecture | S | Every user-facing text is a hard-coded English string |
| R19 | Later | Security | S | Car and cover access trust a package name, and the simulator is trusted in release builds |
| R20 | Later | Correctness | S | Only one database migration is tested |
| R21 | Later | Architecture | S | The R8 keep rules are far broader than needed |
| R22 | Later | Docs and process | S | Repository housekeeping: old branches, worktrees, one stale PR, missing tags |
| R25 | Later | Product | M | Tablets, landscape and foldables are untested |
| R26 | Later | Product | M | Common listening options are missing |
| R27 | Later | Product | M | Data that lives only on the phone cannot be exported or moved |
| R28 | Later | Product | S | No first-run guidance and no 'what's new' |
| R29 | Later | Product | S | Notifications still use Android's placeholder icons |
| R30 | Later | Verification | M | The iOS app has never been launched, yet every feature pays for it |

## Do first

### R1. Most features since Stage 2 have never been checked on a device

- **Area:** Verification. **Effort:** Medium (one to three days). **Confidence:** Confirmed in the docs.
- **Why it matters:** The status file carries more than a dozen separate 'Check on the phone' lists (widgets and headless Play, Android Auto covers, lock-screen cover, sleep timer carry-over, Wi-Fi to mobile data mid-download, reboot with a download running, stats against real history, multi-select on 1,000 books, the keyboard fix, saved filters). They are scattered through 88 KB of prose, so nobody can see what is verified. Bugs found so far on the phone (Bookmarks label wrap, mini player edge, 'Nothing playing', Play not asking) were all invisible to host tests.
- **Evidence:** docs/PROJECT_STATUS.md: 'Host-tested only: NOT seen on a phone' appears on almost every entry; no single checklist exists.
- **What to do:** Create docs/DEVICE_CHECKS.md: one table, one row per check (feature, steps, Android version, result, date, build). Move every 'Check on the phone' list into it and delete them from the status file. Add 'row added to DEVICE_CHECKS.md' to the definition of done in every plan. Run the table top to bottom in one or two wireless-debugging sessions.
- **Done when:** One file lists every device check with a status; the status file links to it; each open row has an owner session.
- [ ] Done

### R2. There are no UI tests and no screenshot tests

- **Area:** Verification. **Effort:** Large (a week or more). **Confidence:** Confirmed in code.
- **Why it matters:** All 2,989 tests are host tests of rules and view models. Nothing renders a screen or a widget, so layout faults (clipped titles, wrapped labels, keyboard overlap, widget buckets, contrast on real surfaces) are only found by eye on Robert's phone. The widget work alone needed three review rounds about sizes that a screenshot test would have shown.
- **Evidence:** No androidTest / androidDeviceTest source set; no createComposeRule, runComposeUiTest, Roborazzi or Paparazzi anywhere in the build.
- **What to do:** Add screenshot tests (Roborazzi on Robolectric) for the three widgets in every bucket, the player, Home, Library, Book details and Add Server, each in dark and light and at font scale 1.0 and 1.3. Add a few Compose UI tests (runComposeUiTest in commonTest where possible) for the long-press menu, selection mode and the player's start states. Add one instrumented smoke test (launch, add a mock server, play) on a CI emulator.
- **Done when:** CI fails when a golden image changes without review; the smoke test launches the app on an emulator in CI.
- [ ] Done

### R3. CI never builds the release (R8) build and runs no lint

- **Area:** Verification. **Effort:** Small (under a day). **Confidence:** Confirmed in code.
- **Why it matters:** CI runs assembleDebug and the host tests only. The APK testers receive is the minified release build, which is only ever built on one PC. An R8 regression (a stripped Glance callback, a serializer, a Koin constructor) would reach a tester before any check sees it. Android lint is not run at all.
- **Evidence:** .github/workflows/android.yml: ':androidApp:assembleDebug :androidApp:testDebugUnitTest :shared:testAndroidHostTest'; no assembleRelease, no lint task.
- **What to do:** Add ':androidApp:assembleRelease' and ':androidApp:lintRelease' (with a baseline) to the build job. Add a step that greps the R8 seeds file for the widget ActionCallbacks and receivers. Upload the unsigned release APK as an artifact.
- **Done when:** A PR that breaks the release build or strips a kept class fails CI.
- [ ] Done

### R4. Restoring from a phone backup can leave 'Downloaded' books that cannot play

- **Area:** Correctness. **Effort:** Small (under a day). **Confidence:** Logic confirmed in code; behaviour needs a device check.
- **Why it matters:** Android backup restores the Room database (so the downloads rows say DONE) but the downloads folder is excluded. A missing folder is read as 'storage not mounted', which never marks the download failed, so the book stays 'Downloaded' with no Try again. On a new phone after a restore every download is in this state.
- **Evidence:** domain/playback/PlaybackSource.kt localCopyOf: '!folderPresent -> LocalCopy.UNREACHABLE'; shouldMarkFailed only for MISSING_FILES; data_extraction_rules.xml excludes 'downloads' but not the database.
- **What to do:** Tell an unmounted removable volume apart from internal storage: a missing folder on internal storage (always mounted) is MISSING_FILES. Add a start-up reconcile pass that resets DONE rows whose internal folder is gone to 'not downloaded' (or FAILED with Try again). Add tests for both, and a device check: back up, uninstall, restore.
- **Done when:** After a restore, no book claims to be downloaded unless its files are on the phone.
- [ ] Done

### R5. targetSdk is 34 while the app compiles against 37

- **Area:** Correctness. **Effort:** Medium (one to three days). **Confidence:** Confirmed in code.
- **Why it matters:** The app opts out of three Android releases of behaviour changes. Google Play has required API 35 or higher for new apps and updates since August 2025, so the app cannot be published as it is. The Android 15 rule that a dataSync foreground service may run at most 6 hours in 24 collides directly with the roadmap's 'a book may take 30 hours' download case, and it has never been designed for.
- **Evidence:** androidApp/build.gradle.kts: compileSdk = 37, targetSdk = 34. PROJECT_STATUS: 'the Android 15 dataSync 6 h limit (app targets SDK 34 so it does not apply yet)'.
- **What to do:** Plan the move in two steps (35, then 36): handle Service.onTimeout for the download worker (save, reschedule, tell the user), re-check foreground-service starts from the widgets and from boot, predictive back, edge-to-edge, and notification/media permissions. Run the DEVICE_CHECKS table on Android 15 and 16 after each step.
- **Done when:** targetSdk equals the current Play requirement; a download that outlives the 6 hour window pauses and resumes cleanly.
- **Decision / update:** Robert, 14 Nov: the app will go on Google Play, so this is required, not optional. See R31 for the rest of the Play work.
- [ ] Done

### R6. Jellyfin stage 2 is half built and stopped; 0.5.3 never sends progress to Jellyfin

- **Area:** Correctness. **Effort:** Medium (one to three days). **Confidence:** Confirmed in the repo.
- **Why it matters:** Version 0.5.3 is with a tester. Listening to a Jellyfin book queues progress rows that are never delivered (J1 refuses them as 'not yet'), so Jellyfin never learns the position and the rows pile up. The J2 agent was stopped part-way through Task 11.
- **Evidence:** Worktree .claude/worktrees/agent-aa67a47bcdb3591e2, branch stage-j/j2: one commit (8873a14 'Jellyfin: progress rules', Task 10) and seven uncommitted files (Task 11). Not pushed.
- **What to do:** Resume that agent (or a new one on the same plan, docs/superpowers/plans/2026-11-08-jellyfin-j1-j2.md) from the worktree: finish Tasks 11 to 14, independent review, CI, then the J2 phone check (Task 15) on Robert's Jellyfin 12.4. Confirm the queued J1 rows are delivered or dropped correctly on upgrade.
- **Done when:** A Jellyfin book's position and finished state show in Jellyfin web after listening in the app; no stale rows remain in pending_progress.
- **Decision / update:** Robert, 14 Nov: Jellyfin stage 2 will be resumed on 15 Nov. Leave the stage-j/j2 worktree and branch as they are until then.
- [ ] Done

### R7. Release testers cannot report a problem: no crash capture, Diagnostics is debug-only

- **Area:** Product. **Effort:** Medium (one to three days). **Confidence:** Confirmed in code.
- **Why it matters:** The app has outside testers on release builds. When something fails they can only send a screenshot. There is no crash handler, no log, and the Diagnostics page (the tool that solved the Plex position question) is compiled out of release builds.
- **Evidence:** No UncaughtExceptionHandler, crash library or log file in the source; 'showDiagnosticsRow' is true only when the build is debuggable.
- **What to do:** Add a release-safe 'Report a problem' row in Settings, About: it builds the existing redacted report (app version, Android version, servers by kind, last sync results, the queue, the last crash trace saved locally by an UncaughtExceptionHandler, the last few hundred redacted log lines kept in memory) and hands it to the Android share sheet. No network call, no analytics. Keep the book-level Diagnostics page available behind a long-press on the version number.
- **Done when:** A tester on a release build can share a redacted text report after a crash or a failed sync.
- **Decision / update:** Robert, 14 Nov: approved. A 'Report a problem' row that shares a redacted text report is acceptable in release builds.
- [ ] Done

### R8. CLAUDE.md is 78 KB and is loaded into every session and every subagent

- **Area:** Docs and process. **Effort:** Medium (one to three days). **Confidence:** Confirmed in the repo.
- **Why it matters:** It holds whole design histories in single lines of up to 12,900 characters. Every agent pays for it in context on every task, most of it irrelevant to that task, and it has gone stale in places (it still says Jellyfin J1 'is built on stage-j/j1 ... not yet run against a real Jellyfin server', describes merged work as branches, and its Priorities line does not mention Jellyfin). A file this large is also the likeliest cause of agents missing a rule.
- **Evidence:** CLAUDE.md: 52 lines, 78,594 bytes; longest lines 12,922 / 9,759 / 9,054 characters.
- **What to do:** Cut CLAUDE.md to about 150 short lines: what the app is, the rules that must not be broken, the build and test commands, the traps (no JVM calls in commonMain, CRLF, one Gradle command at a time), and a map of where things live. Move each area's detail to docs/architecture/<area>.md (browse-and-merge, playback, progress, downloads, widgets, plex, jellyfin, ios, car) and tell agents to read only the files their task touches.
- **Done when:** CLAUDE.md is under 10 KB; each area file is current; a plan names the area files its implementer must read.
- [ ] Done

### R9. PROJECT_STATUS.md is stale and contradicts itself

- **Area:** Docs and process. **Effort:** Small (under a day). **Confidence:** Confirmed in the repo.
- **Why it matters:** It is meant to let a new session start cold, but it says 'main is at PR #55', lists 'Review and merge' as the next step for at least nine things that are merged (#67, #70, #71, #73, #74, #76, #81, #89, #90), calls merged work 'not pushed, not reviewed', lists tagging v0.4.0 as a future step, and says only 0.3.3 and 0.4.0 were shared. The commit-trailer rule in it names a model that is no longer the one in use.
- **Evidence:** docs/PROJECT_STATUS.md lines 7, 22 to 26, 30, 49 to 67, 79, 92.
- **What to do:** Rewrite it to four short sections: Now (version, what main contains, one line per feature with its PR), Open work (branches and worktrees that hold unmerged work), Next steps (ordered, only things not done), and links (DEVICE_CHECKS.md, DECISIONS.md, CHANGELOG.md). Move the per-feature histories into CHANGELOG.md. Change the trailer rule to the generic 'Co-Authored-By: Claude <noreply@anthropic.com>'.
- **Done when:** A new session can read the file in two minutes and nothing in it is untrue.
- [ ] Done

## Do next

### R10. The merged library is recomputed separately by every screen and service

- **Area:** Architecture. **Effort:** Medium (one to three days). **Confidence:** Confirmed in code.
- **Why it matters:** observeBrowseItems is a cold flow called from about 14 places (Home, Library, Search, details, group pages, player menu, Up next, stats, widgets, the car, the advancer). Each collector merges the whole library again on every progress save, which happens every 10 seconds while a book plays. It was flagged in an early review ('per-screen merge recomputation') and deferred; the library is now 1,062 books across three kinds of server, and every new feature adds another collector.
- **Evidence:** grep 'observeBrowseItems(' in shared/src: 14 call sites; no shareIn/stateIn around the merge in data/merge or domain/browse/MergedSources.kt.
- **What to do:** Add one app-scoped MergedLibrary (Koin single): the merge runs once on the default dispatcher and is shared (shareIn, WhileSubscribed with a short stop timeout, replay 1). Every caller reads it. Make progress-only changes cheap by merging on book structure and applying progress afterwards. Add a benchmark test with 5,000 books.
- **Done when:** One merge per change regardless of how many screens are open; the benchmark has a recorded budget.
- [ ] Done

### R11. A password can be sent over plain http with no warning

- **Area:** Security. **Effort:** Small (under a day). **Confidence:** Confirmed in code.
- **Why it matters:** Audiobookshelf and Jellyfin sign in with a username and password. The form accepts http:// addresses (the placeholder even suggests one) and the app allows cleartext traffic everywhere. That is right for a home network, but nothing tells the user when the address is a public host, where the password and token would travel unencrypted.
- **Evidence:** AndroidManifest: usesCleartextTraffic="true"; AbsSignInSection.kt and JellyfinSignInSection.kt placeholders 'http://192.168.1.10:...'; no 'not encrypted' text anywhere in ui/settings.
- **What to do:** Classify the host (private range, .local, loopback, or public) with a pure rule. For http to a public host show a clear warning with 'Use https' and 'Continue anyway'. Show a small 'Not encrypted' note on the Servers page for any http server. Keep cleartext allowed (home servers need it).
- **Done when:** Adding an http server on a public address needs an explicit confirmation; tests cover the host rule.
- [ ] Done

### R12. Releases are built by hand on one PC and are not reproducible

- **Area:** Verification. **Effort:** Medium (one to three days). **Confidence:** Confirmed in the repo.
- **Why it matters:** The signing key lives in a folder on Robert's PC with its password beside it; signing is a manual zipalign and apksigner run; the 'debug' APKs sent to Robert were built with a temporary local edit that turned minification on; there are no GitHub Releases, no changelog, and the tags stop at v0.4.1 although the app is at 0.5.3.
- **Evidence:** androidApp/build.gradle.kts has no signingConfigs; git tag: v0.1.0 to v0.4.1; 'gh release list' is empty; no CHANGELOG file.
- **What to do:** Add a signingConfig that reads the keystore path and passwords from Gradle properties or environment (never the repo). Add a committed 'tester' build type (debuggable, minified, .debug id) so the small debug APK is a real build. Add a release workflow that runs on a v* tag, signs with repository secrets and attaches the APK to a GitHub Release with notes from CHANGELOG.md. Tag v0.5.3 now. Robert must back the key up off the PC.
- **Done when:** Pushing a tag produces a signed APK on GitHub; any commit can be rebuilt to the same APK.
- [ ] Done

### R31. Nothing is in place yet for a Google Play release

- **Area:** Product. **Effort:** Large (a week or more). **Confidence:** Confirmed in the repo.
- **Why it matters:** Robert decided on 14 November that the app will go on Google Play. Beyond the targetSdk move (R5), Play needs things the project has never produced: an app bundle signed for Play App Signing, a privacy policy and the Data safety form, declarations for the two foreground service types (media playback and data sync) with a short video of each, a content rating, store listing text and screenshots, and a closed test with at least 12 testers for 14 days on a new personal developer account. The app also allows cleartext traffic everywhere, which Play's pre-launch report flags.
- **Evidence:** No privacy policy, store listing assets or bundle configuration in the repo; AndroidManifest declares FOREGROUND_SERVICE_MEDIA_PLAYBACK and FOREGROUND_SERVICE_DATA_SYNC; usesCleartextTraffic=true. Checked on the 0.5.3 release APK: its two native libraries are zip-aligned for 16 KB pages (their ELF alignment was not checked).
- **What to do:** Write docs/PLAY_RELEASE.md as the checklist and work through it: (1) R5 first; (2) build an .aab in the release workflow (R12) and enrol in Play App Signing, keeping the current key as the upload key; (3) a short privacy policy page (no analytics, no data leaves the phone except to the user's own servers) and the Data safety answers; (4) the foreground service declarations and their demo videos; (5) confirm 16 KB page-size support on an Android 15+ emulator with 16 KB pages; (6) listing text, feature graphic and screenshots from the screenshot tests (R2); (7) run Play's pre-launch report and fix what it finds; (8) the closed test. Decide whether Android Auto distribution is wanted at launch, because Play reviews Auto apps separately. Check each Play requirement against the current Play Console help pages before relying on the numbers here.
- **Done when:** An internal-testing release is live on Play, built by CI from a tag, with the policy forms accepted.
- **Decision / update:** New on 14 Nov, from Robert's answer that the app will go on Google Play.
- [ ] Done

### R13. A few classes have grown to do several jobs

- **Area:** Architecture. **Effort:** Large (a week or more). **Confidence:** Confirmed in code.
- **Why it matters:** BookDetailsViewModel (889 lines, a 27-line constructor) is the details page and also the engine behind the long-press menu and parts of bulk actions. BookRepositoryImpl (789 lines, 26 constructor parameters) holds details, progress, marks, mirroring and connection recovery. SettingsViewModel (795 lines) serves every settings page. Each new feature lands in these files, reviews get harder, and one change can break an unrelated screen.
- **Evidence:** wc -l: BookDetailsViewModel 889, SettingsViewModel 795, BookRepositoryImpl 789, EnginePlaybackController 737, PlayerScreen 663, ServerSetupScreen 620.
- **What to do:** Extract small use-case classes that the screens share: PlayBook, DownloadBook, MarkFinished, ToggleFavourite, EditUpNext. Have details, the long-press sheet, selection mode and the widgets call them instead of a details view model in 'quickActions' mode. Split BookRepositoryImpl into details, progress and marks. Give each settings page its own small view model. Do it one class at a time, tests unchanged.
- **Done when:** No main class over about 400 lines; the long-press menu no longer constructs a BookDetailsViewModel.
- [ ] Done

### R14. No static analysis, coverage report or dependency updates

- **Area:** Architecture. **Effort:** Small (under a day). **Confidence:** Confirmed in the repo.
- **Why it matters:** Nothing checks style, common Kotlin mistakes, unused code or test coverage, and dependencies are updated only when someone notices (the AGP update came from an Android Studio pop-up). With many agents writing code, a linter is the cheapest reviewer.
- **Evidence:** No detekt, ktlint, spotless, kover, dependabot or renovate configuration in the repo.
- **What to do:** Add detekt (with a baseline so it starts green) and Kover (report only, no threshold at first) to CI. Add Dependabot for Gradle and GitHub Actions, grouped weekly. Add a dependency vulnerability check (the GitHub dependency graph via the Gradle setup action).
- **Done when:** CI reports new detekt issues and coverage; dependency PRs arrive on their own.
- [ ] Done

### R15. No .gitattributes or .editorconfig: the CRLF working tree keeps costing time

- **Area:** Docs and process. **Effort:** Small (under a day). **Confidence:** Confirmed in the repo.
- **Why it matters:** Every plan tells agents to 'edit with Python, encoding utf-8, preserve line endings' and to check for whole-file churn. One edit already corrupted a character in the roadmap. This is a tooling problem with a one-line fix.
- **Evidence:** Neither file exists at the repo root; PROJECT_STATUS 'Windows quirks' section.
- **What to do:** Add .gitattributes ('* text=auto eol=lf', binary rules for png, jks, ttf) and .editorconfig (utf-8, lf, 4 spaces, max line 120 for Kotlin). Renormalise once ('git add --renormalize .') in its own PR while no feature branches are open, then delete the CRLF instructions from the plans and docs.
- **Done when:** A plain editor edit produces a clean diff on Windows; plans no longer mention line endings.
- [ ] Done

### R17. README and ROADMAP no longer match the app

- **Area:** Docs and process. **Effort:** Small (under a day). **Confidence:** Confirmed in the repo.
- **Why it matters:** The README is the public face of a public repo. It does not mention Jellyfin, the Listen widget, favourites, Up next or multi-select, and it points to a GETTING_STARTED file it calls out of date. The roadmap still shows iOS steps 4 and 5 as 'BUILT on branch' and unchecked although they are merged, and the Jellyfin J1 server check as open although Robert has used it.
- **Evidence:** README.md lines 5, 17, 52; docs/ROADMAP.md lines 176 to 190.
- **What to do:** Update the README feature list, platform table and widget list; fix or delete GETTING_STARTED.md; add two or three screenshots. Tick the roadmap items that are merged and add the stages now implied (targetSdk, tester reporting, Jellyfin J2/J3).
- **Done when:** A stranger reading the README gets an accurate picture of version 0.5.3.
- [ ] Done

### R18. Decisions are scattered and several are still open

- **Area:** Docs and process. **Effort:** Small (under a day). **Confidence:** Confirmed in the docs.
- **Why it matters:** Robert's decisions live partly in the status file and partly only in chat (the warm tab bar, Apache-2.0, no in-place mode for the converter, white then amber widgets, the 18 Jellyfin design calls, and the answers of 14 November: Google Play, English only, Audible skipped for now). Open questions have no home, so they are re-asked or forgotten; today the only one left is the font.
- **Evidence:** PROJECT_STATUS 'Decisions (Robert)' has six dated entries, none after 2026-11-01; docs/AUDIBLE.md ends with six unanswered questions.
- **What to do:** Create docs/DECISIONS.md: a dated list (decision, why, who) plus an 'Open decisions' section. Back-fill from the session history. Reference it from CLAUDE.md so agents check it before re-opening a settled question.
- **Done when:** Every settled choice has one dated line; open choices are listed with what they block.
- [ ] Done

### R23. The M4B converter has never encoded a real file, and its tests are not in CI

- **Area:** Verification. **Effort:** Small (under a day). **Confidence:** Confirmed in the repo.
- **Why it matters:** tools/audiobook-to-m4b will be run against Robert's real library. Its 96 tests use stand-ins for ffmpeg; the one real end-to-end test is skipped wherever ffmpeg is missing, which is everywhere so far. The concat, cover and chapter handling follow the ffmpeg documentation only.
- **Evidence:** PROJECT_STATUS Tools section: 'The real encoding has never been run'; no python or ffmpeg step in .github/workflows/android.yml.
- **What to do:** Add a small CI job on ubuntu: install ffmpeg, run 'python -m unittest discover tools/audiobook-to-m4b/tests' so the real end-to-end test runs on every change to tools/. Fix what it finds. Then Robert runs the documented trial ('--apply --limit 3' into a separate folder) and checks the files in Jellyfin.
- **Done when:** The real-ffmpeg test passes in CI; three real books convert and show chapters and covers in Jellyfin.
- [ ] Done

### R24. Accessibility has been designed for but never tested

- **Area:** Product. **Effort:** Medium (one to three days). **Confidence:** Needs a device check.
- **Why it matters:** Content descriptions, live regions and TalkBack actions were added in reviews, but TalkBack has not been run once on a device, and the docs record known clipping at large font sizes in the widgets and a doubled 'More options' announcement.
- **Evidence:** PROJECT_STATUS known limits: 'TalkBack lists More options twice', 'font scale 2.0 can clip the lowest buckets', 'The TalkBack changes have not been checked on a device'.
- **What to do:** Run one TalkBack pass over the main flows (add server, browse, long-press menu, selection mode, player, widgets) and one pass at the largest font and display size. Add the results to DEVICE_CHECKS.md and fix what blocks a task. Add font-scale 2.0 cases to the screenshot tests.
- **Done when:** Every main flow can be completed with TalkBack; nothing essential is clipped at the largest font.
- [ ] Done

## Later

### R16. Every user-facing text is a hard-coded English string

- **Area:** Architecture. **Effort:** Small (under a day). **Confidence:** Confirmed in code.
- **Why it matters:** No screen uses string resources (0 stringResource calls; about 150 literal texts in screens plus many text constants in the domain layer). That is a fair choice for a personal English app, but it is undocumented, it blocks translation, and it makes a wording review impossible without reading code.
- **Evidence:** grep stringResource( in commonMain: 0; androidApp strings.xml has 4 lines.
- **What to do:** Record 'English only' in docs/DECISIONS.md and in CLAUDE.md so no agent starts a string-resource migration. Keep user-facing texts as named constants next to their rules (as most already are) so wording can still be reviewed in one place per area. Nothing else to build.
- **Done when:** The decision is recorded; reviewers stop flagging hard-coded English text.
- **Decision / update:** Robert, 14 Nov: English only. This finding is now only a note to record.
- [ ] Done

### R30. The iOS app has never been launched, yet every feature pays for it

- **Area:** Verification. **Effort:** Medium (one to three days). **Confidence:** Confirmed in the docs.
- **Why it matters:** About 1,900 lines of iOS code plus the whole shared layer compile and link in CI, but the app has never started on a simulator or a phone. Each feature adds iOS risk blind (keyboard behaviour, background tasks, audio session) and every PR waits 15 to 20 minutes for the macOS job. The test device is blocked until Robert has an unmanaged iPhone.
- **Evidence:** CLAUDE.md: 'The iOS app has never run on a simulator or a phone'; CI builds for 'generic/platform=iOS Simulator' but never boots one for the app.
- **What to do:** Use the macOS runner to launch the app for the first time: boot a simulator, install the built app, launch it, wait, take a screenshot and fail on a crash ('xcrun simctl'). Upload the screenshot as an artifact. Later add an XCUITest that adds a mock server and opens the library. Until a device exists, limit new iOS-only work to keeping this green.
- **Done when:** CI proves on every merge that the iOS app starts and shows its first screen.
- **Decision / update:** Robert, 14 Nov: wait for a phone, for now. Do not build the simulator launch check yet; keep iOS compiling and linked in CI and start no new iOS-only work.
- [ ] Done

### R19. Car and cover access trust a package name, and the simulator is trusted in release builds

- **Area:** Security. **Effort:** Small (under a day). **Confidence:** Confirmed in code.
- **Why it matters:** Browsing, starting books and reading covers are allowed to callers whose package name is on a list. A name can be claimed by another app when the real one is not installed. The Desktop Head Unit simulator package is on the list in release builds too. Exposure is small (book titles and covers, never tokens), and it is already documented as a known limit.
- **Evidence:** domain/car/CarRules.kt TRUSTED_CAR_PACKAGES includes 'com.google.android.autosimulator'; CoverProvider checks the calling package name only.
- **What to do:** Check the caller's signing certificate as well as its name (the allow-list pattern from the Android media samples: package plus SHA-256 of the signing certificate). Trust the simulator package only in debuggable builds.
- **Done when:** A look-alike package is refused in a test; the simulator is refused in release builds.
- [ ] Done

### R20. Only one database migration is tested

- **Area:** Correctness. **Effort:** Small (under a day). **Confidence:** Confirmed in code.
- **Why it matters:** The schema is at version 15 with 13 automatic migrations and one hand-written one. Only 8 to 9 has a test. Room checks automatic migrations when it compiles, but nothing proves that real rows (progress, favourites, the queue, downloads) survive the whole chain from an old install.
- **Evidence:** MigrationsTest.kt covers 'migrates from 8 to 9' only; shared/schemas holds 1.json to 15.json.
- **What to do:** Add a test that creates a database at each exported version with a few representative rows, opens it at the current version and checks the rows. Run it for 1, 8, 9, 12 and 14 at least.
- **Done when:** An upgrade from any shipped version is covered by a test that reads data back.
- [ ] Done

### R21. The R8 keep rules are far broader than needed

- **Area:** Architecture. **Effort:** Small (under a day). **Confidence:** Confirmed in code.
- **Why it matters:** The rules keep all of kotlin.**, kotlinx.** and every class under data.remote and data.model. That switches off most shrinking and obfuscation for a large part of the app and hides which classes really need keeping.
- **Evidence:** androidApp/proguard-rules.pro: '-keep class kotlin.** { *; }', '-keep class kotlinx.** { *; }', '-keep class com.chapterplayer.data.remote.** { *; }'.
- **What to do:** Remove the blanket rules and rely on the libraries' own consumer rules (kotlinx.serialization, Ktor, Koin ship them). Keep only what a failing release smoke test proves is needed. Do this after R3 so CI catches a mistake.
- **Done when:** The release APK is smaller and the release smoke test still passes.
- [ ] Done

### R22. Repository housekeeping: old branches, worktrees, one stale PR, missing tags

- **Area:** Docs and process. **Effort:** Small (under a day). **Confidence:** Confirmed in the repo.
- **Why it matters:** Old local branches, empty agent worktree folders, a stale worktree entry, PR #61 untouched since 1 November and no v0.5.x tag made it hard to see which branch still holds unmerged work (exactly one: stage-j/j2). Correction to the first version of this review: it said there were 66 remote branches. That was a count of stale tracking references on the development PC; after a prune the remote had 6.
- **Evidence:** After the clean-up on 14 Nov: remote branches are main and docs/project-review (this review's PR); local adds stage-j/j2; one worktree (stage-j/j2).
- **What to do:** Done on 14 Nov with Robert's approval: PR #61 closed and its branch deleted, the merged remote branch stage-2/book-details deleted, 20 merged local branches deleted, 8 empty worktree folders and the stale worktree entry removed, v0.5.3 tagged at cfd6ed1. Also deleted, with a second approval: the two branches whose PRs were closed without merging (fix/plex-progress-call and stage-1/plugin-settings); '.claude/worktrees/' is in .gitignore. Still to do: turn on 'Automatically delete head branches' in the repository settings.
- **Done when:** Remote branches equal open work; every released version has a tag.
- **Decision / update:** Robert, 14 Nov: approved. Done except one repository setting (see 'What to do').
- [ ] Done

### R25. Tablets, landscape and foldables are untested

- **Area:** Product. **Effort:** Medium (one to three days). **Confidence:** Needs a device check.
- **Why it matters:** Layouts were designed on one phone in portrait. The player adapts its cover to the height, but grids, sheets, the settings pages and the widgets have not been looked at on a wide screen, and the app is not restricted to portrait.
- **Evidence:** No window-size-class handling in ui/; no device check recorded in landscape or on a tablet.
- **What to do:** Add tablet and landscape cases to the screenshot tests. Use a two-pane layout for Library plus details on wide screens, cap content width on settings and sheets, and check the player in landscape.
- **Done when:** Screenshots exist for a phone in landscape and a 10 inch tablet; nothing is stretched or clipped.
- [ ] Done

### R26. Common listening options are missing

- **Area:** Product. **Effort:** Medium (one to three days). **Confidence:** Confirmed in code.
- **Why it matters:** Compared with Audible, the Audiobookshelf app and AudioBooth, the player lacks options many listeners expect: the skip lengths are fixed at 15 and 30 seconds, speed is one global setting and is not remembered per book, and there is no skip silence, volume boost, or shake to extend the sleep timer.
- **Evidence:** No skip-length, per-book speed, skipSilence, volumeBoost or shake setting in the source; PlaybackStarter applies settings.playbackSpeed to every book.
- **What to do:** In order of value: remember speed per book (with the global value as default), let the user choose skip back and skip forward lengths (used by the player, notification, widgets, car and headset), then shake or tap to extend the sleep timer. Skip silence and volume boost are Android-only (ExoPlayer) and can follow.
- **Done when:** Speed follows the book; skip lengths are a setting honoured on every surface.
- [ ] Done

### R27. Data that lives only on the phone cannot be exported or moved

- **Area:** Product. **Effort:** Medium (one to three days). **Confidence:** Confirmed in the docs.
- **Why it matters:** Favourites, the Up next queue, 'not the same book' corrections, listening stats and the position of Plex-only books exist only in the app's database. Android backup carries them to a new Android phone, but there is no way to move them to an iPhone later, to a second device, or to keep a copy.
- **Evidence:** CLAUDE.md: favourites 'local only', Up next 'on this phone only', Plex 'never holds the position of these books'.
- **What to do:** Add Export and Import in Settings: one JSON file (versioned) with favourites, the queue, corrections, settings, stats and local-only progress, matched on import by server address and book id with a title and author fallback. No tokens in the file.
- **Done when:** An export from one install restores those items on another.
- [ ] Done

### R28. No first-run guidance and no 'what's new'

- **Area:** Product. **Effort:** Small (under a day). **Confidence:** Confirmed in code.
- **Why it matters:** A new tester lands on an empty Home with an 'Add a server' button. Nothing explains the three kinds of server, that Jellyfin needs a Books library, or which widgets exist; after an update nothing says what changed, and testers were told by message to remove and re-add widgets.
- **Evidence:** Empty states route to the Servers page only; no onboarding or changelog screen in ui/.
- **What to do:** Add a short first-run card (three steps: add a server, pick libraries, add a widget) and a 'What's new' sheet shown once per version, fed from CHANGELOG.md.
- **Done when:** A first launch explains the next step; an update shows its changes once.
- [ ] Done

### R29. Notifications still use Android's placeholder icons

- **Area:** Product. **Effort:** Small (under a day). **Confidence:** Confirmed in code.
- **Why it matters:** The download notification uses the system download icon and the 'Tap to keep listening' notice uses the system play icon. The app has its own icon with a monochrome layer; using it makes the notifications recognisably ChapterPlayer's.
- **Evidence:** WorkManagerDownloads.kt: setSmallIcon(android.R.drawable.stat_sys_download); WidgetPlay.kt: setSmallIcon(android.R.drawable.ic_media_play).
- **What to do:** Add a small monochrome notification icon drawn from the launcher's foreground shape and use it for the media, download and notice notifications.
- **Done when:** All three notifications show the app's own small icon.
- [ ] Done

## Suggested order of work

**1. Safety net (small, do together)**

- R3: CI never builds the release (R8) build and runs no lint
- R9: PROJECT_STATUS.md is stale and contradicts itself
- R17: README and ROADMAP no longer match the app
- R15: No .gitattributes or .editorconfig: the CRLF working tree keeps costing time
- R14: No static analysis, coverage report or dependency updates
- R22: Repository housekeeping: old branches, worktrees, one stale PR, missing tags

**2. Correctness**

- R4: Restoring from a phone backup can leave 'Downloaded' books that cannot play
- R6: Jellyfin stage 2 is half built and stopped; 0.5.3 never sends progress to Jellyfin
- R11: A password can be sent over plain http with no warning
- R20: Only one database migration is tested

**3. Testers and releases**

- R7: Release testers cannot report a problem: no crash capture, Diagnostics is debug-only
- R12: Releases are built by hand on one PC and are not reproducible
- R1: Most features since Stage 2 have never been checked on a device
- R23: The M4B converter has never encoded a real file, and its tests are not in CI

**4. Agent efficiency**

- R8: CLAUDE.md is 78 KB and is loaded into every session and every subagent
- R18: Decisions are scattered and several are still open

**5. Bigger engineering (R5 and R31 are now required for Google Play)**

- R5: targetSdk is 34 while the app compiles against 37
- R31: Nothing is in place yet for a Google Play release
- R2: There are no UI tests and no screenshot tests
- R10: The merged library is recomputed separately by every screen and service

**6. When there is room**

- R13: A few classes have grown to do several jobs
- R16: Every user-facing text is a hard-coded English string
- R19: Car and cover access trust a package name, and the simulator is trusted in release builds
- R21: The R8 keep rules are far broader than needed
- R24: Accessibility has been designed for but never tested
- R25: Tablets, landscape and foldables are untested
- R26: Common listening options are missing
- R27: Data that lives only on the phone cannot be exported or moved
- R28: No first-run guidance and no 'what's new'
- R29: Notifications still use Android's placeholder icons
- R30: The iOS app has never been launched, yet every feature pays for it

## Decisions (Robert)

1. **How will the app be distributed: sideloaded APKs only, or Google Play as well?** Decides how urgent the targetSdk move (R5) is and adds the Play work (R31). **Answered (14 Nov): It will go on Google Play.**
2. **Should Jellyfin stage 2 be resumed now? It was stopped part-way.** Until it lands, Jellyfin never receives listening progress (R6). **Answered (14 Nov): It will be resumed on 15 November.**
3. **May merged branches and finished worktrees be deleted, and PR #61 closed?** Housekeeping (R22). **Answered (14 Nov): Yes. Done on 14 November; stage-j/j2 was kept.**
4. **Is a 'Report a problem' row that shares a redacted text report acceptable in release builds?** Gives outside testers a way to send evidence (R7). **Answered (14 Nov): Yes.**
5. **English only, or should the app be translatable?** Settles R16. **Answered (14 Nov): English only.**
6. **iOS: wait for an unmanaged iPhone, or have CI launch the app on a simulator now?** R30. **Answered (14 Nov): Wait for a phone, for now.**
7. **Which font: stay with Playfair Display and DM Sans, or one of the five alternatives shown?** Affects widgets and long titles. Nothing is blocked; the app keeps its current fonts until decided. **Still open.**
8. **The six questions at the end of docs/AUDIBLE.md.** Nothing Audible-related is built until they are answered. 'Do nothing' is a valid answer. **Answered (14 Nov): Skip Audible for now: build nothing Audible-related. For when it is reopened: purchases only (no Plus titles), marketplace audible.com.au, the Amazon data export 'maybe later'.**
9. **Delete the two branches whose PRs were closed without merging (fix/plex-progress-call and stage-1/plugin-settings)?** They were not covered by 'merged branches', so they were left in place at first (R22). **Answered (14 Nov): Yes. Deleted on 14 November; their commits (40481b5 and e332f92) stay reachable through the closed PRs #68 and #17.**

## What this review did not cover

- Nothing was run on a device or an emulator for this review; findings marked 'Needs a device check' say so.
- The iOS code was read for structure only (it has never run).
- No line-by-line audit of every file: the review sampled the largest classes, the build, CI, the manifest, the database and the docs.
