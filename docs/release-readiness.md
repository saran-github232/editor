# Release readiness

## Required owner setup

- Choose the final package ID, publisher name and store branding. `app.clipper` is a development identifier.
- Configure Google Cloud Drive API, consent screen, testing audience, Android OAuth clients and debug/Play signing certificate fingerprints. Drive is implemented but cannot be tested against an account without this registration and user consent.
- Create and secure the release upload key; use Play App Signing. No production signing secret is stored in this repository.
- Host the completed privacy policy and provide a support contact, screenshots, content rating and accurate Data safety answers.
- Declare foreground service use in Play Console. Android 15+ uses `mediaProcessing` for exports, `dataSync` for uploads/model downloads; Android 14 uses `specialUse` for processing because `mediaProcessing` is unavailable on that OS. The special-use declaration needs review and justification.

## Current compatibility blocker

The packaged Vosk 0.3.75 libraries for arm64-v8a and x86_64 have 16 KB-aligned LOAD segments, but their GNU_RELRO end address is not 16 KB aligned. The stricter static check in `scripts/check_native_alignment.py` fails them. JNA 5.18.1 passes both checks. Do not treat a successful APK build or a 4 KB emulator run as proof of full 16 KB device compatibility.

Resolve by using a verified upstream build or rebuilding Vosk and its native dependencies with a current NDK and the required page-size/RELRO linker settings, then re-run ELF/ZIP alignment checks and a real 16 KB emulator/device test. Do not patch ELF headers or remove RELRO to hide the issue.

## Validation scope

Unit tests cover continuous clip coverage, all selected lengths, the 200-minute bound, safe filenames, remainder clips, copy-mode constraints, keyframe deduplication, no upscaling, SRT clipping/round-tripping, invalid timestamp rejection, the command parser, and audio resampling clocks.

Instrumentation tests generate a synthetic source and exercise UI launch/navigation, native speech-library loading, exact captioned exports and compressed-video sample conservation in Original copy mode. Test results belong in `app/build/reports`; a test's presence is not evidence that it passed. See `docs/validation.md` for observed results.

Before release, test on physical low/mid/high-range devices: 200-minute sources, 4K and HDR, rotated and variable-frame-rate video, silent/no-audio files, multiple audio tracks, unsupported codecs, low disk, process death, cancellation during model download/transcription/export/upload, foreground-service timeout, subtitle readability, large fonts, screen readers, and interrupted or quota-limited Drive uploads. Test speech recognition with representative English and Hindi recordings and manually review accuracy.

Long jobs are user-started foreground work. Android's execution limits and device thermal/codec constraints still apply. After process death or a timeout, users explicitly resume. There is no automatic boot restart.

## Security-related implementation controls

- No broad storage, microphone, account-list or contacts permissions.
- Only the launcher activity is exported; services and the file provider are private. Shared file grants are temporary and scoped to `files/exports/`.
- Cleartext network traffic is disabled and system trust anchors are used.
- Drive requests use only `drive.file`; tokens are memory-only, network redirects are disabled, and resumable session URLs must remain on the expected HTTPS Drive endpoint.
- Folder and file IDs are preallocated and persisted, preventing ambiguous retry responses from creating duplicate uploads.
- Network retry loops are bounded and respect the user's metered-network setting.
- Speech archive paths are canonicalized beneath a staging directory; entry count, compressed download size and expanded size are bounded. Model paths are fixed constants.
- Export filenames cannot introduce filesystem traversal. Atomic project writes and partial-file cleanup protect interrupted jobs.
- No server secrets, ad trackers or analytics SDKs are included.

The Codex Security plugin's full audit did **not** complete: its startup failed with `Could not read local file: .gradle\9.6.1\checksums\checksums.lock`. This document records implementation controls and release work; it is not a completed security audit report. Re-run the plugin audit against the source after the build finishes, excluding generated/cache files as appropriate.
