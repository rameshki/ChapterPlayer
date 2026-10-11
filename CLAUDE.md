# ChapterPlayer

Audiobook player for Audiobookshelf, Plex and (later) Audible. Android first,
built with Kotlin Multiplatform so it can port to iOS. See `docs/ROADMAP.md`
for scope, stages and design direction. Keep that file updated as stages complete.

## Priorities
- Sources: Audiobookshelf > Plex > Audible (Audible scope undecided; never remove DRM)
- Must have: Android Auto, widgets, offline downloads; later CarPlay + iOS widgets
- Design reference: AudioBooth (iOS ABS client): tabs, home shelves, player sheet, mini player. Borrow structure, not code (MPL-2.0).

## Current state
- Single Android module (`app/`): Compose, Hilt, Room (kapt), Retrofit/Moshi
- Stage 1 of the roadmap migrates this to `shared/` (KMP) + `androidApp/` + `iosApp/`
- No tests yet; no Gradle wrapper yet (Stage 0)

## Building
- Java: Android Studio's bundled JBR (`C:\Program Files\Android\Android Studio\jbr`)
- `./gradlew :app:assembleDebug` once the wrapper is added

## Conventions
- Branches `stage-N/<feature>`, PRs into protected `main`, tags `v0.N.0` per stage
- Server tokens never logged (auth headers are redacted in the HTTP logger)
