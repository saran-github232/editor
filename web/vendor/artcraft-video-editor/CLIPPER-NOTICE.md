# Clipper integration

Vendored from https://github.com/storytold/artcraft at commit `d4e4ac69bbd93d237eaf5d0260bc6a5ff3a5e373`, path `frontend/libs/components/video-editor`, retrieved 2026-10-14. Used under the included MIT license (also offered under Apache-2.0). Preserve the upstream LICENSE-MIT, LICENSE-APACHE and NOTICE. No Artcraft logos, paid providers, sounds, skyboxes or other excluded media were copied.

Clipper modifications are recorded in `docs/web-handoff.md`: local disk adapters, range-backed restored media, branding, local-only fonts, and integration fixes. This is an upstream-derived editor, not a claim that Clipper authored the underlying engine. Dependencies retain their own licenses, including OpenCut WASM and MediaBunny.

Additional web integration changes on 2026-10-15: native metadata recovery for imported/restored media, explicit validation of canvas dimensions, media-library export disabled by default, and save flushing that awaits in-flight writes and reports unsuccessful saves. See `web/README.md` and `web/VALIDATION.md` in the containing Clipper project for behavior and validation results.
