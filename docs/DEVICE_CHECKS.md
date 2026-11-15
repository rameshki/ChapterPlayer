# Device checks

How to test on devices, and the checks still open. Phone testing happens only in the debug app (`com.chapterplayer.debug`) over wireless debugging against the owner's own servers.

## Testing on devices
- Emulator AVD `ChapterPlayer_Pixel` (Android SDK emulator). If it hangs or `adb` shows it `offline`: kill `qemu-system-x86_64.exe` and `emulator.exe` by PID, delete `*.lock` in the AVD folder, start with `-no-snapshot -no-audio -gpu swiftshader_indirect`, wait for `sys.boot_completed`. Driving the UI works with `adb shell uiautomator dump` + `input tap`, but text fields lose the first characters unless you tap, wait ~1.5 s, then type.
- Robert's phone over wireless debugging: he turns on Wireless debugging, `adb pair <ip:pairport> <code>` (the code and pairing port only work while his dialog is open), then `adb connect <ip:mainport>` (the main port changes; `adb mdns services` shows it). Use `adb -s <device id> ...` because the emulator is also attached. Install the DEBUG build (package `com.chapterplayer.debug`, separate data from the release app). Read logs with `adb logcat -s ChapterPlayerHttp` (auth headers are redacted); never read his screen or other apps; the database can be copied with `run-as` for diagnosis, titles in it are his real library: never put them in the repo.
- Handing him an APK: build `:androidApp:assembleRelease`, `zipalign`, sign with the release key in `C:\Users\Robert\ChapterPlayerSigning` (`release.jks`, alias `chapterplayer`, password in `signing-info.txt` next to it; outside the repo, git-ignored, Robert must keep a backup: every update has to be signed with the same key) using `zipalign -p 4` and `apksigner sign`, send the 7 MB file with the SendUserFile tool (limit 30 MiB; the debug APK is 31 MiB and cannot be sent). Uninstalling the app wipes downloads and tokens but Android's Google backup restores the Room data (server list, library); tokens and `downloads/` are excluded from backup.
- Real Audiobookshelf checks use a throwaway user created through the admin API (with the download permission when testing downloads) and deleted afterwards; never write with the owner's token; never put tokens or passwords in prompts to subagents, OpenClaw or the repo.

## Still open (as of 2026-11-15)
- Jellyfin J2 on the real Jellyfin 12.4 server (plan Task 15): listen 10 min and see the place in Jellyfin web; 2 min in the resume band keeps the real place; stopping in the last 5 min is not finished unless it ended; Mark as finished and not finished (and whether the UserData write clears Played); one tile "3 sources" with three copies; listening on one copy moves the others within seconds; offline listening delivered later.
- Backup restore (R4): back up, uninstall, restore; no book may say Downloaded without its files.
- Fonts: Home, Library, Book details and the player in each font, long titles, largest system text size (Lexend scale 0.94 is an estimate).
- Headless Play from the widgets; lock-screen cover; sleep timer carrying into the next book; Forward 30 near the end of a book; Wi-Fi to mobile data during a download; Android Auto covers; keyboard fix; saved filters; multi-select; TalkBack.
- iOS: nothing has run on a device; waits for an unmanaged iPhone.
- The audiobook converter's first real trial needs ffmpeg on the machine that holds the library.
