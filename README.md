# ThystTV

<p align="center">
  <img src="docs/images/ic_launcher_foreground.svg" width="128" height="128" />
</p>

A fork of [Xtra](https://github.com/crackededed/Xtra) with additional features focused on viewer experience, playback UX, local stats, and accessibility.

> **Current focus:** polishing the app, fixing player and stats regressions, improving large-screen layouts, cleaning up the repo, and staying aligned with upstream Xtra.

## Current Branch

`fix/stats-layout-and-player-regressions`

## To Do

### Player & Gesture Polish
- [ ] Fix brightness gesture applying the wrong value
- [ ] Fix brightness gesture so it does not jump to extreme brightness at night
- [ ] Make the playback speed button show the current speed
- [ ] Make seek gestures much more responsive for VoD scrubbing
- [ ] Refine remaining gesture edge cases during active interactions
- [ ] Test gesture behavior thoroughly on-device

### Stats & Large-Screen Layout
- [ ] Fix remaining stats card/layout issues
- [ ] Finish the adaptive stats dashboard migration cleanup
- [ ] Remove any leftover legacy tablet-specific layout paths still overriding the new dashboard
- [ ] Move stats adaptation fully to width-based behavior
- [ ] Switch adaptive sizing logic to proper window-size-class handling
- [ ] Verify compact / medium / expanded dashboard behavior
- [ ] Verify split-screen, resized window, and rotation behavior on large screens
- [ ] Tune card spans, padding, and density for wide tablets

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
- [ ] Run and pass unit tests for adaptive stats behavior
- [ ] Run and pass unit tests for gesture behavior
- [ ] Build debug and release successfully
- [ ] Do proper manual regression testing on phone and tablet layouts

## Progress So Far

### Gesture System
- [x] Tap sensitivity after scroll fixed
- [x] Gesture zone boundaries configurable
- [x] Minimize gesture conflicts improved
- [x] Optional haptic feedback added
- [x] Gesture settings integrated into Settings
- [x] Gesture logic unit tests added
- [x] Gesture architecture documentation added

### Stats & Analytics
- [x] Category breakdown implemented
- [x] Charts, legends, and heatmaps implemented
- [x] Adaptive stats dashboard refactor started
- [x] Dashboard span policy and adapter introduced
- [x] Width-based stats resources introduced

## What's Different from Xtra?

| Feature | Xtra | ThystTV |
|---------|------|---------|
| Floating Chat Overlay | No | Yes |
| Screen Time & Watch Stats | No | Yes |
| Swipe Gesture Controls | No | Yes |

## Key Features

### 💬 Floating Chat
- **Overlay Mode**: Keep up with chat while watching in full-screen
- **Customizable**: Resize and move the chat window anywhere on screen
- **Opacity Control**: Adjust transparency to balance stream and chat visibility
- **High Contrast Mode**: Improve readability over bright video content

### ⏱️ Screen Time & Stats
- **Daily Tracking**: Monitor your viewing time directly inside the app
- **Top Channels**: See your most-watched channels and streamers
- **Privacy First**: All stats are stored locally on your device
- **Easy Access**: Open stats from the dedicated tab

### 👆 Gesture Controls
- **Volume**: Slide up/down on the **right** half of the screen
- **Brightness**: Slide up/down on the **left** half of the screen
- **Seek** (VoD only): Horizontal swipe on the **top** half of the screen
- **Playback Speed** (VoD only): Horizontal swipe on the **bottom** half of the screen
- **Visual Feedback**: Real-time overlay while adjusting controls
- **Edge Protection**: Gestures are limited in system gesture zones to reduce conflicts
- **Double-Tap**: Cycle chat display modes (overlay, side-by-side, hidden)

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run tests
./gradlew test
