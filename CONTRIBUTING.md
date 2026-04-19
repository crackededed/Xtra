# Contributing to ThystTV

Thanks for contributing.

This project is still relatively small, so the rules are intentionally simple. The main goal is to keep the repo stable, reviewable, and easy to maintain.

## Before you start

For anything non-trivial:
1. search existing issues first
2. open an issue if the change affects UX, architecture, release process, or upstream sync behavior
3. avoid opening a giant surprise PR

## Local setup

Recommended:
- Android Studio current stable
- JDK 21
- Android SDK matching the repo config

Basic commands:

```bash
./gradlew assembleDebug
./gradlew test
./gradlew assembleRelease
```

## Branch naming

Use short, specific branch names:

- `feat/<topic>`
- `fix/<topic>`
- `docs/<topic>`
- `chore/<topic>`
- `refactor/<topic>`
- `release/<topic>`

Examples:
- `fix/player-speed-label`
- `feat/faster-vod-scrub`
- `docs/release-process`

## Pull request expectations

Keep PRs focused.

A good PR should:
- solve one main problem
- explain user impact clearly
- avoid unrelated refactors
- include screenshots for UI changes
- include manual test notes for player/layout changes
- link the relevant issue

## UI changes

If you touch UI, include:
- before/after screenshots when useful
- phone screenshots at minimum
- tablet / wide screenshots if layout behavior changes
- light explanation of the intended UX

## Player and gesture changes

If you touch player, playback, or gestures:
- test live playback
- test VoD playback
- test orientation change
- test minimize/restore if affected
- test gesture interactions that could conflict

## Upstream sync changes

If the PR pulls in upstream Xtra changes:
- say exactly what was taken
- say why it was taken
- mention any intentionally skipped adjacent upstream changes
- call out risk areas to retest

## Code style

General expectations:
- prefer small, readable changes
- do not mix cleanup and functional changes without reason
- do not introduce broad formatting churn
- preserve project consistency over personal preference

## Review standard

PRs may be rejected if they are:
- too broad
- hard to test
- weakly explained
- likely to cause regressions without enough validation
- clearly branch-dump style work instead of a reviewable change

## Release changes

Release-related PRs should include:
- version bump
- release notes draft
- changelog update
- confirmation that release workflow path was considered

## Security

Please do not report vulnerabilities in public issues. See [SECURITY.md](SECURITY.md).
