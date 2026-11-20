# Project status

Read this first in every session; it is short on purpose. The list of work is the latest review (`reviews/2026-11-17-project-review.md`). History is in `CHANGELOG.md`, decisions in `DECISIONS.md`, device checks in `DEVICE_CHECKS.md`, design detail in `architecture/`. What has merged is `git log`; do not list it here.

## Now (20 November 2026)
- Version 0.5.4 (versionCode 10, tag `v0.5.4`). Tester builds up to 0.5.4 were shared, signed with the key in `C:\Users\Robert\ChapterPlayerSigning` (outside the repo); that APK predates #127.
- Debug builds are numbered: `scripts/install-debug.sh` installs `0.5.4-debug.N`; Robert's phone has build 10 (20 Nov, main with the screenshot fixes and widget sizes).
- `main` is at #159. 3,411 host tests pass. targetSdk 36. Sources: Audiobookshelf, Jellyfin (progress, downloads and Quick Connect work on the phone), Plex. Audible is skipped. iOS builds in CI and has never run on a device.
- Since the review of 14 November: 19 of its findings are done (R3 to R10, R11, R15 to R18, R20, R22, R32, R33, R34, R37), plus Quick Connect, the Settings option "Sync listening when a server is added" (off by default; both ways; checked on the phone), a confirm before removing a server, layout fixes the screenshots found (player tool buttons, Library grid, Details header, a 640 dp settings column, widget title room), the font choice, Mark as finished returning to 0:00.

## Open work
- No worktrees. Robert to check Han & Chewie Return and The Butcher's Masquerade on Audiobookshelf and Plex for a stray 0:00:30 (cross-sync test; cause fixed in #147).
- Outstanding in the 17 November review, in this order. R39 Jellyfin (plan `superpowers/plans/2026-11-19-jellyfin-j3.md`): downloads and Quick Connect are checked on a real server; left: a second throwaway user for the 403, and Quick Connect's waiting, cancel and time-out screens. R2: CI verifies about 95 goldens (distance 0.05) of the mini player, player, Home, Library, Details, Add Server, settings and widgets, also at font scale 2.0, landscape and tablet; left: Plex's add section (needs a refactor) and the emulator smoke test (after R41). R23: the real-ffmpeg test passes in CI; Robert's trial of three real books is due. R35: table, review and two fixes are in; the real-Audiobookshelf script (needs a throwaway user) and four review points remain. R1 (device checks), R24 (TalkBack, needs the phone), R25 (tablets: width caps and a wide-screen grid are in; the two-pane Library is a design question). R36 (playback stops overnight): the journal, exit reasons, Settings row and first-play question are in and ran on the phone. First report: killed with signal 9 at 04:29 although Unrestricted; likely Samsung's sleeping apps (Robert to check Never sleeping apps). Left: another night, then the report.
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
