# Validation log

Updated 2026-10-14. Executed checks are separate from planned work.

## Web

- Production Vite build passed. Large bundle warning remains.
- Dependency install audit: zero vulnerabilities after router/CSS-tool upgrades. Not a full security audit.
- First native run: 9/10 tests passed. Exact export, dimensions, SRT rebasing, compressed packet conservation, ranges, project round trip and planning passed. Host-header harness corrected from fetch to node:http; rerun pending.
- Browser dashboard loaded, named project created/opened with media, preview, timeline and properties panels. Further E2E tests in progress.
- Real movie footage, 4K/HDR, multi-hour edited timeline exports, Drive OAuth and web speech are not yet validated.

## Android

- 19 unit tests passed; debug APK, lint and unsigned release bundle built before web reprioritization. Later small hardening changes need final rebuild.
- Main activity launched; screenshot in `screenshots/studio.png`.
- Instrumentation tests did not execute: emulator/package service failed. Do not count as passed.
- Vosk GNU_RELRO failed strict 16 KB alignment check; JNA passed. Play release blocker until resolved/verified.
- Real Drive OAuth requires owner setup/device tests.
- Codex Security scan failed to start on unreadable Gradle lock; no scan ID or completed audit report exists.
