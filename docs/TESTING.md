# Testing Guide

## Minimum local checks before merge

```bash
./gradlew assembleDebug
./gradlew test
```

For release-related work also run:

```bash
./gradlew assembleRelease
```

## Manual regression checklist

### Player basics
- live stream opens
- VoD opens
- stream switching works
- minimize / restore works
- no obvious visual glitches during transition

### Speed control
- current speed is shown correctly
- speed changes update immediately
- state survives orientation and minimize/restore if relevant

### Gestures
- brightness still works
- volume still works
- VoD seek is responsive
- large drags allow fast movement through long VoDs
- gesture conflicts remain acceptable

### Floating chat
- overlay opens correctly
- drag / resize persistence works
- no broken empty-sidebar/floating-chat state

### Layouts
- portrait phone
- landscape phone
- at least one wide/tablet profile
- split-screen if layout code changed

### Stats
- stats screen opens
- rotation while already on stats screen behaves correctly
- no obviously broken spacing in compact / wide layouts
