# Project status

Read this first in every session; it is short on purpose. The list of work is the latest review (`reviews/2026-11-17-project-review.md`). History is in `CHANGELOG.md`, decisions in `DECISIONS.md`, device checks in `DEVICE_CHECKS.md`, design detail in `architecture/`. What has merged is `git log`; do not list it here.

## Now (19 November 2026)
- Version 0.5.4 (versionCode 10), tag `v0.5.4`. Tester builds up to 0.5.4 were shared, signed with the key outside the repo (`C:\Users\Robert\ChapterPlayerSigning`); the 0.5.4 APK predates #127 and later changes.
- Debug builds are numbered: `scripts/install-debug.sh` installs `0.5.4-debug.N`. Robert's phone has debug build 7 (19 Nov: main plus the options below).
- `main` is at #146 (#147 follows). 3,326 host tests pass. targetSdk 36. Sources: Audiobookshelf, Jellyfin (progress goes back; downloads and Quick Connect work on the phone), Plex. Audible is skipped. iOS builds in CI and has never run on a device.
- Since the review of 14 November: 19 of its findings are done (R3 to R10, R11, R15 to R18, R20, R22, R32, R33, R34, R37), plus Quick Connect, the Settings option "Sync listening when a server is added" (off by default; the newest place of each book goes to its other copies, both ways; checked on the phone), a confirm before removing a server, the font choice, Mark as finished returning the book to 0:00, "play again" making a book not finished, and numbered debug builds.

## Open work
- No worktrees. Robert to check Han & Chewie Return and The Butcher's Masquerade on Audiobookshelf and Plex: the cross-sync test may have left 0:00:30 (cause fixed in #147).
- Outstanding in the 17 November review, in this order. R39 Jellyfin (plan `superpowers/plans/2026-11-19-jellyfin-j3.md`): downloads and Quick Connect are checked on a real server; left: a second throwaway user for the 403, and the Quick Connect waiting, cancel and time-out screens on a device. R2: CI verifies the screenshot goldens (small per-pixel tolerance); the player, Home, Library, Add Server, widgets and font scale 2.0 are not covered yet. R23: the real-ffmpeg test passes in CI; Robert's trial of three real books is to do. R35: table, review and one fix are in; the real-Audiobookshelf script and five review points remain. R1 (device checks), R24 (TalkBack, needs the phone), R25 (tablets, after R2). R36 (playback stops overnight): the journal, exit reasons, Settings row and first-play question are in and ran on the phone. First report: killed with signal 9 at 04:29 although Unrestricted; likely a Samsung sleeping-apps list (Robert to check Never sleeping apps); the idle-timer loop is fixed. Left: another night, then read the report.
- CI skips the build jobs for docs-only PRs, and iOS unless `shared/`, `iosApp/`, Gradle or the workflow changed; `scripts/check-docs.sh` guards this file.

## Next steps
1. Batch 2: the R36 night trial and the device checks (R1), with Robert's phone over wireless debugging (the phone must be unlocked); R39 and R2 as above.
2. Batch 3: the release pipeline (R12), a demo library (R41), Dependabot alerts and the bouncycastle advisory (R14), then Google Play (R31).
3. Questions waiting for Robert are listed in `DECISIONS.md` under "Open decisions".

## Process
- Plan, implement, independent review, CI, squash merge. Merge only when BOTH CI jobs have passed (both are required checks, admins cannot bypass); merge with `gh pr merge N --auto --squash`.
- A feature pull request does not edit this file; the session that merges updates it once per batch (review R37).
- Commit trailer: `Co-Authored-By: Claude <noreply@anthropic.com>` plus the session line.
- JDK 17, one Gradle command at a time. Install on the phone only with `scripts/install-debug.sh`.
- Agents only when the owner asks or a work order says so; no secrets in their prompts.

## Links
`reviews/2026-11-17-project-review.md` · `DECISIONS.md` · `DEVICE_CHECKS.md` · `PLAY_RELEASE.md` · `ROADMAP.md` · `architecture/` · `CHANGELOG.md` (history) · `JELLYFIN_SETUP.md` · `AUDIBLE.md`
