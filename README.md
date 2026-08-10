# Xtra for Twitch

<p align="center">
  <img src="https://github.com/thiscallnet/Xtra/raw/master/app/src/main/ic_launcher-web.png" width="96" alt="Xtra logo">
</p>

<p align="center">
  A community-maintained fork of <a href="https://github.com/crackededed/Xtra">Xtra</a>,<br>
  focused on practical fixes and Twitch features that are useful in everyday viewing.
</p>

## What this fork adds

Compared with the upstream project, this fork currently includes:

- More reliable live notifications with persisted follow IDs and Helix fallback.
- More reliable playback across background playback, picture-in-picture, and app task removal, with automatic recovery for interrupted live streams.
- Channel Points in chat: balances, custom icons, watch streaks and streak sharing, text-input rewards, voting, and redemption, including web GQL login support.
- Searchable emote pickers limited to emotes belonging to the active channel.
- A reorganized settings experience with search, categorized pages, and clearer playback and background-playback controls.
- Fork-hosted releases and update links for existing Xtra installs.

### Recent UX and playback improvements

- A clearer main screen with compact live cards, live badges, viewer counts, uptime, language and tag details, thumbnail fallbacks, and accessible actions.
- More useful list states: loading, successful empty results, refresh errors, and page errors now have distinct feedback and retry paths while preserving content that is already loaded.
- A more efficient live-player layout with edge-to-edge landscape chat, denser chat messages, combined multiview chat spacing, and fullscreen horizontal controls that stay visible after a tap.
- Account login/logout is grouped at the bottom of Settings, with a gear entry point, searchable categories, and clearer notification permission and blocked-state guidance.
- Accessibility improvements across cards, menus, chat actions, and dynamic player controls, including clearer labels for screen readers.

### VAFT ad avoidance

The optional **VAFT ad avoidance** setting uses alternate Twitch playback sources when a live ad is detected, keeps playback hidden or muted while no clean source is available, and returns to the configured source once it is verified clean again. It is best-effort: Twitch controls ad delivery, so results may change when Twitch changes its playback system.

<p align="center">
  <img src="./docs/images/channel-points-rewards.jpg" width="320" alt="Channel Points rewards and watch streaks">
  <img src="./docs/images/channel-points-navigation.jpg" width="620" alt="Channel Points balance in the navigation bar">
</p>

## Download

[<img src="https://f-droid.org/badge/get-it-on.png"
      alt="Get it on F-Droid"
      height="80">](https://f-droid.org/packages/com.github.andreyasadchy.xtra/)

You can find released APKs [here](https://github.com/thiscallnet/Xtra/releases/tag/latest).

[![Join the Xtra community on Discord](https://img.shields.io/badge/Join%20the%20community-Discord-5865F2?logo=discord&logoColor=white)](https://discord.gg/2cKy8DNgPX)

## License
Xtra is licensed under the [GNU Affero General Public License v3.0](LICENSE).
