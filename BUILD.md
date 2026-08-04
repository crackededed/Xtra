# Building Xtra with Docker

The repository includes an Xtra-specific Android build image with JDK 21, Android SDK 37, and build-tools 36. It does not install Node.js or the Android NDK.

Build the image once, then run the default debug build:

```powershell
docker compose -f docker-compose.android.yml build
docker compose -f docker-compose.android.yml run --rm android-build
```

The Compose service keeps Gradle distributions/dependencies in `xtra-gradle-cache` and the Android SDK in `xtra-android-sdk`. Later builds reuse both volumes. The Windows `gradlew` line endings are normalized only in a pipe inside the container; the checkout is not modified.

To build a release APK:

```powershell
docker compose -f docker-compose.android.yml run --rm android-build :app:assembleRelease --no-daemon
```

The APKs are written to `app/build/outputs/apk/` in the workspace. Rebuild the image with `docker compose -f docker-compose.android.yml build --pull` when the toolchain definition changes.
