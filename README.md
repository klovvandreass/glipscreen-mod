# GLIP Screen MOD

Native Android screen-recorder project foundation.

## Current status
This repository is a buildable project foundation, **not a finished screen recorder**. The actual MediaProjection capture pipeline, MediaCodec video encoding, MP4 muxing, internal audio capture, microphone mixing, floating controls, and gallery are not implemented yet.

## Build
Open this repository in Android Studio with JDK 17 and Android SDK 35, sync Gradle, then build the debug APK.

## Privacy
Recordings are intended to remain on-device. Android's system screen-capture consent must be shown for every capture session. No hidden recording or network upload behavior is included.
