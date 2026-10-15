# Validation log

Date: 15 October 2026.

## Confirmed in this work

- 17 unit/API/media checks passed in the final sequential integration run.
- Hidden source-storage folders now serve authenticated range requests, fixing the reproduced source playback failure.
- Native exact splitting retains expected dimensions and produces rebased SRT sidecars.
- Original-copy automatic splitting preserves every compressed video packet, checked by packet hashes.
- Manual range validation accepts hour-based times and rejects invalid ranges and merged ranges over three minutes.
- Upload rejects a synthetic source longer than three hours.
- Merged previews support playback ranges, manual MP4 download and MP4/SRT ZIP bundles.
- Compatibility preview is H.264 with yuv420p and supports byte ranges.
- Provider fallback was tested with simulated quota errors; authentication errors do not trigger fallback.
- Drive folder-link parsing rejects unrelated hosts and file links.
- Browser manual test: source plays, selected interval previews, a captioned native render completes and its download link works.
- Browser manual test: a fresh timeline project imports synthetic footage and exports successfully without the reported JSON height failure. Metadata recovery and a clear invalid-canvas check were added for missing dimensions.
- NVIDIA Riva client installed in the web virtual environment; request construction checked against the installed client API.

## Final automated/browser checks

- `npm test`: **17 passed, 0 failed** (29.79 seconds).
- `npm run test:e2e`: **2 passed** in Microsoft Edge (23.2 seconds). Covers source import/playback, range merge, captions, review, explicit MP4/ZIP downloads, settings restoration, Drive configuration feedback, and timeline save/reload/export.
- `npm run build`: **passed** after the final save changes (13.53 seconds). Vite reports a large JavaScript bundle warning; this is a remaining startup-performance optimization, not a build failure.
- Browser testing exposed a save/reload race. The editor now has an explicit **Save project** button, a dirty-state indicator and an unload warning. Saving waits for any in-flight write; navigation through the host links waits for saving too.
- One overlapping test run encountered a server connection reset. The subsequent sequential API and browser runs passed; these results do not claim concurrent stress-test coverage.

## User-uploaded reference footage

The user uploaded `Least Eligible Bachelor S01E01.mkv` through the app. Its metadata reported 29 minutes 10.4 seconds, H.264, 1280 × 720.

- Selected `00:01:20–00:04:20` and `00:10:00–00:13:00`, then merged them in that order.
- Used clearly labelled **test captions**, not an automatic transcript, to check timing across the join.
- Render completed and played in the Review dialog. The MP4 and ZIP were downloaded through the app's own buttons into the browser's Downloads folder.
- Downloaded MP4: **360.022 seconds, 1280 × 720, 25 fps, H.264, AAC, BT.709**.
- A full FFmpeg decode of the downloaded MP4 completed without errors.
- ZIP CRC check passed. It contains `Merged.mp4` and `Merged.srt`; the bundled video's SHA-256 matches the separately downloaded MP4.
- SRT first cue starts at `00:00:00`; the second source range's cue starts at `00:03:00`, confirming the caption rebase across the merge.

This validates the six-minute merge on the supplied footage. It does not establish lossless re-encoding, live speech recognition accuracy or full three-hour rendering performance.

## External checks still required

- Actual NVIDIA/Gemini recognition, quota fallback against live accounts, and caption accuracy need configured API keys and representative speech.
- Google OAuth consent, permissions on a real folder, and completed Drive upload need a configured Google Cloud OAuth application and user sign-in.
- Full three-hour high-resolution rendering, HDR and heavy timeline effects are not certified by these synthetic tests.

## Preview recovery follow-up

- The reported error was reproduced on an older job whose output metadata remained but whose MP4 files were absent.
- Review now distinguishes a missing file from unsupported playback, offers **Rebuild playable preview**, and opens the replacement automatically when rendering completes.
- Added **Finished part**, **Preview last 10 seconds**, and **Edit this clip** controls.
- Rebuilt the user's affected two-minute source range and verified browser playback at 110 seconds with readyState 4 and no media error.
- Final browser rerun: **3 passed (23.7 seconds)**, including missing-file recovery, playback and restoring the exact editing range.
- Final production build: **passed (9.05 seconds)**; the existing large-bundle warning remains.
