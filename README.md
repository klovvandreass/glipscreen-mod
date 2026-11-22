# Klovv Screen

Premium native Android screen recorder built with Kotlin.

## Current implementation

The main branch includes:
- A dark midnight-blue dashboard with Home, Videos, Settings, and About navigation.
- Screen capture using Android MediaProjection.
- Hardware video encoding through MediaCodec and MP4 muxing.
- Audio capture options for internal playback audio (Android 10+), microphone, or both where supported.
- Recording countdown, floating controls, and notification actions for Pause/Resume and Save & Stop.
- On-device video library actions for playback, sharing, details, renaming, and deletion.

## Build

Open this repository in Android Studio with JDK 17 and Android SDK 35, sync Gradle, then build the debug APK.

GitHub Actions also builds and verifies the debug APK on pushes to `main`.

## Platform notes

- Android displays its system screen-capture consent before each recording session.
- Internal audio capture depends on Android version, app audio policy, and device support. Some apps block playback capture.
- Floating controls require the user to grant “Display over other apps” permission.
- Recordings are stored locally in `Movies/Klovv Screen`. No recording upload is implemented.
- Device testing is still required across Android versions and hardware encoders before calling the app production-ready.
