# ChapterPlayer privacy policy

*Draft for the Google Play listing (review R31). Publish it as a public web page (for example GitHub Pages from this repository) and put its address in the Play Console. The contact address is the owner's to fill in before publishing. Last updated: 2026-11-16.*

ChapterPlayer is an audiobook player for the servers you already use: Audiobookshelf, Jellyfin and Plex. It is open source (Apache-2.0).

## What the app does with your data
- **The app has no account of its own, no analytics, no advertising and no tracking.** The developer receives nothing from your phone.
- **Your servers.** When you add a server, the app sends your sign-in details and requests (your library, covers, audio, your listening place) only to that server. For Plex it also talks to plex.tv (Plex's own service) to sign in and find your server. Those services have their own privacy policies.
- **What stays on your phone.** Your server addresses, your sign-in tokens (encrypted with the phone's secure key storage), a copy of your library list, your listening progress, favourites, bookmarks, your Up next list, downloaded audiobooks, and listening statistics. Removing a server, clearing the app's storage or uninstalling deletes them. Android's own phone backup may restore the library list and progress (not your sign-in tokens and not downloaded files).
- **Listening progress** is also sent to the server the book came from (and to the other servers that have the same book, if you use more than one) so your place follows you. That is the point of the app, and it only goes to servers you added.
- **Report a problem** (Settings, About). It builds a text report: app and Android version, your servers by kind (never their names or addresses), the last sync result, the size of the progress queue, the last crash and recent errors, with addresses, tokens, names and file paths removed. **Nothing is sent anywhere unless you choose an app to share it with.**
- **Notifications** are used for the playback controls and for download progress. **Network and storage** access is used to reach your servers and to keep downloads on the phone.

## What the app does not do
It does not read your contacts, location, microphone, camera, photos or other apps. It does not contain third-party advertising or analytics code. It does not remove DRM or play protected files.

## Children
The app is not directed at children and collects no personal data.

## Changes and contact
If this policy changes, the new version will be published at the same address with a new date. Questions: use the issues page of the project repository, or the contact address listed on the Play listing.
