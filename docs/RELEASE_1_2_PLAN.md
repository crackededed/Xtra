# ThystTV 1.2 Release Plan

## Release framing

`1.2.0` is a better fit than `2.0.0` for the current scope.

This release should be positioned as:
- player polish
- visual refresh
- repo maturity
- better project presentation

Suggested release names:
- `ThystTV 1.2: player polish and visual refresh`
- `ThystTV 1.2: faster scrubbing, cleaner presentation`
- `ThystTV 1.2: smoother playback, sharper visuals`

## What this release is

This is a quality-focused release that should:
- make the player feel better to use every day
- make the app look more intentional
- make the GitHub repo look maintained and trustworthy
- improve release discipline before larger future work

## What this release is not

This release is not a major architectural reset.
It should avoid pretending to be a new era if the scope is mostly polish and UX refinement.

---

## Top-level goals

### Product goals
- show the current playback speed directly in the player UI
- make VoD seek gestures dramatically faster and more controllable
- fix the minimized-player black-bar/background problem
- improve launcher icon readability and background separation
- add polished screenshots to the README and site
- add a proper GitHub social preview/banner with a stronger visual identity

### Repo goals
- replace the current README-as-worklog with a real front page
- add maintainer/community health files
- make issue and PR intake more structured
- make release publishing intentional instead of an automatic side effect of `master` pushes
- document roadmap, testing, release, and upstream-sync policy

---

## Current assessment

### Already good enough to build on
- `origin/master` is clean and tagged through `v1.1.6`
- CI and release automation exist already
- the app already has distinctive ThystTV-specific features
- the repo already has enough substance to support a polished `1.2` release story

### Still underselling the project
- README is still too much of an internal notebook
- GitHub issue/PR flow is under-structured
- release workflow is still too coupled to `master`
- screenshots and branding do not yet sell the app well
- player polish tasks are visible in code and partially unfinished

---

## Workstreams

## Workstream 1: Repo front door and maintainer hygiene

### Goals
- make the repo understandable in under 30 seconds
- reduce maintainer-only tribal knowledge
- make release process visible and repeatable

### Deliverables
- polished `README.md`
- `CONTRIBUTING.md`
- `SECURITY.md`
- `CODE_OF_CONDUCT.md`
- `CHANGELOG.md`
- `GOVERNANCE.md`
- `.github/CODEOWNERS`
- issue templates and PR template
- `docs/ROADMAP.md`
- `docs/TESTING.md`
- `docs/RELEASE_PROCESS.md`
- `docs/UPSTREAM_SYNC.md`

### Notes
- use the maintainer starter kit as the base
- customize the README with real screenshots and ThystTV-specific messaging
- keep the tone practical, not bureaucratic

### Acceptance criteria
- README no longer exposes branch-specific workflow clutter
- new contributors can see how to report bugs and how releases work
- issue submission becomes structured

---

## Workstream 2: CI and release discipline

### Goals
- keep development verification fast
- make public releases deliberate
- make it easier to trust published APKs

### Deliverables
- stronger `ci.yml` with `assembleDebug` and `test`
- optional `lintDebug` if it is stable enough
- `debug-build.yml` for manual or PR APK generation
- tag-driven `release.yml`
- release notes discipline in `docs/release-notes/`

### Recommendation
- keep normal CI separate from public release publishing
- public releases should come from tags like `v1.2.0`

### Acceptance criteria
- CI verifies serious changes automatically
- releases happen from intentional tags, not just ordinary pushes
- release notes exist before publishing

---

## Workstream 3: Player polish

### 3.1 Speed button shows current speed

#### Goal
- make the current speed visible without opening a dialog

#### Implementation direction
- replace the icon-only speed control with a labeled control
- show values like `1x`, `1.25x`, `1.5x`, `2x`
- use a fixed-width or min-width treatment to avoid jitter
- update immediately when changed by gesture or dialog

#### Likely files
- `app/src/main/res/layout/player_layout.xml`
- `app/src/main/java/com/github/andreyasadchy/xtra/ui/player/PlayerFragment.kt`
- `app/src/main/java/com/github/andreyasadchy/xtra/ui/player/PlayerSettingsDialog.kt`
- `app/src/main/java/com/github/andreyasadchy/xtra/ui/player/PlayerSpeedDialog.kt`

#### Acceptance criteria
- current speed is visible when the button is shown
- label stays correct after gesture, dialog, minimize, and restore paths

### 3.2 Seek gesture becomes much more responsive

#### Goal
- a strong horizontal swipe should traverse meaningful chunks of a long VoD

#### Implementation direction
- replace the fixed seek model with duration-aware scaling
- use nonlinear acceleration so large drags speed up more aggressively
- keep fine control available for smaller drags
- target roughly `10%` to `25%` traversal on long VoDs for a strong swipe
- preserve predictable feedback while dragging

#### Likely files
- `app/src/main/java/com/github/andreyasadchy/xtra/ui/player/PlayerGestureListener.kt`
- `app/src/main/java/com/github/andreyasadchy/xtra/ui/player/PlayerGestureHelper.kt`
- `app/src/test/java/com/github/andreyasadchy/xtra/ui/player/PlayerGestureHelperTest.kt`

#### Acceptance criteria
- long VoDs scrub much faster than today
- short VoDs do not feel absurdly jumpy
- seek remains reversible and predictable during drag

### 3.3 Fix ugly black bars when minimized

#### Goal
- minimized player should look intentional instead of broken

#### Implementation direction
- inspect surface/container/background chain
- identify where hardcoded black is leaking through during minimize
- style the minimized surface background intentionally
- avoid black flashes during transition

#### Likely files
- `app/src/main/res/layout/fragment_player.xml`
- `app/src/main/java/com/github/andreyasadchy/xtra/ui/player/PlayerFragment.kt`
- player implementation fragments if resize mode behavior needs adjustment

#### Acceptance criteria
- no obviously broken black bars in minimized mode
- transitions into and out of minimized mode look deliberate

---

## Workstream 4: Icon and visual identity

### 4.1 Launcher icon background refresh

#### Goal
- make the icon readable on launcher backgrounds and masks

#### Implementation direction
- increase separation between the foreground mark and the background
- avoid the lower-half blending problem
- keep the design simple and mask-safe
- test on at least two launcher styles

#### Deliverables
- updated adaptive icon foreground/background assets
- launcher screenshots showing the improvement

### 4.2 GitHub banner / social preview

#### Goal
- make the repo look attractive when shared

#### Implementation direction
- create a clean, high-contrast banner/social preview
- take inspiration from bold, premium channel branding without copying another brand directly
- align palette with the refreshed icon and README/site screenshots
- keep text minimal and readable in GitHub crops

#### Deliverables
- social preview image for GitHub
- optional README banner if it improves the page

---

## Workstream 5: Screenshots and site polish

### Goal
- prove app quality visually instead of only describing it

### Screenshot set
- home/browse screen
- full-screen player
- floating chat
- stats dashboard on phone
- stats dashboard on tablet or wide layout
- gesture overlay if it reads well in screenshots

### Placement
- one hero screenshot near the top of the README
- small feature gallery lower on the README
- site sections with curated screenshots, not a random dump

### Acceptance criteria
- README and site feel current
- screenshots are consistent in framing and quality

---

## PR plan

### PR 1: Repo front door
- README rewrite
- roadmap/testing/release/upstream docs
- maintainer/community files

### PR 2: Intake and automation
- issue templates
- PR template
- `CODEOWNERS`
- `.gitignore` cleanup
- CI and release workflow split

### PR 3: Player polish
- speed button label
- faster VoD scrubbing
- minimized-player black-bar fix

### PR 4: Visual polish
- launcher icon refresh
- screenshots in README and site
- GitHub social preview/banner

### PR 5: Release prep
- `CHANGELOG.md`
- `docs/release-notes/1.2.0.md`
- version bump
- final regression sweep

---

## Testing plan

### Player verification
- live stream opens correctly
- VoD opens correctly
- speed label updates from dialog changes
- speed label updates from gesture changes
- aggressive seek drag moves through long VoDs quickly
- small seek drag still allows precision
- minimize and restore remain stable

### Device/layout verification
- portrait phone
- landscape phone
- one tablet or large emulator
- split-screen or resized-window check if feasible

### Visual verification
- icon looks distinct on launcher
- screenshots are polished and consistent
- GitHub banner/social preview looks good in actual crop

---

## Release gate for `1.2.0`

### Must ship
- README cleanup
- maintainer/issue/PR files
- intentional release workflow
- speed button current speed
- faster VoD scrubbing
- minimized-player visual fix
- icon background refresh
- screenshots and repo branding

### Should ship
- `.gitignore` cleanup
- changelog and release notes discipline
- large-screen verification pass

### Nice to have
- Dependabot
- CodeQL
- deeper governance polish

---

## Suggested order of execution

1. Apply the maintainer starter kit and customize it for `1.2.0`
2. Clean up README and repo health files
3. Split CI/release workflows
4. Implement player polish changes
5. Refresh icon and visual assets
6. Capture final screenshots
7. Write `1.2.0` release notes and changelog
8. Tag and publish

---

## After `1.2.0`

Save `2.0.0` for a larger milestone such as:
- deeper player architecture work
- stronger large-screen adaptation using better window metrics handling
- broader upstream reconciliation strategy
- more substantial visual/product differentiation from Xtra
