# Upstream Sync Policy

## Goal

Keep ThystTV reasonably current with useful upstream Xtra fixes without breaking ThystTV-specific UX.

## Strategy

ThystTV uses a selective upstream sync approach.

That means:
- small safe fixes are preferred
- risky broad rewrites are evaluated carefully
- ThystTV-specific player and UX behavior takes precedence over blindly following upstream

## Usually accepted
- crash fixes
- compatibility fixes
- networking fixes
- isolated correctness fixes
- low-risk cleanup that helps maintenance

## Usually deferred
- large unstable player rewrites
- UI changes that conflict with ThystTV-specific UX
- architectural churn with high regression risk and low user value

## Requirements for upstream sync PRs
- explain what upstream changes were taken
- explain what was intentionally not taken
- list the retest areas
- note any manual conflict resolution

## Minimum retest areas
- live stream playback
- VoD playback
- stream switching
- floating chat
- minimize / restore
- gestures
- stats screen load and layout
