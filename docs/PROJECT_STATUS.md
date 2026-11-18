# Project status

Read this first in every session; it is short on purpose. The list of work is the latest review (`reviews/2026-11-17-project-review.md`). History is in `CHANGELOG.md`, decisions in `DECISIONS.md`, device checks in `DEVICE_CHECKS.md`, design detail in `architecture/`. What has merged is `git log`; do not list it here.

## Now (18 November 2026)
- Version 0.5.4 (versionCode 10), tag `v0.5.4`. Tester builds 0.3.3, 0.4.0, 0.4.1, 0.5.3 and 0.5.4 were shared, signed with the key outside the repo (`C:\Users\Robert\ChapterPlayerSigning`). The 0.5.4 APK was built before #127 (the overnight wording), so it lacks that text change.
- Debug builds are numbered: `scripts/install-debug.sh` installs `0.5.4-debug.N`. Robert's phone has debug build 3 (main as of #126).
- `main` is at #129. 3,215 host tests pass. targetSdk 36. Sources: Audiobookshelf, Jellyfin (progress goes back; no downloads yet), Plex. Audible is skipped. iOS builds in CI and has never run on a device.
- Since the review of 14 November: 18 of its findings are done (R3 to R10, R15 to R18, R20, R22, R32, R33, R34, R37), plus the font choice, Mark as finished returning the book to 0:00, "play again" making a book not finished, and numbered debug builds.

## Open work
- No unmerged feature branches and no worktrees, and no open pull requests. Dependabot #128 and #129 were merged on 19 November (one install on the phone first, build `0.5.4-debug.4`); the next weekly group arrives under the rule in `dependabot.yml`.
- Outstanding in the 17 November review. Do first: R35 (review and real-server check of Mark as finished), R1 (device checks). R36 (playback stops overnight): the journal, Android's exit reasons in Report a problem, the Settings row and the first-play question are in main; what is left is one night on the phone with the battery setting, then reading the report. The first-play dialog and the journal have not run on a device (checked on an Android 15 emulator without a server).
- CI now skips both build jobs for docs-only pull requests and iOS unless `shared/`, `iosApp/`, Gradle files or the workflow changed; a failed run uploads `test-reports`; `scripts/check-docs.sh` guards this file.

## Next steps
1. Batch 1 of the review is finished.
2. Batch 2: R35, the R36 night trial and the device checks (R1), with Robert's phone over wireless debugging (the phone must be unlocked).
3. Batch 3: the release pipeline (R12), a demo library (R41), Dependabot alerts and the bouncycastle advisory (R14), then Google Play (R31).
4. Questions waiting for Robert are listed in `DECISIONS.md` under "Open decisions".

## Process
- Plan, implement, independent review, CI, squash merge. Merge only when BOTH CI jobs have passed (both are required checks, admins cannot bypass); merge with `gh pr merge N --auto --squash`.
- A feature pull request does not edit this file; the session that merges updates it once per batch (review R37).
- Commit trailer: `Co-Authored-By: Claude <noreply@anthropic.com>` plus the session line.
- JDK 17, one Gradle command at a time. Install on the phone only with `scripts/install-debug.sh`.
- Agents only when the owner asks or a work order says so; never put tokens or passwords in prompts to them.

## Links
`reviews/2026-11-17-project-review.md` · `DECISIONS.md` · `DEVICE_CHECKS.md` · `PLAY_RELEASE.md` · `ROADMAP.md` · `architecture/` · `CHANGELOG.md` (history) · `JELLYFIN_SETUP.md` · `AUDIBLE.md`
