# ThystTV

<p align="center">
  <img src="docs/images/ic_launcher_foreground.svg" width="128" height="128" alt="ThystTV icon" />
</p>

A polished fork of [Xtra](https://github.com/crackededed/Xtra) focused on better playback UX, floating chat, local watch stats, and improved large-screen behavior.

## Why ThystTV?

ThystTV pushes the Twitch viewing experience further in a few specific areas:

- **Floating chat overlay** for full-screen viewing
- **Local stats and watch-history insights**
- **Gesture-based playback controls**
- **Large-screen and tablet layout improvements**
- **Selective upstream sync** from Xtra with ThystTV-specific stability priorities

## Highlights

### Floating chat
- Overlay chat while watching in full-screen
- Drag, resize, and adjust opacity
- High-visibility mode for brighter content

### Playback controls
- Horizontal seek gestures for VoDs
- Playback speed controls
- Brightness and volume gestures
- Better feedback during active player interactions

### Local stats
- Daily screen-time tracking
- Favorite channels and loyalty metrics
- Watch streaks
- Category breakdowns and heatmaps
- All stats stay local on-device

## Screenshots

> Replace the placeholders below with current polished screenshots before merging.

| Browse | Full-screen player |
|---|---|
| ![Browse screen](docs/images/screenshots/browse-phone.png) | ![Player screen](docs/images/screenshots/player-phone.png) |

| Floating chat | Stats dashboard |
|---|---|
| ![Floating chat](docs/images/screenshots/floating-chat-phone.png) | ![Stats dashboard](docs/images/screenshots/stats-phone.png) |

## Building

```bash
./gradlew assembleDebug
./gradlew test
./gradlew assembleRelease
```

## Downloads

Use the [Releases](../../releases) page for tagged builds.

## Reporting bugs

Please use the issue templates in this repository. For player regressions and layout issues, include:
- device model
- Android version
- ThystTV version
- reproduction steps
- screenshots or screen recording if relevant

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Project docs

- [Roadmap](docs/ROADMAP.md)
- [Testing guide](docs/TESTING.md)
- [Release process](docs/RELEASE_PROCESS.md)
- [Upstream sync policy](docs/UPSTREAM_SYNC.md)
- [Changelog](CHANGELOG.md)
- [1.2 release plan](docs/RELEASE_1_2_PLAN.md)

## License

ThystTV is licensed under the [GNU Affero General Public License v3.0](LICENSE), same as upstream Xtra.
