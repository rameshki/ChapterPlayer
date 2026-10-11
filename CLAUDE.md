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
- No tests yet

## Building
- Java: JDK 17 (CI uses Temurin 17). Android Studio's bundled JBR 21 also works, but newer Studio releases bundle JBR 25, which Gradle 8.8 cannot run on. Stage 1's Gradle upgrade removes this limit.
- `./gradlew :app:assembleDebug` (needs JAVA_HOME set to JDK 17 or 21)

## Conventions
- Branches `stage-N/<feature>`, PRs into protected `main`, tags `v0.N.0` per stage
- Server tokens never logged (auth headers are redacted in the HTTP logger)

## New dev machine
- `scripts/setup-dev-machine.ps1` installs Git, GitHub CLI, Android Studio, SDK + emulator + Android Auto DHU, Claude Code, and clones the repo
