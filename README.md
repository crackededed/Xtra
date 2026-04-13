# ThystTV

<p align="center">
  <img src="docs/images/ic_launcher_foreground.svg" width="128" height="128" />
</p>

A fork of [Xtra](https://github.com/crackededed/Xtra) with additional features focused on viewer experience, playback UX, local stats, and accessibility.

> **Current focus:** polishing the app, fixing player and stats regressions, improving large-screen layouts, cleaning up the repo, and staying aligned with upstream Xtra.
>
> **Upstream sync note:** ThystTV currently uses a selective upstream-sync approach. Some upstream Xtra fixes are cherry-picked and conflict-resolved locally so GitHub can still show the fork as "behind" even when those safe changes are already present here. The main intentionally deferred upstream area is the unstable player rewrite, which is being kept out until it can be integrated without breaking ThystTV-specific behavior.

## Current Branch

`fix/stats-layout-and-player-regressions`

## To Do

### Player & Gesture Polish
- [ ] Make seek gestures much more responsive for VoD scrubbing
- [ ] Refine remaining gesture edge cases during active interactions
- [ ] Re-check playback speed UI polish on-device
- [ ] Test gesture behavior thoroughly on-device
- [ ] Re-verify brightness behavior on phone and tablet after the recent fixes

### Stats & Large-Screen Layout
- [x] Fix major stats card/layout issues on phone, tablet, and rotation paths
- [x] Finish the adaptive stats dashboard migration cleanup
- [x] Move stats adaptation fully to width-based behavior
- [ ] Switch adaptive sizing logic from `screenWidthDp` tiers to proper window-size-class handling
- [x] Use the canonical dashboard path instead of legacy screen-specific stats layouts
- [x] Verify compact / medium / expanded dashboard behavior
- [x] Verify rotation-driven dashboard re-layout while the stats screen is already open
- [ ] Verify split-screen and resized window behavior on large screens
- [ ] Do a final wide-tablet polish pass after more real-device screenshots

### Visual & UI Cleanup
- [ ] Fix app icon cropping / masking issues on Android launchers
- [ ] Add updated screenshots to the README
- [ ] Add updated screenshots to the project site
- [ ] Refresh visuals so the repo better reflects the current app UI

### Repo Cleanup & Maintenance
- [ ] Delete temporary screenshots folder
- [ ] Clean up repo structure and temporary assets
- [ ] Review branch leftovers from the stats/player refactor
- [ ] Improve overall project organization

### Upstream Sync
- [ ] Sync with the latest useful upstream Xtra changes
- [ ] Re-check ThystTV-specific features after upstream sync
- [ ] Make sure custom player, stats, and floating chat behavior still work correctly

### Testing
- [x] Run and pass unit tests for adaptive stats behavior
- [x] Run and pass unit tests for brightness / floating-chat state helpers
- [ ] Run and pass broader gesture behavior tests
- [x] Build debug successfully
- [ ] Build release successfully
- [ ] Do proper manual regression testing on phone and tablet layouts

## Progress So Far

### Gesture System
- [x] Tap sensitivity after scroll fixed
- [x] Gesture zone boundaries configurable
- [x] Minimize gesture conflicts improved
- [x] Optional haptic feedback added
- [x] Gesture settings integrated into Settings
- [x] Brightness restore logic improved when leaving fullscreen / minimizing
- [x] Gesture helper and player state unit tests added
- [x] Gesture architecture documentation added

### Stats & Analytics
- [x] Daily screen time tracking implemented
- [x] Favorite channels card implemented with watch time as the primary ranking
- [x] Watch streak tracking implemented
- [x] Streamer loyalty scoring implemented
- [x] Category breakdown, legends, and heatmaps implemented
- [x] Adaptive stats dashboard refactor completed on the main stats path
- [x] Dashboard span policy and adapter introduced
- [x] Width-based stats resources introduced
- [x] Short-height landscape card compositions added for screen time and categories
- [x] Rotation refresh fixed so the dashboard re-lays out immediately on orientation change
- [x] Loyalty and top-stream cards merged into a single full-width favorite-channels card
- [x] Legacy `layout-sw600dp` / `layout-land` stats screen variants removed from the main path

### Floating Chat
- [x] Floating chat overlay implemented
- [x] Opacity control and high-visibility mode implemented
- [x] Drag / resize persistence implemented
- [x] Fullscreen re-entry behavior improved to avoid empty sidebar + floating-chat conflicts

## What's Different from Xtra?

| Feature | Xtra | ThystTV |
|---------|------|---------|
| Floating Chat Overlay | No | Yes |
| Screen Time & Watch Stats | No | Yes |
| Swipe Gesture Controls | No | Yes |
| Watch Streak & Loyalty Metrics | No | Yes |
| Adaptive Stats Dashboard Work | No | In progress |

## Key Features

### Floating Chat
- **Overlay Mode**: Keep up with chat while watching in full-screen
- **Customizable**: Resize and move the chat window anywhere on screen
- **Opacity Control**: Adjust transparency to balance stream and chat visibility
- **High Visibility Mode**: Improve readability over bright video content

### Screen Time & Stats
- **Daily Tracking**: Monitor your viewing time directly inside the app
- **Top Channels**: See your most-watched channels and streamers
- **Watch Streaks**: Track current and longest watch streaks
- **Loyalty Metrics**: See which streamers you come back to most
- **Privacy First**: All stats are stored locally on your device
- **Easy Access**: Open stats from the dedicated tab

### Gesture Controls
- **Volume**: Slide up/down on the right half of the screen
- **Brightness**: Slide up/down on the left half of the screen
- **Seek** (VoD only): Horizontal swipe on the top half of the screen
- **Playback Speed** (VoD only): Horizontal swipe on the bottom half of the screen
- **Visual Feedback**: Real-time overlay while adjusting controls
- **Edge Protection**: Gestures are limited in system gesture zones to reduce conflicts
- **Chat Mode Cycle**: Double-tap cycles chat display modes (overlay, side-by-side, hidden)

## Notes On The Current Stats Migration

The stats screen is now driven by a dashboard-style `RecyclerView` with span-based card placement instead of the old stacked tablet layout path. The current branch includes:

- width tiers at `600dp` and `840dp`
- dashboard span policy tests
- width-based resource buckets for wide-card layouts
- a base dashboard host layout for `StatsFragment`
- short-height landscape compositions for screen time and categories
- immediate adapter/layout refresh on rotation while staying on the stats screen
- a merged `Favorite Channels` card that combines watch time, loyalty, sessions, and relative progress in one list

What is still left after this branch:

- switching width-tier detection to proper dynamic window-size-class handling
- finishing verification under split-screen and arbitrary window resizing
- tuning density and spans on real wide tablets after more screenshots

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run tests
./gradlew test
```

## License

ThystTV is licensed under the [GNU Affero General Public License v3.0](LICENSE), same as the upstream Xtra project.
