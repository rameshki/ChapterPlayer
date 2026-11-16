# ChapterPlayer project review, 17 November 2026

Reviewed at `main` f13a7a3 (version 0.5.3). It follows the review of 14 November 2026 (`docs/reviews/2026-11-14-project-review.md`) and replaces it as the list of work: 23 pull requests have merged since, 14 of its 31 findings are finished, 17 are carried over here (4 partly done, 13 not started) and 10 are new. Findings keep their ids; new ones start at R32.

## How to use this file (for agents)

- Each finding has an id, a priority, a status, the evidence it rests on, what to do and a 'done when' test. Take one finding or one batch per branch and PR.
- Check that a finding's evidence is still true on the current `main` before you build on it. If it is not, say so and stop.
- 'Confidence' says how the finding was established. 'Needs a device check' or 'likely' means it was read or inferred, not observed.
- A finding's 'Decision / update' line is Robert's answer and overrides the text above it. Do not start work that depends on a question still open with him.
- Merge only when every CI job has passed. When a finding is done, record it in `docs/PROJECT_STATUS.md` and tick it here.

## The project in numbers

| | |
|---|---|
| App code | 47,500 lines of Kotlin in 427 files (shared, Android, iOS) |
| Test code | 48,200 lines; 3,202 host tests, all passing on `main` on 17 November |
| Outstanding findings | 27: 6 to do first, 12 to do next, 9 later |
| Since 14 November | 23 pull requests merged; 14 of 31 findings done; 10 new findings |

## What is strong (keep doing it)

- The rules are pure code with tests: 3,202 host tests, about 48,000 lines of test code against 47,500 lines of app code.
- Independent review keeps paying: this week it found 15 defects in Mark as finished, a crash trace that lost its class names, a library name that could leak into a report, and a way for one failed read to crash the app. None reached a phone.
- CI now builds what testers get: the minified release APK, lint against a baseline, and a check that R8 keeps the widget classes.
- An upgrade from every database version ever shipped is tested with real rows.
- Token handling stayed disciplined through 23 pull requests: tokens only in headers; reports and crash traces pass through one redaction rule with tests that try to smuggle a secret through.
- The phone is in the loop: wireless debugging with numbered debug builds, and the Diagnostics page settled the Audiobookshelf question in minutes with facts from the server.
- Decisions have a home (docs/DECISIONS.md), and the work order format carried 14 findings from review to merge in three days.
- After one bad merge the process changed the same day: every merge since has waited for both CI jobs.

## Summary of outstanding findings

| Id | Priority | Status | Area | Effort | Finding |
|---|---|---|---|---|---|
| R32 | Do first | New | Verification | S | One Mark as finished test fails at random in CI and blocks unrelated pull requests |
| R33 | Do first | New | Docs and process | S | Only one of the two CI jobs is required, and merges are gated by hand-written scripts |
| R34 | Do first | New | Verification | S | Every change waits for the full 25 minute pipeline, and GitHub's macOS queue stalls merges |
| R35 | Do first | New | Correctness | M | Mark as finished and 'play again' changed what three servers are told, and the last fixes were not reviewed |
| R36 | Do first | New | Correctness | M | Playback stops in the night on Robert's phone and nothing records why |
| R1 | Do first | Partly done | Verification | M | Most device checks are still open, and the list is not yet a table with results |
| R12 | Do next | Not started | Verification | M | Releases are still built by hand on one PC, and there is no changelog a tester could read |
| R41 | Do next | New | Product | M | There is no synthetic library to show or test the app with |
| R31 | Do next | Partly done | Product | L | Google Play: the paperwork is drafted, the release itself has not started |
| R14 | Do next | Partly done | Architecture | S | Dependency updates arrive but have no rule, vulnerability alerts are off, and there is still no static analysis |
| R2 | Do next | Partly done | Verification | L | Screenshot tests work but cover one small screen and do not run in CI |
| R39 | Do next | New | Product | L | Jellyfin books cannot be downloaded, and its fixtures are still guesses |
| R11 | Do next | Not started | Security | S | A password can be sent over plain http with no warning |
| R37 | Do next | New | Docs and process | S | The status file went stale again within a day, and every pull request collides in it |
| R38 | Do next | New | Docs and process | M | The area files are single paragraphs of up to 14,000 characters |
| R23 | Do next | Not started | Verification | S | The M4B converter has never encoded a real file, and its tests are not in CI |
| R24 | Do next | Not started | Product | M | Accessibility has been designed for but never tested |
| R13 | Do next | Not started | Architecture | L | The largest classes grew again this week |
| R40 | Later | New | Docs and process | S | Limits accepted in this week's reviews are written down only in pull requests and chat |
| R21 | Later | Not started | Architecture | S | The R8 keep rules are far broader than needed |
| R19 | Later | Not started | Security | S | Car and cover access trust a package name, and the simulator is trusted in release builds |
| R29 | Later | Not started | Product | S | Notifications still use Android's placeholder icons |
| R26 | Later | Not started | Product | M | Common listening options are missing |
| R25 | Later | Not started | Product | M | Tablets, landscape and foldables are untested |
| R27 | Later | Not started | Product | M | Data that lives only on the phone cannot be exported or moved |
| R28 | Later | Not started | Product | S | No first-run guidance and no 'what's new' |
| R30 | Later | Not started | Verification | M | The iOS app has never been launched, yet every change pays for it |

## Suggested order of work

**1. CI you can trust (small, do together)**

- R32: One Mark as finished test fails at random in CI and blocks unrelated pull requests
- R33: Only one of the two CI jobs is required, and merges are gated by hand-written scripts
- R34: Every change waits for the full 25 minute pipeline, and GitHub's macOS queue stalls merges
- R37: The status file went stale again within a day, and every pull request collides in it

**2. Prove what shipped this week**

- R35: Mark as finished and 'play again' changed what three servers are told, and the last fixes were not reviewed
- R36: Playback stops in the night on Robert's phone and nothing records why
- R1: Most device checks are still open, and the list is not yet a table with results

**3. A release pipeline, then Google Play**

- R12: Releases are still built by hand on one PC, and there is no changelog a tester could read
- R41: There is no synthetic library to show or test the app with
- R14: Dependency updates arrive but have no rule, vulnerability alerts are off, and there is still no static analysis
- R31: Google Play: the paperwork is drafted, the release itself has not started

**4. Tests that see the screen**

- R2: Screenshot tests work but cover one small screen and do not run in CI
- R24: Accessibility has been designed for but never tested
- R25: Tablets, landscape and foldables are untested

**5. Jellyfin and sign-in safety**

- R39: Jellyfin books cannot be downloaded, and its fixtures are still guesses
- R23: The M4B converter has never encoded a real file, and its tests are not in CI
- R11: A password can be sent over plain http with no warning

**6. Structure and upkeep**

- R38: The area files are single paragraphs of up to 14,000 characters
- R40: Limits accepted in this week's reviews are written down only in pull requests and chat
- R13: The largest classes grew again this week
- R21: The R8 keep rules are far broader than needed
- R19: Car and cover access trust a package name, and the simulator is trusted in release builds

**7. When there is room**

- R29: Notifications still use Android's placeholder icons
- R26: Common listening options are missing
- R27: Data that lives only on the phone cannot be exported or moved
- R28: No first-run guidance and no 'what's new'
- R30: The iOS app has never been launched, yet every change pays for it

## Decisions

Open (each blocks the finding named):

- Which Google Play account, and when: personal (needs 12 testers for 14 days) or organisation? Blocks the Play release itself (R31). Robert said 'not just yet' about paying.
- Build version 0.5.4 for the testers now, or after the device checks? 24 pull requests have merged since 0.5.3 (R12, R1).
- Should the app ask for the battery exemption itself, or only explain the phone setting? Playback was killed overnight (R36). Play restricts the permission for asking in-app.
- May both CI jobs be made required, with auto-merge on and no bypass? Stops a merge with a red job without relying on scripts (R33). These are repository settings.
- Turn on Dependency graph and Dependabot alerts in the repository settings? Without it the vulnerability check added on 15 Nov cannot report anything (R14).
- Dependency updates: merge patch and minor groups after green CI and one install on the phone; majors separately? PR #114 with 18 updates is waiting (R14).
- Is Android Auto offered at launch on Play? Play reviews Auto apps separately (R31, R19).
- Is it acceptable that Audiobookshelf's 'finished at' time becomes the time of the mark? A side effect of finishing a book at 0:00 (R35).
- After a book ends, should a play report from earlier in the book start reporting again (as now), or stay silent? Asked on 15 Nov during Jellyfin stage 2; unanswered. Kept as 'report again' so listening after a seek back is not lost.
- Keep the sideload APKs for outside testers once Play testing starts? Decides whether the GitHub Release path (R12) stays after launch.
- detekt: try the 2.x alpha now, or wait for a stable release? Static analysis (R14).

Answered since the last review:

- Which font? **The user picks: Classic stays the default, plus Bookish, Inter, Manrope, Lexend and System.** (15 Nov)
- What should Mark as finished do to the place? **Set the flag and return the book to 0:00. Playing it again makes it not finished, on the phone and the servers.** (16 Nov)
- Should each build sent to the phone be identifiable? **Yes: debug builds count up (0.5.3-debug.N).** (16 Nov)
- Distribution, English only, Report a problem, Audible, iOS **Google Play; English only; Report a problem approved; Audible skipped for now; iOS waits for an unmanaged phone.** (14 Nov)

## Done since 14 November

| Id | What | Merged | What is left |
|---|---|---|---|
| R3 | CI builds the release build, runs lint and checks the R8 kept classes | #93 | Nothing. |
| R4 | A restored backup no longer shows books as downloaded | #94 | Device check: back up, uninstall, restore (in R1). |
| R5 | targetSdk 36, the six hour download limit, refused audio focus | #101 | Device checks on Android 15 and 16 (in R1). |
| R6 | Jellyfin stage 2: progress, queue, marks, three-copy mirroring | #96 | Checked on the phone on 16 Nov: progress and marks. Left: resume band, offline delivery, recorded fixtures (R1, R39). |
| R7 | Report a problem in release builds, crash trace saved, Diagnostics by long press | #98 | Not yet seen on a phone (R1). The event log is thin and lost when the app is killed (R36). |
| R8 | CLAUDE.md cut from 78 KB to 6 KB with a map of area files | #95 | The area files themselves are still walls of text (R38). |
| R9 | PROJECT_STATUS.md rewritten; history, decisions and device checks split out | #97 | It drifted again in two days (R37); corrected in this review's pull request. |
| R10 | One shared merged library for all 13 readers | #115 | Not measured on a phone. Mirroring still groups by itself (R40). |
| R15 | .gitattributes and .editorconfig, LF everywhere | #103 | Nothing. |
| R16 | English only, recorded as a decision | #110 | Nothing. |
| R17 | README and roadmap match the app | #100 | Screenshots, which need a synthetic library (R41). |
| R18 | Dated decision log and open decisions in docs/DECISIONS.md | #110 | Keep it current; updated in this review's pull request. |
| R20 | Migration tests from every exported schema version | #106 | Nothing. |
| R22 | Housekeeping: branches, worktrees, tags, auto-delete of merged branches | settings | Nothing. Six finished worktrees were removed on 17 Nov. |
| - | Font choice (Classic, Bookish, Inter, Manrope, Lexend, System) | #92 | Look at each font on the phone (R1). |
| - | Mark as finished returns the book to 0:00; playing it again makes it not finished | #108, #109 | Review and real-server check (R35). |
| - | Debug builds carry a build number (0.5.3-debug.N) and an install script | #111 | Nothing. |

## Findings: do first

### R32. One Mark as finished test fails at random in CI and blocks unrelated pull requests

- **Area:** Verification. **Effort:** Small (under a day). **Status:** New. **Confidence:** Seen in CI; cause likely, not proven.
- **Why it matters:** The test 'marking a book finished rewinds the player holding another copy and both copies start over' failed on three CI runs on 16 and 17 November, including a pull request that changed only a workflow file. It passes on the development PC every time. Each failure costs a 20 minute re-run and hides whether a change really broke something.
- **Evidence:** CI runs 43908904911 (twice) and 43916327591: 'BookRepositoryMarkFinishedTest ... FAILED, AssertionError at BookRepositoryMarkFinishedTest.kt:543'. Line 543 is the test's runTest line, which is all Gradle prints for a failure inside the test body. Likely cause, read from the code: the test puts both copies on ONE mock server with one shared record and a plain list of requests; markFinished sends to both copies in parallel on Dispatchers.Default, so on the two-core CI machine the two request pairs interleave and one copy's 'not finished' step undoes the other's finish. In the app two copies of a book are never on the same server, so this is a fault in the test, not in the app. Assertion messages were added on 17 November so the next failure names the value.
- **What to do:** Give the mock server one record per book id and make its request list thread-safe (or put the two copies on two mock servers, as in the app). Check the other tests that use the same mock with two copies. Run the class 50 times with two worker threads to prove the fix. Upload the test report when a CI job fails (see R34) so the next flaky test can be read.
- **Done when:** The test class passes 50 runs in a row under a two-core limit; no CI run fails on it for a week.
- [ ] Done

### R33. Only one of the two CI jobs is required, and merges are gated by hand-written scripts

- **Area:** Docs and process. **Effort:** Small (under a day). **Status:** New. **Confidence:** Confirmed in the repository settings.
- **Why it matters:** On 15 November a pull request (#106) was merged while its iOS job was failing; the iOS test build on main stayed broken until #107. The branch rule did not stop it, because only the Android job is a required check. Since then every merge has been gated by a shell loop written in the session. A loop is easy to get wrong, and it lives in no file.
- **Evidence:** gh api .../branches/main/protection: required checks = ['Build & unit test'] only; enforce_admins = false; required approving reviews = 0. Repository: allow_auto_merge = false. PR #106 merged with 'iOS framework & app shell' failed; fix in #107.
- **What to do:** In the repository settings: add 'iOS framework & app shell' as a required check, turn on 'Allow auto-merge', and turn on 'Do not allow bypassing the above settings'. Then merge with 'gh pr merge N --auto --squash' and delete the polling loops from the process notes. Do this together with R34, so a pull request that does not touch iOS is not held up by the macOS queue (a skipped job counts as passed for a required check).
- **Done when:** A pull request with any red or pending job cannot be merged by anyone; merging is one command with no script.
- **Decision / update:** The settings are Robert's to change (or to ask Claude to change through the API).
- [ ] Done

### R34. Every change waits for the full 25 minute pipeline, and GitHub's macOS queue stalls merges

- **Area:** Verification. **Effort:** Small (under a day). **Status:** New. **Confidence:** Confirmed in the workflow and in CI.
- **Why it matters:** A change to one Markdown file runs the Android build, the release build, lint, 3,202 tests and the whole iOS job. Main is strict, so each pull request must be rebased and re-run after the one before it: 23 pull requests in three days spent most of their time waiting. The macOS job was cancelled five times at exactly 15 minutes because GitHub never gave it a machine. When a test fails, CI keeps only a one-line summary, so the cause cannot be read.
- **Evidence:** .github/workflows/android.yml: 'on: pull_request' with no path filter; no upload of test reports; 'cancel-in-progress: true' also on main, so the main runs for cdf6f14 and c62afa2 were cancelled and never finished. GitHub's note on job 133502831593: 'The job was not acquired by Runner of type hosted even after multiple attempts' (macOS arm64 capacity). The docs-only pull request #110 waited 17 minutes 43 seconds for the macOS job.
- **What to do:** Add a first 'changes' job (dorny/paths-filter or git diff) and give the two jobs an 'if': skip both for docs-only changes; skip iOS unless shared/, iosApp/, gradle files or the workflow changed. Upload shared/build/reports/tests and the Roborazzi outputs with 'if: failure()'. Do not cancel runs on main ('cancel-in-progress' only for pull requests). Put several docs changes in one pull request.
- **Done when:** A docs-only pull request is mergeable in under two minutes; a failed test's full report can be downloaded from the run; every commit on main has a finished CI run.
- [ ] Done

### R35. Mark as finished and 'play again' changed what three servers are told, and the last fixes were not reviewed

- **Area:** Correctness. **Effort:** Medium (one to three days). **Status:** New. **Confidence:** Partly seen on a phone; real-server behaviour partly assumed.
- **Why it matters:** On 16 November Mark as finished was changed to return the book to 0:00, and playing a finished book again now marks it not finished on every server. Four review rounds raised 16 points, several of which would have lost a listening place; 15 were fixed. The two fixes of the last round were merged on CI alone. What Audiobookshelf really does with the new two-request sequences comes from reading its source code and from a mock, not from a server.
- **Evidence:** PR #109: five commits; the reviews raised 4, 5, 4 and 3 points (one small one was accepted); the last commit (5d6007b) had no review. domain/playback/MarkFinished.kt now holds 16 rules and constants; BookRepositoryImpl.kt grew from 789 to 992 lines. Seen on Robert's phone on 16 November (debug build 2): Jellyfin showed played and unplayed correctly, and after playing again the Audiobookshelf server held 53:28, not finished (read by Diagnostics). Not seen: Audiobookshelf right after Mark as finished, a network drop between the two requests, an offline mark, Plex's played state, a second device.
- **What to do:** (1) One independent review of the whole feature as it now stands, not of a diff. (2) A script that replays the request sequences against a real Audiobookshelf with a throwaway user (finish at 0, play again, a half-sent pair, a mark made offline) and prints what the server stored; save the answers as fixtures. (3) Replace the long paragraph in docs/architecture/progress.md with a table: event, server, requests sent, what the server then holds. (4) Add the rows to DEVICE_CHECKS.md for Plex and for two devices.
- **Done when:** The script's results match the mock for every sequence (or the mock is corrected); the review has no open finding; the table exists and the device rows are filled in.
- **Decision / update:** Robert, 16 Nov: Mark as finished must return the book to 0:00, and playing it again must make it not finished. Still to confirm with him: Audiobookshelf's 'finished at' time becomes the time of the mark.
- [ ] Done

### R36. Playback stops in the night on Robert's phone and nothing records why

- **Area:** Correctness. **Effort:** Medium (one to three days). **Status:** New. **Confidence:** Confirmed on the phone; cause not known.
- **Why it matters:** Robert reported that the app stopped playing two or three times in one night. The phone's own record shows Android killing the app's process while it was playing. That explains one stop; the others are unexplained. The app keeps no record that survives the process, so the next night cannot be explained either. A player that stops by itself fails at its main job, and a Play reviewer or tester will meet the same thing on other Samsung phones.
- **Evidence:** adb shell dumpsys activity exit-info com.chapterplayer.debug: reason SIGNALED, status 9, importance 125 (foreground service) at 03:49 on 16 Nov and 02:49 on 13 Nov; the package is not on the battery-optimisation exemption list. PlaybackService.kt:91 sets WAKE_MODE_NETWORK, so a missing wake lock is not the cause. ProblemLog (the events for 'Report a problem') is in memory only and records only failed HTTP calls (3 call sites).
- **What to do:** (1) Keep a small playback journal on disk (last 200 lines): start, pause with its reason (the person, audio focus, headphones gone, sleep timer, error, service destroyed, book ended) and the place. (2) At start, read Android's own exit reasons for the app (ActivityManager.getHistoricalProcessExitReasons, Android 11 and later) and add both to the 'Report a problem' text. (3) Robert sets the app to Unrestricted battery use / Never sleeping and compares one night. (4) Decide whether the app should ask for the exemption itself or only explain the setting (open decision).
- **Done when:** After a night with a stop, the report says what stopped playback and when; with the battery setting changed, a whole night plays through, or the journal shows what else stopped it.
- [ ] Done

### R1. Most device checks are still open, and the list is not yet a table with results

- **Area:** Verification. **Effort:** Medium (one to three days). **Status:** Partly done. **Confidence:** Confirmed in the docs.
- **Why it matters:** The checks now live in one file, which is the first half of this finding. But it is a prose list with no result, date or build, so nobody can see what was verified. This week added more unchecked behaviour than it checked: targetSdk 36 on Android 15 and 16, the restored-backup fix, Report a problem, six fonts, the shared library. The checks that were run on 16 November found two things no test had (the stale Resume button, the wish for 0:00), which is the usual rate.
- **Evidence:** docs/DEVICE_CHECKS.md: 27 lines, heading 'Still open (as of 2026-11-15)', no table. Run on the S24+ on 16 Nov with debug build 2: Jellyfin progress, Mark as finished and not finished, play again, three copies of one book in Diagnostics. Never run: everything else in that file. Debug builds now carry a number (0.5.3-debug.N), so a result can name its build.
- **What to do:** Turn the file into one table: check, steps, Android version, result, date, build number. Enter the 16 November results. Then run two sessions over wireless debugging. Everyday session: fonts, headless Play from each widget, lock-screen cover, Report a problem and the long press on the version, sleep timer carry-over, Forward 30 near the end, saved filters, multi-select, the keyboard on Add Server. System session: back up, uninstall, restore (R4); the Android 15 and 16 list for targetSdk 36; Wi-Fi to mobile data during a download; reboot with a download queued; Android Auto on the Desktop Head Unit; TalkBack (R24).
- **Done when:** Every row has a result, a date and a build number; rows that failed have a finding or a fix.
- [ ] Done

## Findings: do next

### R12. Releases are still built by hand on one PC, and there is no changelog a tester could read

- **Area:** Verification. **Effort:** Medium (one to three days). **Status:** Not started. **Confidence:** Confirmed in the repo.
- **Why it matters:** The app is still called 0.5.3 although 24 pull requests have merged since that build, with visible changes (fonts, Jellyfin progress, Mark as finished, Report a problem, targetSdk 36). The next tester build would again be a manual zipalign and apksigner run with a key that exists only on Robert's PC. Google Play (R31) needs a signed app bundle built the same way every time.
- **Evidence:** androidApp/build.gradle.kts has no signingConfigs; .github/workflows holds android.yml and dependency-graph.yml only; 'gh release list' is empty; docs/CHANGELOG.md is the old status file's history (95 KB), not a list of changes per version; tags stop at v0.5.3.
- **What to do:** Add a signingConfig that reads the keystore and passwords from Gradle properties or the environment (never the repo). Add a release workflow on a v* tag: build the signed APK and the .aab, attach them to a GitHub Release with the notes for that version. Start a real CHANGELOG.md (one short list per version, written for testers) and rename the current docs/CHANGELOG.md to docs/HISTORY.md. Bump to 0.5.4 and tag it as the first release built this way. Robert backs the key up off the PC and adds it as repository secrets.
- **Done when:** Pushing a tag produces a signed APK and bundle on GitHub with notes; nobody runs apksigner by hand.
- **Decision / update:** Open with Robert: build 0.5.4 for the testers now, or wait for the device checks (R1)?
- [ ] Done

### R41. There is no synthetic library to show or test the app with

- **Area:** Product. **Effort:** Medium (one to three days). **Status:** New. **Confidence:** Confirmed in the repo.
- **Why it matters:** Three pieces of work are blocked by the same gap. The README has no screenshots, the Play listing needs screenshots and two videos, and the CI smoke test (R2) needs a server to add: all of them must show a library, and the only libraries that exist are Robert's real ones, which must never appear in public.
- **Evidence:** README.md has no images; docs/PLAY_LISTING.md: 'Screenshots must use a synthetic library, never the owner's'; docs/architecture/testing.md lists the instrumented smoke test as a later step; fixtures hold JSON only, no audio.
- **What to do:** Build a small demo server in tools/ (a script that answers the Audiobookshelf calls the app uses from the existing fixtures, with invented titles and covers and a few minutes of public-domain or generated audio). Use it for the emulator smoke test in CI, the README and Play screenshots (taken by the screenshot tests where possible) and the two foreground-service videos.
- **Done when:** The app can be installed, given the demo server and shown end to end with no real data; the README has three screenshots.
- [ ] Done

### R31. Google Play: the paperwork is drafted, the release itself has not started

- **Area:** Product. **Effort:** Large (a week or more). **Status:** Partly done. **Confidence:** Confirmed in the repo.
- **Why it matters:** Done since 14 November: targetSdk 36 (Play's current floor for updates), the checklist, a privacy policy draft, the listing text and the Data safety answers. None of the eleven checklist items is ticked, because each needs something that does not exist yet: a developer account, a signed bundle, a public page for the policy, screenshots and videos, a 16 KB-page emulator run, and twelve testers for fourteen days.
- **Evidence:** docs/PLAY_RELEASE.md: 11 unticked items; docs/PRIVACY.md and docs/PLAY_LISTING.md are drafts ('Nothing is submitted'); AndroidManifest still has usesCleartextTraffic=true with no network security config.
- **What to do:** In this order: R12 (signed bundle from CI); publish PRIVACY.md as a web page (GitHub Pages) with a contact address; R41 for screenshots and the two foreground-service videos; run the release APK on the 16 KB-page emulator image; upload to an internal track and read the pre-launch report; then the closed test. Decide before listing whether Android Auto is offered at launch. Re-check each requirement against the Play Console on the day.
- **Done when:** An internal-testing release is live on Play, built by CI from a tag, with the policy forms accepted.
- **Decision / update:** Robert, 14 Nov: the app will go on Google Play. Open: the account type and fee ('not just yet'), Android Auto at launch.
- [ ] Done

### R14. Dependency updates arrive but have no rule, vulnerability alerts are off, and there is still no static analysis

- **Area:** Architecture. **Effort:** Small (under a day). **Status:** Partly done. **Confidence:** Confirmed in the repo and settings.
- **Why it matters:** Dependabot now opens grouped pull requests. The one that is open bundles 18 updates, among them Gradle 9.5 to 9.9 and Navigation 2.9 to 2.10, and a green build does not show a change in behaviour. The dependency list is sent to GitHub on every push but GitHub refuses it, because the repository setting is off, so no vulnerability alert can arrive. detekt and Kover were deferred.
- **Evidence:** PR #114 open since 16 Nov (18 updates, both jobs green). Dependency graph run: 'The Dependency graph is disabled for this repository'; vulnerability-alerts API: 404; dependabot_security_updates: disabled. No detekt or kover in the build.
- **What to do:** Robert turns on Dependency graph and Dependabot alerts (Settings, Advanced Security). Rule for update pull requests: patch and minor groups merge after green CI plus one install on the phone with scripts/install-debug.sh; major versions and the Gradle wrapper come as their own pull requests (change .github/dependabot.yml). Try detekt 2.x with a baseline on a branch; if it does not work with Kotlin 2.4 and AGP 9.3, record that and try again in a month. Add Kover as a report only.
- **Done when:** Alerts are on; #114 is merged or split; the update rule is written in CLAUDE.md; detekt is in CI or has a dated note saying why not.
- [ ] Done

### R2. Screenshot tests work but cover one small screen and do not run in CI

- **Area:** Verification. **Effort:** Large (a week or more). **Status:** Partly done. **Confidence:** Confirmed in code.
- **Why it matters:** The trial settled the tool: Roborazzi on Robolectric works with this project's versions, and a check fails when the screen changes by 2 dp. But only the font picker is covered, the golden images are not compared in CI, and the screens where layout faults keep appearing (the widgets, the player, long titles, large text) are still seen only by eye.
- **Evidence:** PR #113: FontCardScreenshotTest (4 golden images in shared/src/androidHostTest/screenshots) and SelectionTopBarUiTest. .github/workflows/android.yml has no Roborazzi task. docs/architecture/testing.md: goldens recorded on Windows, not yet compared on Linux; full suite estimated at 5 to 8 days.
- **What to do:** First, hours not days: run compareRoborazziAndroidHostTest once on CI to see whether Windows goldens match Linux, then make verifyRoborazziAndroidHostTest the CI test task and upload the differences on failure. Then, in this order: the player and Book details with long titles; Home and Library; Add Server; the three widgets in every size bucket (the uncertain part); each in dark and light at font scale 1.0, 1.3 and 2.0. Last, the emulator smoke test using the demo server (R41).
- **Done when:** CI fails when a golden image changes without review; the widgets, the player and the main lists have images in both themes.
- [ ] Done

### R39. Jellyfin books cannot be downloaded, and its fixtures are still guesses

- **Area:** Product. **Effort:** Large (a week or more). **Status:** New. **Confidence:** Confirmed in code.
- **Why it matters:** Jellyfin is the second source and offline listening is a must-have, but a Jellyfin book has no Download control at all. Stage 3 of the Jellyfin plan (downloads and Quick Connect) is an outline only. The JSON fixtures the Jellyfin tests run against were written from the API description; the real answers of Robert's server were never recorded, although the server has now been used for two days.
- **Evidence:** ServerSource.canDownload() is false for Jellyfin (domain/browse/SourceChoice.kt:99); docs/superpowers/plans/2026-11-08-jellyfin-j1-j2.md 'Stage J3 (outline only)'; plan Tasks 9 and 15 ('record the real JSON of every call, anonymise it, update the fixtures') not done.
- **What to do:** Record the real answer shapes first (a script with a throwaway Jellyfin user, values replaced by invented ones) and fix what differs. Write the J3 plan. Build JellyfinDownloadSource (one file per book, GET /Items/{id}/Download with the header, 403 means the account may not download), then Quick Connect sign-in. Finish the open rows of the J2 phone check (the resume band, the last five minutes, offline listening delivered later).
- **Done when:** A Jellyfin book downloads and plays offline; Quick Connect signs in; the fixtures come from recorded shapes.
- [ ] Done

### R11. A password can be sent over plain http with no warning

- **Area:** Security. **Effort:** Small (under a day). **Status:** Not started. **Confidence:** Confirmed in code.
- **Why it matters:** Audiobookshelf and Jellyfin sign in with a username and password. The form accepts http:// addresses and its placeholder suggests one. That is right for a home network, but nothing tells the user when the address is a public host, where the password and token would travel unencrypted. Play's pre-launch report also flags cleartext.
- **Evidence:** AndroidManifest: usesCleartextTraffic="true"; placeholders 'http://192.168.1.10:...' in AbsSignInSection.kt:109, JellyfinSignInSection.kt:101 and ServerSetupScreen.kt:261 and 293; no 'not encrypted' text anywhere in ui/settings.
- **What to do:** Classify the host with a pure rule (private range, .local, loopback, or public). For http to a public host show a clear warning with 'Use https' and 'Continue anyway'. Show a small 'Not encrypted' note on the Servers page for any http server. Keep cleartext allowed (home servers need it).
- **Done when:** Adding an http server on a public address needs an explicit confirmation; tests cover the host rule.
- [ ] Done

### R37. The status file went stale again within a day, and every pull request collides in it

- **Area:** Docs and process. **Effort:** Small (under a day). **Status:** New. **Confidence:** Confirmed in the repo.
- **Why it matters:** PROJECT_STATUS.md was rewritten on 15 November to be short and true. Two days later it again listed merged work as 'not pushed yet', named done findings as next steps, and listed four findings as both done and in progress. The cause is the process: every pull request adds a line to the same place, so each rebase conflicts (six times in three days) and nobody removes the old lines.
- **Evidence:** docs/PROJECT_STATUS.md at f13a7a3: 'Branch stage-r/r20-migration-tests (R20, not pushed yet)' (merged as #106); 'Remote branches left to delete ... turn on Automatically delete head branches' (done on 16 Nov); Next steps 1 and 2 are R7 and R5 (merged); R7, R15, R17 and R20 appear under both 'Done' and 'In progress'. Corrected in this review's pull request.
- **What to do:** Rule: a feature pull request does not edit PROJECT_STATUS.md; the session that merges updates it once per batch. Keep 'Now' to the version, what is open and pointers (the list of merged work is 'git log'). Add scripts/check-docs.sh to CI: it fails when the status file names a branch that is not on the remote, says 'not pushed', or lists a finding id as both done and open.
- **Done when:** The check runs in CI; the file stays under 4 KB; a week of merges produces no conflict in it.
- [ ] Done

### R38. The area files are single paragraphs of up to 14,000 characters

- **Area:** Docs and process. **Effort:** Medium (one to three days). **Status:** New. **Confidence:** Confirmed in the repo.
- **Why it matters:** Cutting CLAUDE.md (R8) moved each area's text into its own file word for word. Each file is still one or two enormous lines. A one-word change shows as a 14 KB line in the diff, which no reviewer can check, and two branches that touch the same area always conflict. An agent cannot read 'just the mirroring part'. The files also still describe merged work as branches in places.
- **Evidence:** docs/architecture: browse-and-merge.md 8 lines, longest 14,163 characters; widgets.md 9,890; ios.md 9,054; plex.md 7,296; progress.md 5,670. browse-and-merge.md still says 'Selection mode (branch stage-4/multi-select, host-tested only)'.
- **What to do:** Restructure each file, without changing what it says: short sections with headings (what it does, the rules, the main classes, known limits, what was checked on a device), one fact per bullet, lines under about 200 characters. Remove branch names of merged work. Do it in one pull request per file while no feature branch touches that area. Add a line-length check for docs/architecture to scripts/check-docs.sh (R37).
- **Done when:** No line in docs/architecture is longer than 400 characters; a plan can point an implementer to a section, not a file.
- [ ] Done

### R23. The M4B converter has never encoded a real file, and its tests are not in CI

- **Area:** Verification. **Effort:** Small (under a day). **Status:** Not started. **Confidence:** Confirmed in the repo.
- **Why it matters:** tools/audiobook-to-m4b will be run against Robert's real library, and the Jellyfin setup depends on it (one .m4b per book). Its tests use stand-ins for ffmpeg; the one real end-to-end test is skipped wherever ffmpeg is missing, which is everywhere so far.
- **Evidence:** No python or ffmpeg step in .github/workflows; tools/audiobook-to-m4b/tests/test_real_ffmpeg.py holds the one real test; DEVICE_CHECKS.md: 'The audiobook converter's first real trial needs ffmpeg on the machine that holds the library'.
- **What to do:** Add a small CI job on ubuntu, run only when tools/ changes: install ffmpeg, run 'python -m unittest discover tools/audiobook-to-m4b/tests'. Fix what it finds. Then Robert runs the documented trial ('--apply --limit 3' into a separate folder) and checks the files in Jellyfin.
- **Done when:** The real-ffmpeg test passes in CI; three real books convert and show chapters and covers in Jellyfin.
- [ ] Done

### R24. Accessibility has been designed for but never tested

- **Area:** Product. **Effort:** Medium (one to three days). **Status:** Not started. **Confidence:** Needs a device check.
- **Why it matters:** Content descriptions, live regions and TalkBack actions were added in reviews, but TalkBack has not been run once on a device. This week added a long press on the version line and a new card on the About page; the user can now also choose fonts that run wider. Play's pre-launch report includes accessibility findings.
- **Evidence:** DEVICE_CHECKS.md lists TalkBack as open; docs record 'TalkBack lists More options twice' and 'font scale 2.0 can clip the lowest buckets' as known limits; no screenshot test at font scale 2.0.
- **What to do:** Run one TalkBack pass over the main flows (add server, browse, long-press menu, selection mode, player, widgets, Report a problem) and one pass at the largest font and display size in the Lexend and Bookish fonts. Record the results in DEVICE_CHECKS.md and fix what blocks a task. Add font-scale 2.0 cases to the screenshot tests (R2).
- **Done when:** Every main flow can be completed with TalkBack; nothing essential is clipped at the largest font.
- [ ] Done

### R13. The largest classes grew again this week

- **Area:** Architecture. **Effort:** Large (a week or more). **Status:** Not started. **Confidence:** Confirmed in code.
- **Why it matters:** BookRepositoryImpl took all of the Mark as finished and 'play again' logic and is now close to 1,000 lines with more than 26 constructor parameters; it holds details, progress, marks, mirroring, the queue and connection recovery. The 15 defects found in that feature (R35) were mostly interactions inside this one class. BookDetailsViewModel is still the details page and the engine behind the long-press menu.
- **Evidence:** wc -l on 17 Nov: BookRepositoryImpl 992 (789 on 14 Nov), BookDetailsViewModel 892, SettingsViewModel 814, EnginePlaybackController 737, PlayerScreen 663, ServerSetupScreen 620, JellyfinLibrarySource 532. 'quickActions' mode of BookDetailsViewModel is still used by the long-press menu.
- **What to do:** Start with the class that hurt this week: move marks and the 'play again' rules out of BookRepositoryImpl into a MarkFinished class with its own tests, then progress saving and mirroring into a second class; the repository keeps details and delegation. Then the shared actions (PlayBook, DownloadBook, ToggleFavourite, EditUpNext) out of BookDetailsViewModel. One class per pull request, tests unchanged. Do it after R35's review, not before.
- **Done when:** No main class over about 400 lines; the long-press menu no longer constructs a BookDetailsViewModel.
- [ ] Done

## Findings: later

### R40. Limits accepted in this week's reviews are written down only in pull requests and chat

- **Area:** Docs and process. **Effort:** Small (under a day). **Status:** New. **Confidence:** Confirmed in the pull requests.
- **Why it matters:** Each review ended with a few points that were judged acceptable and left. They are real behaviour that a later session will rediscover as a bug, or build on without knowing.
- **Evidence:** Left as they are: a saved crash trace is never cleared, so an old crash is reported for ever (R7 review); 'Report a problem' has no crash capture on iOS; progress mirroring still groups copies by itself, apart from the shared library (R10); a one-second play of a finished book leaves a session open on the server (#109); if the network drops between the two Audiobookshelf requests the book is left not finished at 0:00 until marked again (#109); a mark made offline loses to another device's newer change (#109); a list read by the car or a widget can be a few milliseconds behind a write (R10); screenshot tests draw at Android 14 because Robolectric needs Java 21 for newer versions (R2).
- **What to do:** Add a 'Known limits' section to each area file (R38 gives it a place) with one line per limit and the pull request it came from. At each project review, decide for each line: fix, accept, or drop.
- **Done when:** Every limit above has one line in an area file; none exists only in a pull request.
- [ ] Done

### R21. The R8 keep rules are far broader than needed

- **Area:** Architecture. **Effort:** Small (under a day). **Status:** Not started. **Confidence:** Confirmed in code.
- **Why it matters:** The rules keep all of kotlin.**, kotlinx.** and every class under data.remote and data.model. That switches off most shrinking and obfuscation for a large part of the app. Now that CI builds the release APK and checks the kept classes (R3, done), a mistake here would be caught.
- **Evidence:** androidApp/proguard-rules.pro lines 6, 7, 18 and 19: '-keep class com.chapterplayer.data.remote.** { *; }', '...data.model.**', '-keep class kotlin.** { *; }', '-keep class kotlinx.** { *; }'.
- **What to do:** Remove the blanket rules and rely on the libraries' own consumer rules (kotlinx.serialization, Ktor and Koin ship them). Keep only what a failing release build or the emulator smoke test (R2) proves is needed. Compare APK sizes.
- **Done when:** The release APK is smaller; scripts/check-r8-seeds.sh and the smoke test still pass.
- [ ] Done

### R19. Car and cover access trust a package name, and the simulator is trusted in release builds

- **Area:** Security. **Effort:** Small (under a day). **Status:** Not started. **Confidence:** Confirmed in code.
- **Why it matters:** Browsing, starting books and reading covers are allowed to callers whose package name is on a list. A name can be claimed by another app when the real one is not installed. The Desktop Head Unit simulator is on the list in release builds too. Exposure is small (titles and covers, never tokens). It matters more once the app is on Play with Android Auto.
- **Evidence:** domain/car/CarRules.kt:23 and CarSession.kt:55 include 'com.google.android.autosimulator'; CoverProvider.kt:43 checks the calling package name from getPackagesForUid only.
- **What to do:** Check the caller's signing certificate as well as its name (package plus SHA-256 of the certificate, as in the Android media samples). Trust the simulator package only in debuggable builds.
- **Done when:** A look-alike package is refused in a test; the simulator is refused in release builds.
- [ ] Done

### R29. Notifications still use Android's placeholder icons

- **Area:** Product. **Effort:** Small (under a day). **Status:** Not started. **Confidence:** Confirmed in code.
- **Why it matters:** The download notification, the new 'Downloads paused' notice and 'Tap to keep listening' use system icons. The app has its own icon with a monochrome layer; using it makes the notifications recognisably ChapterPlayer's, which the Play listing videos will show.
- **Evidence:** setSmallIcon(android.R.drawable...) in WorkManagerDownloads.kt:171, DownloadTimeLimitHold.kt:82 and WidgetPlay.kt:114.
- **What to do:** Add a small monochrome notification icon drawn from the launcher's foreground shape and use it for the media, download and notice notifications.
- **Done when:** Every notification shows the app's own small icon.
- [ ] Done

### R26. Common listening options are missing

- **Area:** Product. **Effort:** Medium (one to three days). **Status:** Not started. **Confidence:** Confirmed in code.
- **Why it matters:** Compared with Audible, the Audiobookshelf app and AudioBooth, the player lacks options many listeners expect: skip lengths are fixed at 15 and 30 seconds, speed is one global setting and is not remembered per book, and there is no skip silence, volume boost, or shake to extend the sleep timer.
- **Evidence:** No skip-length, per-book speed, skipSilence, volumeBoost or shake setting in the source (searched on 17 Nov).
- **What to do:** In order of value: remember speed per book (the global value as default), let the user choose skip back and skip forward lengths (used by the player, notification, widgets, car and headset), then shake or tap to extend the sleep timer. Skip silence and volume boost are Android-only and can follow.
- **Done when:** Speed follows the book; skip lengths are a setting honoured on every surface.
- [ ] Done

### R25. Tablets, landscape and foldables are untested

- **Area:** Product. **Effort:** Medium (one to three days). **Status:** Not started. **Confidence:** Needs a device check.
- **Why it matters:** Layouts were designed on one phone in portrait. With targetSdk 36, Android 16 ignores an app's wish to stay in portrait on large screens, so the app will be shown in landscape and split screen whether or not it was designed for it.
- **Evidence:** No window-size-class handling in ui/ (searched on 17 Nov); DEVICE_CHECKS.md lists the large-screen check as open.
- **What to do:** Add tablet and landscape cases to the screenshot tests (R2). Cap content width on settings pages and sheets, check the player in landscape, then consider a two-pane layout for Library plus details on wide screens.
- **Done when:** Screenshots exist for a phone in landscape and a 10 inch tablet; nothing is stretched or clipped.
- [ ] Done

### R27. Data that lives only on the phone cannot be exported or moved

- **Area:** Product. **Effort:** Medium (one to three days). **Status:** Not started. **Confidence:** Confirmed in the docs.
- **Why it matters:** Favourites, the Up next queue, 'not the same book' corrections, listening statistics, the font and filter choices and the position of Plex-only books exist only in the app's database. Android backup carries them to a new Android phone, but there is no way to move them to an iPhone later or to keep a copy.
- **Evidence:** docs/architecture/browse-and-merge.md: 'Favourites (local only ...)'; playback.md: Up next is 'an ordered queue kept on this phone'; plex.md: 'Plex never holds the position of these books'. No export or import in the source.
- **What to do:** Add Export and Import in Settings: one versioned JSON file with favourites, the queue, corrections, settings, statistics and local-only progress, matched on import by server address and book id with a title and author fallback. No tokens in the file.
- **Done when:** An export from one install restores those items on another.
- [ ] Done

### R28. No first-run guidance and no 'what's new'

- **Area:** Product. **Effort:** Small (under a day). **Status:** Not started. **Confidence:** Confirmed in code.
- **Why it matters:** A new tester lands on an empty Home with an 'Add a server' button. Nothing explains the three kinds of server or that Jellyfin needs a Books library. After an update nothing says what changed: this week alone changed what Mark as finished does, and a tester would have to be told by message.
- **Evidence:** Empty states route to the Servers page only; no onboarding or changelog screen in ui/ (searched on 17 Nov).
- **What to do:** Add a short first-run card (add a server, pick libraries, add a widget) and a 'What's new' sheet shown once per version, fed from the CHANGELOG.md that R12 starts.
- **Done when:** A first launch explains the next step; an update shows its changes once.
- [ ] Done

### R30. The iOS app has never been launched, yet every change pays for it

- **Area:** Verification. **Effort:** Medium (one to three days). **Status:** Not started. **Confidence:** Confirmed in the docs.
- **Why it matters:** About 1,900 lines of iOS code plus the whole shared layer compile and link in CI, but the app has never started on a simulator or a phone. This week the iOS job caught one real fault (a test name Kotlin/Native rejects) and cost roughly 17 minutes on each of 23 pull requests, plus five stalls in GitHub's macOS queue (R34).
- **Evidence:** docs/architecture/ios.md: 'The iOS app has never run on a simulator or a phone'; CI builds for 'generic/platform=iOS Simulator' but never boots one for the app.
- **What to do:** Unchanged: when Robert has an unmanaged iPhone, or decides sooner, use the macOS runner to boot a simulator, install the app, launch it, take a screenshot and fail on a crash. Until then keep iOS compiling and linked, start no new iOS-only work, and let R34 stop docs and Android-only changes from waiting on it.
- **Done when:** CI proves on every merge that the iOS app starts and shows its first screen.
- **Decision / update:** Robert, 14 Nov: wait for a phone, for now. No simulator launch check yet.
- [ ] Done

## How a work order item is built

The review page turns each ticked finding into the same block of text for the agents. The parts, and why each is there:

| Part | What it holds | Why it is there |
|---|---|---|
| Header line | The review date, the commit of main and the version the evidence was taken from, and the path of the full review. | An agent starts with no memory of the review. The commit tells it how old the evidence is, so it checks what changed since before it builds on a finding. |
| Standing instruction | One finding (or one closely related group) per branch and pull request; a plan, an independent review and CI; record the result in the status file. | The process is repeated in every order so that it does not depend on the agent having read the process notes. One finding per pull request keeps a review small enough to be real. |
| [ID] and title | A stable id (R32) and one sentence that states the problem, not the fix. | The id is used in branch names, commits, pull requests and the status file, so work can be traced back to its reason. A problem statement lets the agent propose a better fix than the one suggested. |
| Priority, area, effort, confidence | Do first / Do next / Later; the part of the project; S, M or L; and how the finding was established. | Effort decides whether the task needs a written plan and how many agents. Confidence tells the agent how much to check first: 'Confirmed in code' can be acted on, 'Needs a device check' or 'cause likely, not proven' must be confirmed before anything is built. |
| Why | What goes wrong for the user, the owner or the next agent if nothing is done. | It is the outcome the work serves. When the agent meets a choice the order did not foresee, this is what it decides by, and it is how it avoids solving a neighbouring, easier problem. |
| Evidence | File names with line numbers, the exact command and what it printed, run ids, dates. | It can be checked in a minute. If the evidence is no longer true, the finding is stale and the agent says so, with no work done. It also shows where to start reading. |
| Do | The recommended approach in steps, the order, the traps, and what is somebody else's to do (a setting only Robert can change). | Specific enough that two agents would build the same thing, but it stops before code: the agent that reads the files decides the details. Decisions already made are stated so they are not re-opened. |
| Done when | One or two things that can be observed: a command that passes, a state on a server, a row filled in. | It defines finished before the work starts. The implementer runs it, the reviewer checks it, and 'the code is written' cannot be reported as done. |
| Decision / update | Robert's own words on the finding, with the date, and any question still open with him. | His answer overrides the text above it. An open question tells the agent which part must wait. |

Rules the items follow:

- Each item stands alone. Any subset can be pasted, in any order, and nothing refers to 'the item above'.
- The text is pasted, not linked, because a fresh session may not open the right file, and a link cannot be ticked in parts.
- One problem per item. If two problems share a fix they are still two items in the same batch, so each keeps its own 'done when'.
- An item is sized for one pull request. Anything larger names its first step (as R2 and R31 do).
- Priority is set by consequence, then by cost. Do first: it can lose data or a listening place, it blocks releases, or it is small and makes every other task safer or faster. Do next: it is needed for a goal already decided (Google Play, Jellyfin) or removes a cost paid every week. Later: an improvement nobody is waiting for.
- Effort is the whole job including review and CI: S under a day, M one to three days, L a week or more.
- Items are ordered by priority, then by id. The batches in 'Suggested order' group items that touch the same files or only pay off together.
- A finding from an earlier review keeps its id for life. 'Partly done' items are rewritten to describe only what is left.
- Every fact in an item was read from the repository, CI or the phone during the review. What was inferred is labelled 'likely'.
