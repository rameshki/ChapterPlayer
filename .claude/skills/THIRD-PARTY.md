# Third-party agent skills

Installed project-level for Claude Code. None of these files were modified.

## Official Google skills (installed with `android skills add`, Android CLI 1.0.16500706)
- android-cli
- agp-9-upgrade (not for KMP projects; applies to Stage 1 PR 1 only)

License terms: see each skill's SKILL.md frontmatter (Google LLC).

## rcosteira79/android-skills (MIT)
Source: https://github.com/rcosteira79/android-skills at commit 0f9af5a (2026-09-19)
Subset taken: koin, kmp-ktor, kmp-boundaries, android-data-layer, coil-compose, datastore, compose, android-debugging
License: _licenses/rcosteira79-android-skills.LICENSE
Note: its compose skill is adapted from aldefy/compose-skill (see the source repo's attribution table).

## mmiani/kotlin-kmp-claude-agent-skills (Apache-2.0)
Source: https://github.com/mmiani/kotlin-kmp-claude-agent-skills at commit 939786c (2026-08-15)
Subset taken (skills only, no agents, hooks, orchestration or workflows): kotlin-kmp-refactor-safety, kotlin-kmp-code-review, kotlin-data-kmp-data-layer, kotlin-platform-kmp-bridges
License: _licenses/mmiani-kotlin-kmp-claude-agent-skills.LICENSE

## Project rule
These skills give general patterns. Where they disagree with docs/ROADMAP.md or the approved Stage 1 spec, the roadmap and spec win (DI, networking, database, navigation and module structure).
