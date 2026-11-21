# Project status

Read this first in every session; it is short on purpose. The list of work is the latest review (`reviews/2026-11-17-project-review.md`). History is in `CHANGELOG.md`, decisions in `DECISIONS.md`, device checks in `DEVICE_CHECKS.md`, design detail in `architecture/`. What has merged is `git log`; do not list it here.

## Now (22 November 2026)
- Version 0.5.4 (versionCode 10, tag `v0.5.4`). Tester builds up to 0.5.4 were shared, signed with the key in `C:\Users\Robert\ChapterPlayerSigning` (outside the repo); that APK predates #127.
- Debug builds are numbered: `scripts/install-debug.sh` installs `0.5.4-debug.N`; Robert's phone has build 10 (20 Nov, main with the screenshot fixes and widget sizes).
- `main` is at #181. About 3,470 host tests pass. targetSdk 36. Sources: Audiobookshelf, Jellyfin (progress, downloads and Quick Connect work on the phone), Plex. Audible is skipped. iOS builds in CI and has never run on a device.
- Done from the 17 November review: R3 to R11, R15 to R18, R20 to R22, R26 (speed per book, skip lengths), R27 (Back up and restore, as a shared text), R28 (first-run guide, What's new), R29, R32 to R34, R37, R38, R40. None of R26, R27, R28 has been seen on a phone yet; R26 still lacks shake to extend the sleep timer, skip silence and volume boost.

## Open work
- No worktrees. Robert to check Han & Chewie Return and The Butcher's Masquerade on Audiobookshelf and Plex for a stray 0:00:30 (cross-sync test; cause fixed in #147).
- Outstanding in the 17 November review, in this order. R39 Jellyfin (plan `superpowers/plans/2026-11-19-jellyfin-j3.md`): left: a second throwaway user for the 403, and Quick Connect's waiting, cancel and time-out screens. R2: CI verifies the goldens; left: Plex's add section (needs a refactor) and the emulator smoke test (after R41). R13 (class refactor, one class per PR), R19 (car: the debug-only Desktop Head Unit trust is in; the Android Auto certificate half needs Google's fingerprints and a Desktop Head Unit) and R30 (iOS, skipped by decision) are not started. R23: the real-ffmpeg test passes in CI; Robert's trial of three real books is due. R35: table, review and two fixes are in; the real-Audiobookshelf script (needs a throwaway user) and four review points remain. R1 (device checks), R24 (TalkBack, needs the phone), R25 (tablets: width caps and a wide-screen grid are in; the two-pane Library is a design question). R36 (playback stops overnight): the journal, exit reasons, Settings row and first-play question are in and ran on the phone. Night 1: killed with signal 9 at 04:29. Night 2 (21 Nov, build 0.5.4-debug.10, Wi-Fi, paused 2:30 to 3:30): no kill. Left: Robert confirms "Never sleeping apps" was set, then the report.
- CI skips build jobs for docs-only PRs (iOS also unless `shared/`, `iosApp/`, Gradle or the workflow changed); `scripts/check-docs.sh` guards this file.

## Next steps
1. Batch 2: the R36 night trial and the device checks (R1) on Robert's phone (wireless, unlocked); R39 and R2 as above.
2. Batch 3: the release pipeline (R12), a demo library (R41), Dependabot and the bouncycastle advisory (R14), then Google Play (R31).
3. Questions waiting for Robert are listed in `DECISIONS.md` under "Open decisions".

## Process
- Plan, implement, independent review, CI, squash merge, only when BOTH CI jobs pass (required checks, admins cannot bypass): `gh pr merge N --auto --squash`.
- A feature pull request does not edit this file; the session that merges updates it once per batch (review R37).
- Commit trailer: `Co-Authored-By: Claude <noreply@anthropic.com>` plus the session line.
- JDK 17, one Gradle command at a time. Install on the phone only with `scripts/install-debug.sh`.
- Agents only when asked; no secrets in their prompts.

## Links
`reviews/2026-11-17-project-review.md` · `DECISIONS.md` · `DEVICE_CHECKS.md` · `PLAY_RELEASE.md` · `ROADMAP.md` · `architecture/` · `CHANGELOG.md` (history) · `JELLYFIN_SETUP.md` · `AUDIBLE.md`
