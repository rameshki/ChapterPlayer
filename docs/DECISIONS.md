# Decisions, findings and rules

The owner's decisions, what was learnt on real servers and phones, and the standing rules. Moved out of `PROJECT_STATUS.md` (review R9).

## Decisions (Robert)
- 2026-10-29: Android first and clean, then iOS (Stage 5). No Apple Developer account yet and no paid publishing for now. TestFlight needs the paid Program, so the iOS test route is a free one: the macOS CI job builds an unsigned `.ipa`, Robert signs it from Windows with Sideloadly (free Apple ID, 7-day expiry, 3 apps, no push/CarPlay entitlements) and installs it on his iPhone over USB. Revisit TestFlight if he decides to pay. There is no Mac available for local Xcode work.
- 2026-10-30: the free Apple ID + Sideloadly route is fine for the first iOS builds. Robert's iPhone is an iPhone 12 on the latest iOS (he reported 27.2), so the deployment target stays iOS 16 and the iPhone is the target device; his iPads (one on iOS 12) are not targets (Compose Multiplatform needs iOS 13 or newer).

- 2026-10-30: no Mac purchase and no virtual Mac (against Apple's licence). iOS code is checked by the GitHub macOS CI job (~12 to 15 minutes per cycle) and, for Kotlin compilation only, on Windows with `./gradlew :shared:compileKotlinIosSimulatorArm64` (works; only the framework link and the Xcode build need the Mac runner). Revisit a used Mac mini or a rented cloud Mac only if the slow loop becomes a real problem (e.g. audio debugging).

- 2026-11-01: Robert's iPhone is managed (Intune MDM), so sideloading the unsigned .ipa is expected to be blocked (Developer Mode / trusting a developer certificate are normally restricted) and we do not try to work around a work device's management. iPhone testing is blocked until there is an unmanaged iPhone or iPad on iOS 16 or newer (his iPads: one is on iOS 12, too old; others not yet asked). No new iOS feature work is started; the iOS code stays compile- and CI-checked (the macOS job links, runs the iOS simulator tests and builds the .ipa on every merge to main) and is kept healthy. Android is the product until a test device exists.

- 2026-11-01: a book on several servers now ASKS which copy to play or download from by default ("When a book is on several servers: Always ask" in Settings, Downloads and offline; the earlier default "prefer the downloaded copy, then Audiobookshelf" never asked). Found on his phone: Play and Download did not ask.
- 2026-11-01 (later): the single choice is split into "Download from" and "Play from (when not downloaded)" (Ask every time, Audiobookshelf, Plex, and a line per server when there are two or more of a type); "prefer the downloaded copy" is no longer an option because a downloaded copy always plays first. Plan `docs/superpowers/plans/2026-11-01-source-choices.md`.
- 2026-11-14 (after the project review, `docs/reviews/2026-11-14-project-review.md`): the app WILL go on Google Play (so the targetSdk move and the Play checklist, findings R5 and R31, are required); Jellyfin stage 2 resumes on 2026-11-15 (branch `stage-j/j2` and its worktree are kept as they are until then); a release-safe "Report a problem" row that shares a redacted text report is approved (R7); the app is English only (no string-resource migration, R16); iOS waits for an unmanaged phone, no simulator launch check in CI for now (R30); housekeeping approved and done (PR #61 closed, merged branches and empty worktree folders removed, `v0.5.3` tagged). Later the same day: **Audible is skipped for now** (nothing Audible-related is built until Robert reopens it; for then: purchases only, marketplace audible.com.au, the Amazon data export "maybe later"; recorded in `docs/AUDIBLE.md`), and the two branches whose PRs were closed unmerged (`fix/plex-progress-call`, `stage-1/plugin-settings`) were deleted with his approval. The font question was settled on 2026-11-15: rather than replace the fonts, the user picks one (Classic stays the default; see Font choice above).

## Findings from the real devices
- Verified on Robert's real Plex server (2026-11-06, with the Diagnostics page): both `/:/timeline` and `/:/progress` make the server update the track's `lastViewedAt`, but it keeps NO `viewOffset` for audio tracks in this library type (plays 0, offset none, also at 11% of a 2 h track), and Plex Web / Plexamp offer no resume. So Plex itself never holds the listening position of these books: books on both servers get it from the Audiobookshelf copy (mirrored); a Plex-only book keeps its position on the phone only (lost on reinstall or on a second device). The `/:/progress` change (PR #68) was tried and closed as useless.
- Android phone (2026-11-06, wireless debugging): the merged book test worked end to end (one tile "2 sources", the Play and Download sheets, the position mirrored between copies within about a second, nothing left in the queue), a Plex download completes in seconds on the home network and plays offline from the file with no network requests, and the Diagnostics page reads both servers and logs a redacted report under the tag `ChapterPlayerDiag`. Not testable remotely: the Wi-Fi to mobile data switch mid-download (it cuts the wireless link) and anything needing the phone unlocked or plugged in.
- Android phone (2026-11-07, wireless debugging, debug build of main with cover art, icon/theme, long-press menu, favourites and Up next): seen working: navy cards with the warm tab bar; the long-press sheet in the Library (Play, Play next, Add to Up next, favourites, Mark as finished, Download, Go to series, Book details; the loaded book's Play next / Add to Up next say "Playing now"); Plays next snackbar; the Up next shelf on Home; the player's Up next sheet (empty text, order, move up/down, remove, Clear all with a Keep / Clear Up next confirmation); a book that ends moves to the first queued book on its own, the queue entry is consumed and the Home shelf disappears; the media notification carries the cover as a 101x101 bitmap for an Audiobookshelf stream and for a Plex book. Fixed after seeing it: the player's "Bookmarks" label wrapped now that the tool row has five buttons (smaller label), and the mini player had no visible edge over the navy cards (lighter navy and a thin amber line on top). Not seen yet: the lock-screen cover itself, covers in Android Auto, the home-screen icon and themed icon, the sleep timer carrying into the next book, Forward 30 s near a book's end, and the long-press menu on Home, Search and group pages.

## Rules that must not be broken
- Licence: Apache-2.0 (`LICENSE`, `NOTICE`), chosen 2026-11-07 (permissive, patent grant, matches the Compose/Media3/Room/Ktor dependencies, fine for the App Store). Release APKs shared so far: 0.3.3 and 0.4.0, same signing key.
- Public repo: no real server addresses, ids, titles, library names, tokens or passwords in commits, tests, docs or PR text (fixtures keep real structure with synthetic text). Scan every diff before pushing.
- Tokens never in URLs, logs, exceptions, notifications or media metadata; auth headers are redacted in the HTTP logger; the Plex token goes only to the server's own verified origins; no redirects to another host with a token.
- Never remove DRM. ComfyUI is irrelevant, never raise it. Pronouns: they/them unless stated.

## Dated decision log (one line each; newest last)
Format: date, decision, why, who. Earlier entries are in full above; this list is the quick index. Check it before re-opening a settled question.
- 2026-10-29, Android first and clean, then iOS; no paid Apple account yet, free Sideloadly route for iOS builds (Robert).
- 2026-11-01, a book on several servers ASKS which copy to play or download from; later split into "Download from" and "Play from"; a downloaded copy always plays first (Robert, found on his phone).
- 2026-11-01, iPhone testing waits for an unmanaged device; his iPhone is MDM-managed and is never worked around (Robert).
- 2026-11-07, licence Apache-2.0 (Robert).
- 2026-11-07/08, dark theme "Navy cards" with the old warm tab bar kept (Robert preferred it); amber accent; widgets share the app's midnight look (Robert).
- 2026-11-08, Jellyfin as a third source: design approved, one `.m4b` per book, Quick Connect and downloads in J3, three-copy merging and mirroring (Robert; spec `docs/superpowers/specs/2026-11-07-jellyfin-source-design.md`).
- 2026-11-14, the app WILL go on Google Play (Robert; see `PLAY_RELEASE.md`).
- 2026-11-14, English only; no string-resource migration (Robert).
- 2026-11-14, Audible is skipped for now (Robert; `AUDIBLE.md` keeps the notes).
- 2026-11-14, release builds get a "Report a problem" row that shares a redacted text report; no analytics, no network call (Robert).
- 2026-11-14, iOS waits for an unmanaged phone; no simulator launch check in CI for now (Robert).
- 2026-11-14, housekeeping approved: PR #61 closed, merged branches and worktrees deleted (Robert).
- 2026-11-14, standing authorisation: merge PRs when CI is green and the review is clean (Robert: "Merge as you see fit then proceed").
- 2026-11-14, hand over device testing to Claude over wireless debugging on his own phone and servers, debug app only (Robert).
- 2026-11-15, the app font is the user's choice: Classic (default), Bookish, Inter, Manrope, Lexend, System (Robert).
- 2026-11-16, Mark as finished sets the flag AND returns the book to 0:00; playing a finished book again makes it not finished, locally and on the servers (Robert; found on his phone).
- 2026-11-16, Dependabot PRs are not merged unasked, because a green build does not show a behaviour change (Claude, to confirm with Robert). The GitHub Actions group (#104) was merged on green CI.
- 2026-11-17, push and merge the shared merged library (R10) after both CI jobs pass (Robert: "Yes push it").
- 2026-11-17, both CI jobs are required checks, auto-merge is on and admins cannot bypass (Robert, repository settings; R33).
- 2026-11-17, a new project review replaces the 14 November one as the list of work (`reviews/2026-11-17-project-review.md`); findings keep their ids (Robert asked for it).

## Open decisions (what each one blocks; as of the review of 17 November 2026)
- Google Play account type and fee (a personal account needs a 12 tester, 14 day closed test; an organisation account does not): blocks the Play release (R31). Robert: "not just yet".
- Build 0.5.4 for the testers now, or after the device checks: blocks the next tester build (R12, R1).
- Whether the app asks for the battery-optimisation exemption in-app (Play restricts the permission) or only explains the phone setting: playback was killed overnight on his phone on 16 Nov and 13 Nov (R36).
- Turn on Dependency graph and Dependabot alerts in the repository settings: without it no vulnerability alert can arrive (R14).
- Dependency updates: merge patch and minor groups after green CI and one install on the phone, majors separately? Dependabot #114 (18 updates) is waiting (R14).
- Whether Android Auto ships at launch: Play reviews Auto apps separately (R31, R19).
- Whether Audiobookshelf's "finished at" time may become the time of the mark (finished-at-zero is sent as two requests): accepted for now (R35).
- After a book ends, a play report from earlier in the book starts reporting again (so listening after a seek back is not lost); asked on 15 Nov, unanswered, kept as it is.
- detekt: try the 2.x alpha now or wait for a stable release (R14).
- Whether to keep sideload APKs for outside testers once Play testing starts (R12).
- The six unanswered questions at the end of `AUDIBLE.md`: parked while Audible is skipped.
