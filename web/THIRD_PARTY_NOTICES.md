# Web third-party notices

The MIT license in web/LICENSE applies to original Clipper web code by GenZ Labs. It does not replace the licenses of dependencies, vendored code, fonts, emoji data, codecs or downloaded models.

- Artcraft video editor: used under MIT; upstream also offers Apache-2.0. Preserve vendor/artcraft-video-editor/LICENSE-MIT, LICENSE-APACHE, NOTICE and CLIPPER-NOTICE.md. Local changes include adapters, preview controls, canvas ratio UI, rendering integration and save handling.
- MediaBunny: MPL-2.0, as declared by the installed package. Its source and license remain under MPL-2.0. https://github.com/Vanilagy/mediabunny
- OpenCut WASM: MIT, as declared by the installed opencut-wasm package. Preserve its upstream notices and any separately licensed incorporated components.
- Unicode emoji data: Unicode license in src/data/UNICODE-LICENSE.txt.
- FFmpeg and FFprobe: the binary build determines applicable LGPL/GPL obligations. ffmpeg-static's package license is not a replacement for the binary's license. https://ffmpeg.org/legal.html
- React, Vite and other packages keep their own licenses. See package-lock.json for the exact dependency tree and each distributed package's LICENSE/NOTICE files.
- Speech runtimes and models are installed separately and retain their providers' licenses. Cloud services are governed by their providers' terms.

This is a key-component inventory, not a complete redistribution compliance audit. Before shipping bundled binaries, verify their build configuration and distribute all required notices and corresponding source or source offers where applicable. No third-party code is relicensed by the Clipper MIT license.
