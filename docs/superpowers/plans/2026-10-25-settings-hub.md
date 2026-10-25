# Settings hub with section pages

Branch `stage-3/settings-hub`. Read `CLAUDE.md` first. Robert chose Option 1 (hub) from the mock-up (`https://claude.ai/artifact/VNXGzi9sh8N4MCruGthniA`, source not in repo; summary below). Today `ui/settings/SettingsScreen.kt` (831 lines) is one long page: Appearance, Offline, Downloads, Android Auto, Connected Servers (list + FAB add server + edit server screens). Regroup only; every existing setting keeps its wording and behaviour.

## Target
Settings home (route `settings`): top bar "Settings"; rows (icon, title, one-line live summary, chevron) in grouped cards with small uppercase group labels:
- (no label) **Servers** — "1 Audiobookshelf · signed in" / "No servers yet" / "2 servers" (opens the Servers page: the current server list, edit/delete rows, the add-server FAB, sign-in-again status — exactly today's content and behaviour).
- **Listening**: **Appearance** — "Dark" / "Light" / "Follow system" (opens: the theme radio card).
- **Offline**: **Downloads and offline** — "Wi-Fi only · Internal storage · No limit" (opens a page with groups Network (Wi-Fi only switch), Where files go (location card), Storage (limit card, "Used by downloads: <size>" from `DownloadOverview.storageUsedBytes`), Offline (the "Downloaded only" switch with its description)).
- **Devices**: **Android Auto** — "Resume on connect: on/off" (opens the resume switch card with its description).
- **About** — "Version <versionName>" (opens: app name, version, a line that it is open source with the repo name text only, no link needed; version comes through a small `AppInfo` interface provided by androidApp/Android so commonMain stays portable).
No Playback and no Widgets rows (nothing to set yet; do not add placeholders).

## Constraints
- Navigation: new routes per page in `Screen.kt` and `AppNavigation.kt` (JetBrains navigation, same animation/back behaviour as existing sub-screens such as add/edit server); the bottom bar stays hidden on all settings pages like today; back returns to the hub; the existing entry points to Settings (top-bar gear, player menu "Settings", add server flows, "Sign in again") keep working — check each caller; "Add a server" empty-state buttons on Home etc. must still land where they do today.
- Split `SettingsScreen.kt`: one small file per page under `ui/settings` (hub, servers, appearance, downloads, car, about) reusing the existing card composables (`SwitchCard`, `AppearanceCard`, `DownloadLocationCard`, `StorageLimitCard`, `ServerRow` ...). `SettingsViewModel` stays one shared view model (Koin) used by all pages; no behaviour change.
- Pure summary text rules in `domain/` (e.g. `SettingsSummary.kt`: server count text, downloads summary from wifiOnly/location label/limit) with host tests; view-model tests for what changes; keep all existing settings tests green.
- Accessibility: each hub row is one button with a combined label ("Downloads and offline, Wi-Fi only, Internal storage, No limit").
- commonMain portable; public repo, synthetic data only.
- Update `CLAUDE.md` (a Settings paragraph) and `docs/ROADMAP.md` honestly (not checked on a device).

## Finish
`:shared:testAndroidHostTest`, `:androidApp:assembleDebug`, `:androidApp:assembleRelease` green; privacy scan; commits with trailers `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey` (exactly "Claude Sonnet 5.5"). Never push, never dispatch subagents. One gradle command at a time, JDK 17, CRLF tree (grep after edits). Report to `C:/Users/Robert/AppData/Local/Temp/claude/C--Users-Robert-ClaudeProjects-ChapterPlayer/5951ad6b-9e9b-494e-886e-6d21b9103272/scratchpad/settings-hub-report.md`; return only status, commits, one-line test count, concerns.
