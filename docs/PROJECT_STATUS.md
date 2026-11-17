# Project status

Read this first in every session; it is short on purpose. The list of work is the latest review (`reviews/2026-11-17-project-review.md`). History is in `CHANGELOG.md`, decisions in `DECISIONS.md`, device checks in `DEVICE_CHECKS.md`, design detail in `architecture/`. What has merged is `git log`; do not list it here.

## Now (17 November 2026)
- Version 0.5.3 (versionCode 9), tag `v0.5.3`. Tester builds 0.3.3, 0.4.0, 0.4.1 and 0.5.3 were shared, signed with the key outside the repo. 24 pull requests have merged since 0.5.3; no 0.5.4 has been built.
- Debug builds are numbered: `scripts/install-debug.sh` installs `0.5.3-debug.N`. Robert's phone has debug build 2 (main as of 16 Nov, before the shared library #115).
- `main` is at #121. 3,202 host tests pass. targetSdk 36. Sources: Audiobookshelf, Jellyfin (progress goes back; no downloads yet), Plex. Audible is skipped. iOS builds in CI and has never run on a device.
- Since the review of 14 November: 17 of its findings are done (R3 to R10, R15 to R18, R20, R22, R32, R34, R37), plus the font choice, Mark as finished returning the book to 0:00, "play again" making a book not finished, and numbered debug builds.

## Open work
- No unmerged feature branches and no worktrees. One open pull request: Dependabot #114 (18 dependency updates; waits for a rule, review R14).
- Outstanding in the 17 November review. Do first: R33 (the repository settings half: iOS as a required check, auto-merge, no bypass; waits for Robert), R35 (review and real-server check of Mark as finished), R36 (playback stops overnight on the phone), R1 (device checks).
- CI now skips both build jobs for docs-only pull requests and iOS unless `shared/`, `iosApp/`, Gradle files or the workflow changed; a failed run uploads `test-reports`; `scripts/check-docs.sh` guards this file.

## Next steps
1. Finish batch 1: the R33 settings, once Robert agrees (then merge with `gh pr merge N --auto --squash`).
2. Batch 2: R35, R36 and the device checks (R1), with Robert's phone over wireless debugging.
3. Batch 3: the release pipeline (R12), a demo library (R41), a rule for dependency updates (R14), then Google Play (R31).
4. Questions waiting for Robert are listed in `DECISIONS.md` under "Open decisions".

## Process
- Plan, implement, independent review, CI, squash merge. Merge only when BOTH CI jobs have passed (the iOS job is not a required check yet, review R33); check it yourself.
- A feature pull request does not edit this file; the session that merges updates it once per batch (review R37).
- Commit trailer: `Co-Authored-By: Claude <noreply@anthropic.com>` plus the session line.
- JDK 17, one Gradle command at a time. Install on the phone only with `scripts/install-debug.sh`.
- Agents only when the owner asks or a work order says so; never put tokens or passwords in prompts to them.

## Links
`reviews/2026-11-17-project-review.md` · `DECISIONS.md` · `DEVICE_CHECKS.md` · `PLAY_RELEASE.md` · `ROADMAP.md` · `architecture/` · `CHANGELOG.md` (history) · `JELLYFIN_SETUP.md` · `AUDIBLE.md`
