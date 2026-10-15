# Clipper

A native Android video-splitting studio written in **Kotlin and XML Views**. Android 10+; targets Android 16 (API 36). The web app is intentionally deferred, as requested.

## What is implemented

- Import a video through Android's document picker. Supported duration: 1–200 minutes.
- Split the entire video into 60, 90, 120, 150 or 180 second parts, including the final remainder.
- Two export paths: exact cuts with Media3 Transformer, or original compressed-sample copying at keyframes.
- Original, 420p, 720p, 1080p and 2160p short-edge resolutions. Aspect ratio is preserved and smaller inputs are never upscaled.
- Editable top/bottom captions and automatic, customizable `Part {part}` labels.
- A local caption command parser, for example `Put "My travel diary" at the top`. This is a small deterministic command grammar, not a general AI chat service.
- Offline automatic speech subtitles using Vosk, with downloadable English (US) and Hindi models. Generate, edit SRT text/timestamps, then export. Burn subtitles into clips or keep them as separate SRT files.
- Dashboard/studio, project library, preview player, settings, progress notification, pause and explicit resume after errors/process death.
- Local clip playback, individual Save/Share, SRT downloads, and batch saving to a user-selected folder.
- Optional Google Drive authorization with `drive.file`, a dedicated folder per project, zero-padded filenames, sequential resumable uploads, bounded retries, and an unmetered-network option.

## Build and run

Open this folder in a current Android Studio. Install Android SDK 36 and use **JDK 21** for Gradle (the app's bytecode target is Java 17). Gradle 9.6.1 and Android Gradle Plugin 9.3.2 are pinned.

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:bundleRelease
python scripts/check_native_alignment.py app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is under `app/build/outputs/apk/debug/`. Release signing is deliberately not configured with a checked-in key. Use Android Studio's Generate Signed Bundle flow with your own upload key.

## Google Drive setup

Local editing does not need a Google account or backend. Before Drive authorization can work:

1. Create or choose your Google Cloud project and enable Google Drive API.
2. Configure Google Auth Platform branding, audience/test users, and the `https://www.googleapis.com/auth/drive.file` scope.
3. Register an Android OAuth client for the final application ID and signing certificate SHA-1. The current development ID is `app.clipper`; choose an ID you own before publishing.
4. Register debug and release certificates separately. With Play App Signing, register the **Play app-signing certificate**, not only the upload certificate.
5. Run `./gradlew :app:signingReport` to inspect the local debug certificate, then connect from Settings on a device with Google Play services.

There is no client secret or service-account key in the app. An Android OAuth client is resolved by package/certificate identity. Access tokens are memory-only. If interactive authorization is needed during a long job, the job stops with a reconnect/resume message. Revoking access does not delete existing Drive files.

## Quality contract

“Original resolution” describes pixel dimensions, not mathematical losslessness. Exact cuts, burned-in captions, and scaling need encoding. The app disables encoder fallback and verifies output dimensions before committing a clip. Device codec availability can cause an export to fail, especially for 4K/HDR.

Original copy uses Android MediaExtractor/MediaMuxer to copy audio/video samples without re-encoding. Boundaries move to the next available video keyframe; duplicate boundaries collapse rather than producing empty clips. It preserves the video timeline, but the first audio packet at a cut can move by an audio-frame interval. MP4-compatible tracks are required. Embedded subtitle tracks, chapters, DRM and arbitrary source-container metadata are not preserved. Use generated SRT files for subtitles.

Automatic recognition is not guaranteed to be accurate. Music, overlapping speakers, accents and language mismatches affect it. English and Hindi are the initial supported speech languages; this is not a multilingual translation feature. A transcript interrupted before completion is regenerated when resumed. Completed video clips and uploaded portions are reused.

## Architecture

`model/` contains deterministic planning, validation, subtitle timing and command parsing. `data/` persists immutable project snapshots through AtomicFile. `media/` inspects and exports video. `speech/` installs bounded language archives and streams decoded audio into the recognizer. `drive/` owns scoped authorization and resumable uploads. `export/ExportService` coordinates one user-started job at a time. `ui/MainActivity` binds the XML layouts.

No cloud server is required for Android. See [the future web contract](docs/web-contract.md) for the versioned job format to reuse when building a web interface.

## Before publishing

This is a working development build, **not yet a production-certified Play release**. Follow [release readiness](docs/release-readiness.md) and [privacy policy draft](docs/privacy-policy.md). In particular, resolve the native Vosk RELRO compatibility issue, configure and test real Google OAuth, run long-video tests on physical devices, complete release signing and provide your public support/privacy details.

Source references: [Media3 transformations](https://developer.android.com/media/media3/transformer/transformations), [Android authorization](https://developer.android.com/identity/authorization), [Drive resumable uploads](https://developers.google.com/workspace/drive/api/guides/manage-uploads), [Vosk model catalog](https://alphacephei.com/vosk/models), [Android foreground service limits](https://developer.android.com/about/versions/15/changes/foreground-service-types), [Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878), [16 KB compatibility](https://developer.android.com/guide/practices/page-sizes).
