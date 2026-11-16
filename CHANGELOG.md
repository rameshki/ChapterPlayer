# Changelog

What changed in each tester build, newest first. (The long history of how the project was built is in `docs/CHANGELOG.md`.)

## 0.5.4 (17 November 2026)
**New**
- **Font choice:** Settings, Appearance, Font: Classic (the look you know), Bookish, Inter, Manrope, Lexend, or the phone's own font.
- **Report a problem:** Settings, About. Shares a text report (app and phone version, your servers by kind, the last sync, the last crash). Addresses, names and passwords are removed, and nothing is sent unless you choose where. A long press on the version line opens the book-level Diagnostics page.
- **Jellyfin sends your place back:** listening to a Jellyfin book now updates its place and played state in Jellyfin, and Mark as finished and Mark as not finished work on Jellyfin too.
- **Mark as finished returns the book to the start (0:00), and the page offers Play again.** Playing a finished book again makes it not finished, on the phone and on your servers.

**Fixed**
- After restoring the app from a phone backup, books no longer claim to be downloaded when their files are gone; they show Try again.
- Downloads: Android 15 stops a long download after 6 hours in the background. The app now pauses the queue, tells you ("Downloads paused"), and carries on when you open it.
- Starting a book from a widget or the car while Android refuses audio now pauses and shows "Tap to keep listening" instead of stopping silently.
- Lists, the car and the widgets share one merged library, so a long listening session does less work in the background.

**Under the hood:** the app now targets Android 16 (required for Google Play).

## 0.5.3 (earlier)
Jellyfin as a third kind of server (browse, stream, resume), the Listen widget, widget Play without opening the app, one midnight look for the three widgets.
