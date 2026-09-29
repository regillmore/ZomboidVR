# Changelog

## Unreleased

- Import the working live GPU stereo prototype with a fixed-depth UI, SteamVR overlay, adjustable strength/convergence, and start/stop/recenter controls.
- Include the zoom-coverage correction: copy physical viewport pixels rather than the logical projection extent.
- Include the UI flicker correction: capture immediately before the real UI draw, excluding operation-only batches.
- Keep the mouse pointer in selected flat mode and the no-world fallback; report explicit flat mode as `live_flat` and document persistent depth settings.
- Make strength controls add a missing setting, including in an empty settings file, while preserving unrelated settings and existing line endings.
- Discover Steam libraries and JDK installations, accept local path overrides, and preserve user settings.
- Add source builds, local GPU/bytecode validation, script tests, diagnostic tools, and documentation.

## Prototype milestones

- **2026-09-26:** Real game color/depth capture, static stereo proof, then live GPU submission. Steam Frame user confirmed comfortable depth, orientation, gameplay, and menus.
- **2026-09-27:** Zoom coverage and intermittent UI/world flicker reproduced, fixed, regression-tested, and confirmed in the headset.
- **2026-09-29:** Prepare the source repository for sharing; retain the existing MIT license and Valve notices.
