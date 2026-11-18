# Device checks

How to test on devices, and the checks still open. Phone testing happens only in the debug app (`com.chapterplayer.debug`) over wireless debugging against the owner's own servers.

## Testing on devices
- Emulator AVD `ChapterPlayer_Pixel` (Android SDK emulator). If it hangs or `adb` shows it `offline`: kill `qemu-system-x86_64.exe` and `emulator.exe` by PID, delete `*.lock` in the AVD folder, start with `-no-snapshot -no-audio -gpu swiftshader_indirect`, wait for `sys.boot_completed`. Driving the UI works with `adb shell uiautomator dump` + `input tap`, but text fields lose the first characters unless you tap, wait ~1.5 s, then type.
- Robert's phone over wireless debugging: he turns on Wireless debugging, `adb pair <ip:pairport> <code>` (the code and pairing port only work while his dialog is open), then `adb connect <ip:mainport>` (the main port changes; `adb mdns services` shows it). Use `adb -s <device id> ...` because the emulator is also attached. Install the DEBUG build (package `com.chapterplayer.debug`, separate data from the release app). Read logs with `adb logcat -s ChapterPlayerHttp` (auth headers are redacted); never read his screen or other apps; the database can be copied with `run-as` for diagnosis, titles in it are his real library: never put them in the repo.
- Handing him an APK: build `:androidApp:assembleRelease`, `zipalign`, sign with the release key in `C:\Users\Robert\ChapterPlayerSigning` (`release.jks`, alias `chapterplayer`, password in `signing-info.txt` next to it; outside the repo, git-ignored, Robert must keep a backup: every update has to be signed with the same key) using `zipalign -p 4` and `apksigner sign`, send the 7 MB file with the SendUserFile tool (limit 30 MiB; the debug APK is 31 MiB and cannot be sent). Uninstalling the app wipes downloads and tokens but Android's Google backup restores the Room data (server list, library); tokens and `downloads/` are excluded from backup.
- Real Audiobookshelf checks use a throwaway user created through the admin API (with the download permission when testing downloads) and deleted afterwards; never write with the owner's token; never put tokens or passwords in prompts to subagents, OpenClaw or the repo.

## Checked on the phone
- 2026-11-16, Samsung S24+, debug builds 0.5.3-debug (before numbering) and 0.5.3-debug.2, the owner's Jellyfin 12.4, Audiobookshelf and Plex servers:
  - Jellyfin: add the server and sync; listening shows the same place in Jellyfin web; Mark as finished shows played and Mark as not finished shows unplayed (the UserData write does clear the played flag).
  - Mark as finished returns the book to 0:00 and shows Play again; playing it again makes it not finished in the app and in Jellyfin web.
  - A book on all three servers is one entry with three copies (Diagnostics), and listening on the Jellyfin copy moved the Audiobookshelf copy: after playing again the Audiobookshelf server held the new place, not finished (read by Diagnostics; the Audiobookshelf mobile app showed an older place until refreshed).
  - Found: after Mark as finished the details page offered Resume at the old place (fixed, #108); playback was killed by the system overnight (review R36).
- 2026-11-18, Samsung S24+, debug build 0.5.4-debug.4 (main plus the 19 minor and patch dependency updates, Gradle 9.9.1), the owner's Audiobookshelf server:
  - The app starts and Home shows the library, covers, Up next and progress; Resume plays; Pause stops it.
  - The one-time "Keep playing overnight" question appeared on the first Play with the Unrestricted wording; Open settings opened Android's App battery usage; ChapterPlayer's page offers Optimised, Restricted and Unrestricted; choosing Unrestricted put the debug app on the system's unrestricted list.
  - The playback journal file held "service created", "play asked", "sound started" and "paused" with the book position.
  - Not seen: the Settings row after Unrestricted (the phone received a call during the session), the full Report a problem text, Android's exit reasons on this phone.

## Still open (as of 2026-11-17; to become a table with results, review R1)
- Jellyfin, the rest of plan Task 15: 2 min in the resume band keeps the real place; stopping in the last 5 min is not finished unless it ended; offline listening delivered later; record the real answer shapes into the fixtures (review R39).
- Mark as finished (review R35; the table in `architecture/progress.md` says what each event sends, with a "Checked" column):
  - Audiobookshelf right after Mark as finished: finished at 0:00 on the server (Diagnostics), and after a Pause at 0:00 it is still finished.
  - Plex: Mark as finished shows the album played; Mark as not finished shows it unplayed; Play again after a finished album leaves the tracks before the place played and the rest unplayed.
  - A mark made offline (flight mode), then the network back: the server takes it within a minute; the snackbar said it would.
  - A network drop between the two Audiobookshelf requests (switch off Wi-Fi right after tapping Mark as finished on a slow link): the server never ends on a lost place, and the retry ends finished at 0:00.
  - Two devices: mark finished on one, open the other; play again on the second and check the first.
  - Throwaway-user script against a real Audiobookshelf for the request sequences (finish at 0, play again, a half-sent pair, a mark made offline); save the answers as fixtures.
- Report a problem and the long press on the version line (Settings, About); the Listen widget following the loaded book.
- Backup restore (R4): back up, uninstall, restore; no book may say Downloaded without its files.
- Fonts: Home, Library, Book details and the player in each font, long titles, largest system text size (Lexend scale 0.94 is an estimate).
- Headless Play from the widgets; lock-screen cover; sleep timer carrying into the next book; Forward 30 near the end of a book; Wi-Fi to mobile data during a download; Android Auto covers; keyboard fix; saved filters; multi-select; TalkBack.
- targetSdk 36 (R5), on an Android 15 and an Android 16 phone or emulator, debug build:
  - Download time limit: start a long download and send the app to the background. To avoid waiting 6 hours, shorten the limit (the Android 15 docs give `adb shell device_config put activity_manager data_sync_fgs_timeout_duration 60000` for 1 minute; confirm against the current docs, and delete the value afterwards). Expect: the download notification goes, "Downloads paused" appears, the Downloads tab shows the book Queued with its bytes, nothing downloads in the background; opening the app removes the notice and the download continues from the saved byte (`adb logcat -s WM-SystemFgDispatcher` shows the timeout). With the app in front when the limit hits: it continues at once, no notice. Force-stop between the hold and opening the app: opening still continues it. With notifications off: no notice, opening the app still continues.
  - A queued download after a reboot (no foreground start allowed from boot): it runs or waits without a crash; `adb logcat | grep -i ForegroundServiceStartNotAllowed`.
  - Headless widget Play with the app swiped away (Listen, Continue Listening, Now Playing): the book plays and the notification appears; if Android refuses audio focus or the foreground start, the book pauses and "Tap to keep listening" opens the player, where Play works. Same for resume on car connect and a Play from the Desktop Head Unit.
  - Predictive back (Android 16, gesture navigation): back from Book details, the player, a settings page, selection mode and the Add Server form animate and land where the button would; back on Home shows the back-to-home preview.
  - Edge to edge on both: status and navigation bars transparent in Dark and Light, nothing under the bars, keyboard on Add Server and Search.
  - Large screen (Android 16 ignores orientation and resizability opt-outs on sw600dp): a tablet or foldable emulator in landscape and in split screen; Home, Library, the player and Settings usable.
  - 16 KB pages: install the release APK on the Android 15+ "16 KB page size" emulator image; the app starts, a book plays, a download runs.
  - Notifications: first launch asks once (Android 13+); downloads and playback work when it is refused.
- A night of playback with the app on Unrestricted battery use (R36): note the time it stopped, then Settings, About, Report a problem; the exit reasons and the playback journal should say what ended it.
- iOS: nothing has run on a device; waits for an unmanaged iPhone.
- The audiobook converter's first real trial needs ffmpeg on the machine that holds the library.
