# Clipper Studio

For current web setup, preview recovery, captions, Drive configuration and test results, see [web/README.md](web/README.md) and [web/VALIDATION.md](web/VALIDATION.md).

A **PC-first video editing studio running on your own computer**, with a browser interface and local Node/FFmpeg server. The Kotlin/XML Android app is retained in `app/`; desktop web is now the main development target.

Clipper combines Artcraft's open-source editing engine with local projects, batch video splitting and a photo workspace. This is a development build, not a complete Premiere Pro or After Effects replacement.

## Start on your PC

Install Node.js 22.12+ (current LTS recommended; tested here with Node 26), then:

```powershell
cd C:\AndroidPro\clipper\web
npm install
npm run build
npm start
```

Open **http://127.0.0.1:4317** in desktop Chrome or Edge. Use that exact address. No account/cloud backend is needed for local editing. Use `npm run dev` for development.

If npm blocks ffmpeg-static's install script, run `node node_modules/ffmpeg-static/install.js` from `web`, then restart. Override binaries using `CLIPPER_FFMPEG` and `CLIPPER_FFPROBE` if needed.

To use a larger drive, set `$env:CLIPPER_DATA_DIR='D:\ClipperData'` before starting. Existing media is not moved automatically. Back up the data folder.

## Workspaces

| Workspace | Implemented |
|---|---|
| Projects | Named sequences, recent projects, local JSON persistence, autosave |
| Editor | Video/audio/photos, multitrack timeline, trim/split, text, transforms, effects, keyframes, masks, audio controls, undo/redo, browser-rendered export through upstream engine |
| Batch clips | Native splitting into 60/90/120/150/180-second parts, final remainder, numbered files, captions/part labels, editable timed SRT, downloads, pause/resume |
| Photo studio | Brightness, contrast, saturation, rotation, horizontal flip, text overlay, original-size PNG export |
| Settings | Storage location, quality rules, browser requirements and implementation status |

Batch splitting acts on the **selected source file**, not the editor timeline. Export an edited sequence to the local library first, then split that rendered source if needed.

## Quality and long footage

- **Exact cuts:** H.264 CRF 18/AAC re-encoding, with captions/resizing. Output dimensions are checked before committing each clip.
- **Original copy:** copies compressed packets in one pass, cutting at source keyframes. Part durations vary. No overlays or resizing.
- Original, 420p, 720p, 1080p and 2160p short-edge choices preserve aspect ratio and never upscale smaller sources. Odd dimensions may round down for codec compatibility.
- Exact cuts and burned captions are not mathematically lossless. Native exact output is currently SDR; HDR preservation is not certified.
- Native batch processing streams disk files, with a 24-hour duration bound and an upload cap of 100 GB or available storage minus a reserve, whichever is smaller.
- Browser timeline playback uses range-backed restored video. Timeline export still uses upstream memory-based encoding/audio mixing. Full three-hour effects-heavy rendering and real 4K performance need further work and hardware validation.

Files are stored in `web/.clipper-data/exports/<job-id>/Part-0001.mp4`, etc. Exact-mode resume retains finished clips. Copy-mode resume restarts its continuous pass. Running jobs become paused after server restart.

## Still being implemented

Automatic speech subtitles and Google Drive upload exist in Android but **are not yet connected in the web host**. Web currently accepts edited SRT text. Drive requires your own Google Cloud OAuth registration; no secret is checked in.

Full Adobe parity, 3D compositing, tracking, rotoscoping, plugins, professional color management/interchange, proxies and native streaming export of edited long timelines remain future work. The desktop timeline is not optimized for phones; dashboard/batch pages are responsive.

## Testing and security

```powershell
cd web
npm test
npm audit
npm run build
```

Tests use synthetic media. See [validation](docs/validation.md) for actual results and limitations. The server binds only to loopback, checks Host/Origin, uses an HttpOnly SameSite cookie, bounds uploads, generates media IDs and starts processes without shell interpolation. **Do not expose this server to a LAN or the internet.**

The external Codex Security scan did not start on the earlier Android tree because a Gradle lock file could not be read. No completed external audit is claimed. Manual/dependency/API checks are recorded separately.

## Continuation and documentation

- [Current handoff and next steps](docs/web-handoff.md)
- [Upstream research, reuse and licensing](docs/upstream-research.md)
- [Android build and features](docs/android.md)
- [Android release blockers](docs/release-readiness.md)
- [Local web architecture](docs/web-contract.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

Artcraft source is vendored with MIT/Apache licenses and NOTICE. Filmcraft and Adobe are architecture/design references; Clipper is not affiliated with them. The pinned source version and local modifications are documented in `web/vendor/artcraft-video-editor/CLIPPER-NOTICE.md`.
