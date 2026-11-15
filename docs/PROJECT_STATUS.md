# Project status

Read this first in every session; it is short on purpose. History is in `CHANGELOG.md`, decisions and findings in `DECISIONS.md`, device checks in `DEVICE_CHECKS.md`, design detail in `architecture/`. Update this file whenever a block of work finishes.

## Now
- Version 0.5.3 (versionCode 9). Tags v0.1.0 to v0.4.1 and v0.5.3. Tester builds 0.3.3, 0.4.0, 0.4.1 and 0.5.3 were shared, signed with the key outside the repo.
- `main` holds (newest first):
  - targetSdk 36 with the Android 15 six hour download limit and refused-audio-focus handling (R5; device checks in `DEVICE_CHECKS.md`)
  - Jellyfin J2: progress reporting, queue, marks, three-copy mirroring (#96; mock-tested only, phone check open)
  - CLAUDE.md cut to 5 KB, detail in `docs/architecture` (#95); restored backups no longer claim downloads (#94); CI builds the release APK, lint, R8 seeds check (#93)
  - Font choice: Classic, Bookish, Inter, Manrope, Lexend, System (#92); project review (#91)
  - Widgets: headless Play, midnight look, Listen widget, one look (#87 to #90); Jellyfin J1 (#86)
  - Converter tool (#84); Polish: kept filters, Collections A to Z (#81); Multi-select and Remove finished downloads (#76); Up next (#74); long-press menu and favourites (#73); new icon and navy theme (#71, #72); cover art (#70); Diagnostics (#67)
  - Separate Download / Play source choices (#62 to #64); iOS: browsing, playback, downloads (#55 to #59, compiled and CI-linked only); Plex downloads (#52); merged sources and mirroring; stats; Android Auto; widgets
- Platforms: Android is the product. iOS builds in CI but has never run on a device (the owner's iPhone is managed). Sources: Audiobookshelf, Jellyfin, Plex. Audible is skipped for now.

## Open work
- No unmerged feature branches. Remote branches left to delete: `stage-4/font-choice`, `stage-j/j2`, `stage-r/*` (merged; turn on "Automatically delete head branches" in the repository settings, review R22).
- Review findings still to do: see `docs/reviews/2026-11-14-project-review.md`. Done: R3, R4, R6 (built; phone check open), R8, R9 (this file). In progress or next: R7 (Report a problem), R14, R15, R17, R20, R5 (targetSdk) and R31 (Google Play readiness).

## Next steps
1. R7: a release-safe "Report a problem" row (redacted text report, crash trace saved locally, share sheet).
2. R5 and R31: targetSdk and the Google Play checklist (the app will go on Google Play).
3. Jellyfin Task 15 on the real server, then J3 (Quick Connect, downloads).
4. R14 detekt and Kover (deferred: detekt 2.x is still alpha, check its Kotlin 2.4 / AGP 9 support first; Dependabot and the dependency graph are in), R20 migration tests, R22 repository setting (turn on "Automatically delete head branches").
5. Run the open device checks in `DEVICE_CHECKS.md`; build a 0.5.4 release when the owner wants testers to get fonts and Jellyfin progress.
6. iOS waits for an unmanaged iPhone.

## Process
- Plan, implement, independent review, CI (two jobs, the macOS one about 19 min; main is strict so rebase after another merge), then `gh pr merge N --squash --delete-branch`. The owner authorised merging when CI is green and the review is clean.
- Commit trailer: `Co-Authored-By: Claude <noreply@anthropic.com>` plus the session line.
- Windows: JDK 17, one Gradle command at a time (see CLAUDE.md traps).
- Agents only when the owner asks or a work order says so; never put tokens or passwords in prompts to them.

## Links
`CHANGELOG.md` · `DECISIONS.md` · `DEVICE_CHECKS.md` · `ROADMAP.md` · `architecture/` · `reviews/2026-11-14-project-review.md` · `JELLYFIN_SETUP.md` · `AUDIBLE.md`
