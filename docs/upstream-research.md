# Editing references and reuse

Reviewed 2026-10-14.

## Artcraft

[Repository](https://github.com/storytold/artcraft), [product site](https://getartcraft.com/). The reusable React package is `frontend/libs/components/video-editor`. It exposes host adapters for projects, media, auth, exports and notifications, with media bin, preview, timeline, properties, text, masks, keyframes, effects, audio and undoable commands.

Copied at `d4e4ac69bbd93d237eaf5d0260bc6a5ff3a5e373`, under the included MIT license (also offered under Apache-2.0), retaining NOTICE. Dependencies include OpenCut WASM and MediaBunny. No Artcraft branding, restricted sample assets or paid inference backend was copied. The Clipper host is separate integration code.

The package is early-preview software. Its default restore downloads full blobs and export buffers finished output/mixed audio. Clipper replaces local video restore with bounded HTTP ranges, but large edited-sequence export still needs streaming and proxies. Feature presence is not proof every effect is validated.

## Filmcraft

[Repository](https://github.com/storytold/filmcraft). A native Rust editor with GPU rendering and native codec work, not a drop-in React package. Its timeline/easing and rendering architecture are useful references. No Filmcraft binaries or codecs are currently linked. Integrating its engine would be a separate substantial port.

## Adobe

[Premiere Source/Program Monitor documentation](https://helpx.adobe.com/premiere/desktop/get-started/source-and-program-monitor-adjustments/about-source-monitor-and-program-monitor.html) informs media-bin, monitor and timeline organization. Clipper has one sequence preview, not a complete dual-monitor implementation.

[After Effects layer organization](https://helpx.adobe.com/after-effects/desktop/work-with-layers/select-and-arrange-layers/selecting-arranging-layers.html) informs layers, properties and keyframes. No Adobe assets, proprietary code or project-format compatibility is claimed.

## Problems addressed

Local native splitting avoids cloud uploads and browser storage quotas. [MediaBunny URL sources](https://mediabunny.dev/guide/reading-media-files) permit bounded range reads on reopen. Explicit copy/exact modes clarify quality tradeoffs. Numbered clips and per-job folders preserve order. Atomic metadata and resumable jobs protect completed work. Loopback binding, Host/Origin validation and SameSite sessions isolate the local API.
