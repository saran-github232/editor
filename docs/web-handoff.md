# Clipper web handoff

Updated 2026-10-14. The user changed priority to a **PC-first local web editing studio** and asked to keep working now, then continue tomorrow if usage runs out. No wake-up was scheduled. Preserve Android; its status is in `android.md` and `release-readiness.md`.

## Architecture

- `web/src`: React host, projects dashboard, local adapters, batch workspace, photo adjustments.
- `web/vendor/artcraft-video-editor`: actual licensed upstream code at `d4e4ac69bbd93d237eaf5d0260bc6a5ff3a5e373`, with original MIT/Apache licenses and NOTICE.
- `web/server/index.mjs`: loopback-only Node/Express server, same-origin session, streamed uploads, atomic JSON, sequential native FFmpeg jobs.
- `web/server/media.mjs`: shell-free process calls, metadata, clip planning, no-upscale dimensions, SRT rebasing.
- Data: `web/.clipper-data`, overridden by `CLIPPER_DATA_DIR`. Never delete user media when updating code.

## Upstream modifications

Standalone TypeScript configuration replaces the original monorepo dependency. Restored local audio/video use metadata and range requests instead of fetching full blobs; decoded media sources share a bounded range helper. Photos still restore as files. Fonts use installed system fonts without remote font requests. Autosave subscribes to media-bin changes and surfaces failures. Host adapters persist projects/media on disk and save exports to Downloads/local media library.

## Validation so far

Production Vite build passed (large bundle warning). npm install reports zero vulnerabilities after React Router/Tailwind upgrades. Native tests passed exact 125s splitting into 60/60/5s clips, dimensions, SRT boundary rebasing, HTTP ranges and packet-by-packet compressed video conservation. Initial hostile-Host test used fetch, which rewrote Host; corrected to node:http, rerun pending. Browser created/opened a persisted project and displayed editor panels; full E2E checks continue.

## Next work and limits

1. Complete browser import/edit/save/reopen/export and photo checks; record results in `validation.md`.
2. Generate >3-hour test media. Synthetic duration/ordering tests are not real 4K performance tests.
3. Add web automatic speech through a local streamed worker. Android Vosk is implemented but cannot be directly reused in a browser.
4. Add web Google Drive OAuth/resumable upload; owner OAuth registration needed. Never claim account integration tested without it.
5. Browser timeline export still buffers output and mixed audio. Full multi-hour effects-heavy exports may exhaust memory. Native batch splitting streams disk media; proxy generation and streaming edited-timeline output remain needed.
6. Exact native export is SDR H.264/AAC, not HDR preservation or mathematical losslessness. Original copy cuts at keyframes and cannot burn captions.
7. Full Adobe parity (3D, tracking, rotoscoping, plugins, professional color management/interchange) is not implemented.
8. Android release blockers: Vosk 16 KB RELRO issue, device tests, real Google OAuth, signing. Keep Android work intact.

## Commands

From `web`: `npm install`, `npm run build`, `npm start`; open `http://127.0.0.1:4317`. `npm run dev` uses Vite middleware. If npm blocks ffmpeg-static's install script, run `node node_modules/ffmpeg-static/install.js`. Tests: `npm test`, with synthetic files under `web/test-results/api`. Do not run against real user data.

See `upstream-research.md` for scope and attribution. No upstream paid provider integrations, keys, analytics, logos or excluded sample media were copied.
