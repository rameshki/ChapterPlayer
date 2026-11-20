# Android Auto

The browse tree, voice matching, the trust rule and car covers.

Detail moved out of `CLAUDE.md` (review R8); keep it current when this area changes.

## Browse tree and starting books

- Android Auto: `PlaybackService` serves a browse tree through `CarLibraryCallback` (`data/playback`): the tree, ids, letter ranges, voice matching and the trust rule are pure code in `domain/car` (`CarRules.kt`, `CarBrowse.kt`).
- Only this app and the allowlisted car packages (`TRUSTED_CAR_PACKAGES`) browse or start books; other controllers keep play/pause/seek only.
- A book requested from the car starts through `PlaybackStarter` (the one load path); Plex books are playable in the car under the same rules as Audiobookshelf books (streaming, or from a download).
- `AppSettings.resumeOnCarConnect` (Settings, Android Auto) resumes the last book when the head unit connects.

## Covers and rows

- Car book rows carry `artworkUri` = the app's cover address `content://<applicationId>.covers/cover/<bookId>` (`carMediaSpec(item, coverAuthority)`), never the server address;
  - `CoverProvider` (`data/images`, exported, read only, in the app manifest) serves them only to this app and `TRUSTED_CAR_PACKAGES` (`coverCallerAllowed`, by `Binder.getCallingUid()`; everyone else gets `SecurityException`),
  - validated book ids only (`parseCoverUri`: exact authority and `/cover/<abs_|plex_ id>`, no encoded characters, dots or queries),
  - bytes written to a hashed cache file (`cacheDir/car_covers`, stale `.part` files removed);
  - a binder call waits at most 4 s and a slower cover keeps loading in the background for the car's next request.

## Known limits

- Known limits: the caller check is by package name (no signature check, as for browsing), and other controllers see the content address in the session metadata but cannot open it (a watch app that reads the address instead of the bitmap shows no cover).
- Not yet seen on the Desktop Head Unit
