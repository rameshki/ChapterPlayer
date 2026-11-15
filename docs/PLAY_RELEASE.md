# Google Play release checklist

Robert decided on 2026-11-14 that ChapterPlayer goes on Google Play (review findings R5 and R31). Nothing here is done unless ticked. Requirements were checked against Google's pages on 2026-11-15; re-check each before relying on it, because Play changes them (Play Console help, "Target API level requirements" and "App testing requirements for new personal developer accounts").

## Facts that shape the plan
- **Target API:** new apps and every update must target Android 16 (API 36) or higher since 2026-08-31; apps already published must target 35 or higher to stay available to new users. The app targets 34 until R5 lands (PR in review).
- **Closed test:** a personal developer account created after 2023-11-13 must run a closed test with at least 12 testers opted in for 14 continuous days before it can apply for production. An organisation account is generally exempt. Robert pays for the account when he is ready ("not just yet").
- **Foreground services:** the app uses `mediaPlayback` (the player service) and `dataSync` (downloads). Play requires a declaration for each foreground service type in the Play Console with a short video of the feature that uses it.
- **Cleartext:** the manifest allows cleartext (`usesCleartextTraffic=true`) because people's servers are often plain `http://` on the home network. The pre-launch report flags it; the answer is the declared purpose (own servers on the local network), or a network security config that allows cleartext only where the user added a server.

## Checklist
- [ ] 1. **R5:** targetSdk 36 with the Android 15/16 behaviour changes handled (the 6 hour dataSync limit, predictive back, 16 KB page size). Device checks in `DEVICE_CHECKS.md`.
- [ ] 2. **App bundle and signing:** build a signed `.aab` in CI (release workflow, review R12); use Play App Signing with the current key kept as the upload key (the key and its backup stay outside the repo).
- [ ] 3. **Privacy policy page** (a public URL, e.g. GitHub Pages from this repo): no analytics, no ads, nothing leaves the phone except requests to the servers the person adds; a "Report a problem" text is shared only by the person's own choice. Fill in the **Data safety** form to match (account credentials for the person's own servers are stored encrypted on the device and sent only to those servers).
- [ ] 4. **Foreground service declarations** for `mediaPlayback` and `dataSync`, each with a short screen recording (a book playing with the notification; a download running with its notification).
- [ ] 5. **16 KB page size:** confirm on an Android 15+ emulator image with 16 KB pages (the bundled SQLite and any native libraries must be aligned).
- [ ] 6. **Store listing:** short and full description, app icon (512 px), feature graphic (1024x500), phone screenshots (use real-looking synthetic libraries, never the owner's), content rating questionnaire, category (Books & Reference or Music & Audio), contact email.
- [ ] 7. **Android Auto:** decide whether the car feature ships at launch; Play reviews media apps for Auto separately (declare the `automotive_app_desc`, check the media app quality guidelines).
- [ ] 8. **Pre-launch report:** upload to an internal track, read the report (crashes, accessibility, security, cleartext) and fix what it finds.
- [ ] 9. **Closed test:** 12 or more testers for 14 days (the account rule above); gather their feedback through the in-app "Report a problem".
- [ ] 10. **Production access** application, then staged rollout.
- [ ] 11. **After launch:** a deprecation watch for the target API each August, and the dependency updates (review R14).

## Not decided yet
- The account type (personal needs the 12 tester test; an organisation does not but needs verification) and the fee: Robert's call.
- Whether to keep the sideload APKs for outside testers once Play testing starts.
