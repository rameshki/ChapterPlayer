# Google Play listing and Data safety drafts

*Drafts for review R31; nothing is submitted. Check each field against the current Play Console before using it. Screenshots must use a synthetic library, never the owner's.*

## Listing text
- **Name:** ChapterPlayer
- **Short description (80 characters):** An audiobook player for Audiobookshelf, Jellyfin and Plex, chapter first.
- **Full description:**
  ChapterPlayer plays the audiobooks that already live on your own servers. Sign in to Audiobookshelf, Jellyfin and Plex, and see every book in one library. A book that several servers have shows once, and your place stays in step between them.

  - Chapter-first player: chapters, speed, sleep timer, skip 15 and 30 seconds, bookmarks, listening history.
  - Offline: resumable downloads, built for slow connections, with a Wi-Fi-only option and a storage limit.
  - Your place everywhere: progress is saved on the phone first and delivered to your servers, even after listening offline.
  - Android Auto, lock-screen controls and home-screen widgets.
  - Your shelf: favourites, an Up next queue, filters, series and author views, and listening statistics.
  - Private by design: no account, no ads, no analytics. Everything goes only to the servers you add. Open source.
  ChapterPlayer needs your own Audiobookshelf, Jellyfin or Plex server. It does not provide audiobooks.
- **Category:** Books & Reference (alternative: Music & Audio). **Contact email and website:** the owner's choice. **Privacy policy:** `docs/PRIVACY.md` published as a web page.

## Content rating questionnaire (expected answers)
No violence, sexual content, gambling, user-generated content shared with others, or location sharing; the app plays the user's own media. Expect an "Everyone" style rating; answer the questionnaire honestly in the console.

## Data safety form (draft answers)
- **Does the app collect or share user data?** The app handles credentials for the user's own servers, but the developer collects nothing. Answer as the console's definitions require: data is processed on the device and sent only to the servers the user configures (not to the developer or a third party chosen by the developer). Re-read the definition of "collect" and "share" in the console before answering.
- **Data types touched:** account credentials for the user's servers (stored encrypted on the device, sent only to the matching server); app activity (listening progress, sent to the user's own servers); app info and performance only through the optional, user-initiated "Report a problem" share.
- **Encryption in transit:** the app supports https; plain http is allowed because many home servers have no certificate (the declared purpose is the user's own servers on a local network).
- **Data deletion:** removing a server or uninstalling deletes the app's data on the phone; the developer holds no data.
- **Foreground service declarations:** `mediaPlayback` (audiobook playback with its notification) and `dataSync` (long downloads, with the Android 15 six hour limit handled); each needs a short screen recording (see `PLAY_RELEASE.md` item 4).

## Not decided
The contact email, the website for the policy, and whether the listing mentions Android Auto at launch (see `DECISIONS.md`, open decisions).
