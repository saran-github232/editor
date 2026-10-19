# Clipper Studio web app

This guide covers the web app only. The Kotlin/Android app is unchanged.

## Run locally

Use Node.js 22.12 or newer. In PowerShell:

```powershell
cd C:\AndroidPro\clipper\web
npm install
npm run dev
```

Open **http://127.0.0.1:4317/**. Keep the terminal running. For a production build:

```powershell
npm run build
npm start
```

Run these commands from `web`, not the Android project root. If PowerShell blocks `npm.ps1`, use `npm.cmd run dev`. If the port is occupied, use the already-running Clipper instance or stop that instance before starting another. To choose a different port:

```powershell
$env:PORT='4320'
npm run dev
```

Use the exact `127.0.0.1` address printed in the terminal. The development WebSocket shares the app server, avoiding the old fixed-port conflict. Reload the page after restarting the server to renew the local session cookie.

If FFmpeg is missing after installation, npm may have blocked its install script. From `web`, run `node node_modules/ffmpeg-static/install.js`, then restart. `CLIPPER_FFMPEG` and `CLIPPER_FFPROBE` can point to existing binaries. An `ENOTFOUND registry.npmjs.org` install error indicates a network/DNS failure; retry after restoring registry access.

## Trim, merge, review and download

1. Open **Batch clips**, then **Import footage**. Source videos may be up to **3 hours**. The byte limit is 100 GB or available source-storage capacity minus a 512 MB reserve, whichever is smaller.
2. Play the source video. For a container or codec the browser cannot decode, choose **Create compatible playback preview**. This may take time for long footage. It creates an H.264/AAC preview; exports still read the original source.
3. Choose **Trim ranges**, **Merge clips**, or **Auto split**.
4. Enter start/end values as `HH:MM:SS` or `HH:MM:SS.mmm`. For example, `00:01:20` to `00:20:35`, or `01:02:30` to `02:01:20`. These ranges keep the selected footage. Use the play button beside a range to check it. **Mark start/end** uses the current player position for the last focused range.
5. Add, remove or reorder ranges. Separate trims may be longer than three minutes. In **Merge clips**, each individual range is limited to three minutes. Two three-minute ranges produce one video of approximately six minutes, in the displayed order. Merging currently combines ranges from the same source.
6. Add or generate timed captions, review their words and timestamps, then render.
7. Choose **Review** on a finished result. Watch it, then explicitly choose **Download video**, **Video + captions (.zip)**, or **Save to Google Drive**. No browser download or Drive upload begins automatically.
8. **Edit settings & render again** restores the ranges and captions for another render. **Discard** removes the render. It does not delete the source or previously downloaded files.

In Review, use **Finished part** to switch between rendered clips, **Preview last 10 seconds** to check an ending, or **Edit this clip** to add text and emojis without leaving Review. **Change trim ranges** restores the source range separately. If an older preview file is missing, **Rebuild playable preview** creates a new precise-cut render from the original and opens it when ready. This requires the imported original to still be available and does not download anything automatically.

MP4 downloads include burned-in captions when captions were supplied. The ZIP includes both the MP4 and editable SRT. Caption times are trimmed and rebased to each output, including the merged sequence. Auto split still supports 60, 90, 120, 150 and 180-second parts.

## Edit a finished trim or merge

1. Render your trim, auto-split part, or merged sequence and open **Review**.
2. Choose **Edit this clip**, then **Add text or emoji**. Add titles such as “Part 1”, short captions, or paste emojis. Up to 20 layers can overlap.
3. Set each layer's start/end in seconds on the **finished clip**, starting at zero. Adjust horizontal/vertical position, size, font, bold/italic style, color, and the dark readability background. Click **Choose emoji** to browse categories or search by name, then click an emoji to insert it at the text cursor. Color swatches offer quick choices; **Custom text color** supports other colors. Use multiple layers for timed captions.
4. Scrub or play the video to preview placement and timing. Draft text settings survive closing and reopening the editor in the same browser tab.
5. Choose **Render final clip**. Wait for the finished video to open in Review, then download or save to Drive explicitly. Nothing downloads automatically.

The new render keeps the finished clip's dimensions and audio, including existing burned captions. Fonts, text colors, and color emojis are drawn by the same browser canvas routine used for the preview, then baked into H.264 video. The font selector offers eight local font families; missing fonts use the system fallback. No font download is required. The offline emoji picker contains all 3,781 fully-qualified entries in the Unicode Emoji 16.0 catalog, including skin-tone variants. Search and categories cover the full catalog; **Show more emojis** reveals additional results. Emoji appearance and support depend on installed system fonts. The catalog is lazy-loaded when the picker opens. Data source: [Unicode Emoji 16.0](https://unicode.org/Public/emoji/16.0/emoji-test.txt), distributed under the [Unicode license](src/data/UNICODE-LICENSE.txt). Overlay artwork is rasterized at up to 1920 pixels wide, then scaled to the video. Text added here is a visual layer, not an SRT cue; existing SRT sidecars are preserved. Keep text inside the preview frame to avoid clipping. Already burned captions cannot be removed here; change the original render settings to replace them.

Edits render through the same queue with Pause/Resume in the results list. Each edit keeps its own temporary copy of the input so discarding its parent preview does not interrupt it. Temporary edits and inputs expire after 24 hours. Browser-tab drafts do not preserve expired media. Failed edits retain their draft for retry; final renders can receive additional layers, but previously baked layers are no longer individually editable in that final file.

Merge joins now reset timestamps and trim audio padding before joining clips, instead of copying separately encoded AAC packets across boundaries. This adds a final encoding pass but avoids accumulated padding gaps. Rendering still takes time depending on clip length and hardware.

## Video frame ratios and platform guide

Use the **ratio menu beside the timeline preview controls**, or open **Settings → Aspect ratio**. Each choice shows width × height in pixels and a short use guide. Custom width and height remain available in timeline Settings. A ratio chosen before importing footage is retained.

For a trimmed or merged result, open **Review → Edit this clip → Video frame ratio**. Choose **Fit entire video** to keep the whole image with black padding, or **Fill frame** to crop from the center. The preview shows that placement, and **Render final clip** produces the selected dimensions before you download. You can change only the frame, or add timed text and emojis as well. Frame settings are retained with the draft in the current browser tab. Crop mode can remove existing burned captions, so check the preview.

| Ratio | Width × height | Starting use |
| --- | --- | --- |
| 16:9 | 1920 × 1080 | YouTube landscape, tutorials |
| 9:16 | 1080 × 1920 | Instagram Reels, YouTube Shorts, Stories |
| 1:1 (same as 9:9) | 1080 × 1080 | Square Instagram/Facebook feed layouts |
| 4:5 | 1080 × 1350 | Portrait feed layouts |
| 3:4 | 1080 × 1440 | Tall portrait compositions |
| 4:3 | 1440 × 1080 | Classic footage and presentations |
| 3:2 | 1620 × 1080 | Landscape photo-based videos |
| 2:3 | 1080 × 1620 | Portrait photo-based videos |
| 21:9 | 2520 × 1080 | Ultrawide compositions |
| 1.91:1 | 1910 × 1000 | Wide social layouts |

These are layout starting points, not a promise that every upload surface accepts every format. Ratio describes width relative to height, not duration. The timeline canvas controls export shape; check the export dialog for resolution scaling. Finished-clip presets export at the exact sizes above, which can upscale small sources without adding detail. Timeline clips can be moved/scaled individually; finished-clip Fill uses a center crop.

Platform guidance was checked against [YouTube's aspect-ratio guide](https://support.google.com/youtube/answer/6375112), [YouTube's Shorts guide](https://support.google.com/youtube/answer/15424877), and [Meta's Reels guide](https://www.facebook.com/business/ads/facebook-instagram-reels-ads) on 19 October 2026. Check the destination's posting preview, especially for feed crops and video posts shown as Reels.

## Quality

| Mode | Behavior |
| --- | --- |
| Original copy | Copies compressed video/audio packets. No re-encoding, text overlays or resizing. Cuts follow keyframes and may not match requested times exactly. Not offered for merge. |
| Precise cuts | H.264 CRF 16 with AAC audio up to 320 kbps. Supports precise trim ranges, bottom captions, scaling and merge. Uses original dimensions by default and never upscales. Re-encoding is not mathematically lossless. |

The compatibility player is a separate preview, potentially at a lower resolution. It never replaces the source used for native exports. Original-size odd dimensions may round down by one pixel for H.264 compatibility. HDR sources detected by transfer metadata are rejected in precise mode to avoid silently treating them as SDR; use original copy. Full HDR/color-management support is not implemented.

The full timeline editor is a separate browser renderer for effects, text and multitrack editing. Its export settings are separate from native trim/merge. **Save to my media library** is off by default. Three-hour effects-heavy browser rendering and production 4K/HDR workflows are not certified by the small regression fixtures.

Timeline edits autosave. Use **Save project** before reloading or leaving, and wait for **All changes saved**. The editor warns if you try to close the page with unsaved changes.

## Captions: NVIDIA Parakeet and Gemini

Install the speech client once:

```powershell
npm run setup:speech
```

This installs the Python Riva client in `web/.venv` and retains the offline speech dependencies. Python 3 must be installed. You can use `CLIPPER_PYTHON` to select another Python executable.

Enter API keys in **Cloud captions > API keys for this server session**, or copy `.env.example` to `.env` and fill it privately:

```dotenv
GEMINI_API_KEY=your_key
GEMINI_MODEL=gemini-2.5-flash
NVIDIA_API_KEY=your_key
NVIDIA_FUNCTION_ID=71203149-d3b7-4460-8231-1be2543a1fca
```

Restart after changing `.env`. Do not use a `VITE_` prefix for secrets. Session-entered keys stay in server memory until restart; they are not returned by the settings endpoint or stored in browser storage.

The NVIDIA integration targets **parakeet-1.1b-rnnt-multilingual-asr**, using the hosted Riva gRPC endpoint and real word timestamps. Gemini requests structured timed cues. Audio is extracted in 30-second chunks, so a three-hour source does not have to be loaded into RAM or sent in one request. Chunk boundaries and recognition errors can affect timing and words; review the SRT before export.

Choose the preferred provider, then **Generate captions**. Completed captions populate the editable SRT field automatically. If a provider returns quota/rate-limit or transient server errors, the other configured provider is tried. Invalid credentials and configuration errors are shown instead of silently switching. If both providers fail, the task stops with an error. Provider accounts, language support and quotas remain external requirements. Cloud recognition sends audio to those providers only when you request it.

Offline English/Hindi recognition remains available under **Offline speech recognition**. Download its language model once before using it. SRT import and manual editing work without any API keys.

## Google Drive

A shared folder link alone cannot authorize uploads. Create a Google Cloud OAuth **Web application**, enable the Drive API, configure its consent screen/test users, and register this exact redirect URI:

```text
http://127.0.0.1:4317/oauth/drive/callback
```

Set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` in `web/.env`, then restart. Use the matching port in the redirect URI if you change `PORT`.

In a rendered preview, expand **Save to Google Drive**, connect your Google account, complete consent, return to Clipper and press **Refresh connection**. Paste a **folder** link, then choose **Upload video + captions**. The connected account must be allowed to add files to that folder. The app requests the Drive OAuth scope because arbitrary existing folder links need access beyond app-created files. This is an explicit Google consent step; the app does not change folder sharing permissions.

Video and SRT are uploaded separately using streaming/resumable-upload sessions. Tokens are held in server memory; reconnect after a restart. If an upload fails partway through, check Drive before retrying because a previous file may already exist. Automatic upload-resume across process restarts is not implemented.

References: [NVIDIA hosted Parakeet API](https://build.nvidia.com/nvidia/parakeet-1_1b-rnnt-multilingual-asr/api), [Gemini audio documentation](https://ai.google.dev/gemini-api/docs/audio), [Drive upload documentation](https://developers.google.com/workspace/drive/api/guides/manage-uploads).

## Timeline Pro tools

Open **Pro tools** beside Save project in the timeline editor. Select a clip using the panel dropdown or the timeline.

- **Motion presets:** video fade in/out, slow zoom, slide-up entrance, and audio fade in/out. These create editable keyframes, so you can refine them in the graph editor. Choose the fade/entrance duration first. Clips must be at least three frames long.
- **Framing:** fit the complete media clip or fill the canvas with a centered crop. These reset the selected clip's position, rotation, scale and associated keyframes. The ratio menu controls the project dimensions.
- **Speed and sound:** 0.25x to 4x playback with pitch preservation, gain presets, and mute. Speed changes duration without moving adjacent clips, so check gaps and overlaps. Set speed and gain before adding fades.
- **Titles:** add a title card or lower third at the playhead, with a background plate and fade. Each is a separate editable text layer. Change fonts, color, positioning and text in Properties. A title near the sequence end can extend it.
- **Review:** preview from the selected clip, undo/redo edits, save, reopen, and export using the normal timeline renderer. Presets replace only their affected animation channels; Undo restores the previous edit.

These tools complement the existing timeline, keyframes, masks and blend modes. This is a focused local editor, not full Premiere Pro or After Effects feature parity. Heavy effects, long 4K sequences and all codec combinations still need separate validation.

## Storage

- Imported source files and saved project metadata live in `web/.clipper-data` by default. Set `CLIPPER_DATA_DIR` before startup to use another drive.
- New native rendered clips live in the operating system's temporary `clipper-previews` folder, outside the project folder. `CLIPPER_PREVIEW_DIR` can change that location. Large files require temporary disk space even before a manual download.
- Completed/paused/failed render previews are removed after 24 hours by a periodic cleanup while the server is running. Old renders from earlier app versions remain in their original export folder until you discard them.
- Compatible playback copies also use temporary storage. Imported originals are retained for later edits. Downloaded files are controlled by your browser's download settings.
- The local server binds to loopback and checks the Host, Origin and session cookie. It is intended for local use, not public hosting.

## Tests and current validation

```powershell
npm test
npm run test:e2e
npm run build
```

The browser tests use installed Microsoft Edge, isolated test data on port 4322, and synthetic footage. Artifacts go under `web/test-results`. The API suite uses port 4318.

See [VALIDATION.md](VALIDATION.md) for actual results and outstanding live-provider checks. Synthetic tests establish local workflow behavior, not recognition accuracy on every language, performance on every three-hour movie, or production parity with Premiere Pro.

### Latest checked results

On 19 October 2026, all 22 unit/API/media checks and all eight Edge browser workflows passed (seven regressions plus the new Pro tools export test). Tests render every frame preset in Fit and Fill modes, check output dimensions and audio, verify timed text pixels, and confirm portrait downloads from both editors. The timeline ratio survives reload and first-clip import. The Pro tools workflow also verifies save/reopen, undo/redo, retiming, editable motion channels, titles and rendered video/audio fades. Live NVIDIA/Gemini recognition and Drive uploads still require account configuration and have not been verified with live credentials. See `VALIDATION.md` for build results and details.

### Clone from GitHub

```powershell
git clone https://github.com/saran-github232/editor.git
cd editor/web
npm install
npm run dev
```

Secrets, imported footage, temporary renders, installed dependencies, local Python environments and test outputs are excluded from source control. Configure your own `web/.env` using `.env.example` after cloning. The retained Android source is included, but these changes and checks target the web app only.
