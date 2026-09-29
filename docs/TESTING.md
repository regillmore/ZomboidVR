# Test record

These are observations from the original local prototype on September 26–27, 2026, plus repository packaging checks on September 29. They are not a broad compatibility certification. Large captures, private paths, process IDs, game disassembly, and game assets are not part of the repository.

## Tested system and headset results

| Item | Observed result |
| --- | --- |
| Game | Project Zomboid 42.20.4 on Windows x64 |
| Graphics | NVIDIA RTX 2080 Ti, OpenGL 4.6 |
| Headset connection | Steam Frame through Steam Link and SteamVR |
| Input/output image | 1920 × 1080 game; 1600 × 900 per eye |
| Initial static proof | Captured world depth produced comfortable stereo, confirmed in the headset. |
| Live gameplay | Upright view, comfortable motion, zoom changes, and usable inventory/menus confirmed. |
| Zoom fix | Full scene coverage at every tested zoom confirmed in the headset. |
| Flicker fix | Pause title and world objects steady at the previously problematic position/zoom. |
| Lifecycle | Stop removed hooks; game stayed responsive; renderer restarted without game exit. |

Observed overlay delivery was approximately 60 updates/second. The initial paused-scene samples showed roughly 0.24–0.29 ms mean hook CPU time and 0.25–0.30 ms mean stereo-draw GPU time. A later sample window at zoom 2.0 showed 59.98–60.02 updates/second, 0.25–0.28 ms hook CPU time, and 0.28–0.65 ms stereo GPU time.

Hook timing covers work on submitted frames; the GPU timer covers the stereo draw. Neither measures complete game rendering cost, all graphics copies, Steam Link latency, or headset refresh. Demanding gameplay and other hardware need separate measurement.

## Zoom regression

The first renderer copied depth using zoom-expanded logical dimensions. At 150% zoom, that selected 2048 × 1620 pixels from a padded framebuffer even though only 1920 × 1080 were occupied. The capture contained **1,244,160 clear pixels** in rectangular top/right bands. Zooming in could instead crop and misalign the depth image.

After switching to physical viewport dimensions, the actual stereo depth texture was 1920 × 1080 with **zero clear/invalid pixels** in the reproduced scene, including the top/right edge bands. Legitimate empty depth remains possible in other scenes.

The GPU regression test fills a padded framebuffer with a known depth pattern and verifies every output pixel at 50%, 75%, 100%, 125%, 150%, 200%, and 250% zoom, using both 1920 × 1080 and 1280 × 720 viewports. It also verifies rejection of an undersized source.

## Flicker regression

The old method-entry hook also fired on state-only batches that reused an earlier UI texture reference. Some world captures therefore contained the previous frame's menu. Moving capture to immediately before the real sprite draw corrected this.

In 12 before samples, 9 showed contaminated world capture. UI recomposition error was about 15.23 average channel values on a 0–255 scale in those samples. All 12 after samples were about 0.236, consistent with rounding and later overlays. The depth texture and median reference remained constant throughout both sequences, ruling out changing convergence as the cause of this reproduced paused-scene bug.

Offline validation verifies the transformed bytecode and exactly one capture invocation immediately before the sprite draw. The existing shader, state-restoration, and zoom tests also pass.

## Repository checks — September 29

- Built the live agent/runtime and both diagnostic agents from the cloned repository.
- Passed `Validate-Live.ps1` against the locally installed game and GPU.
- Passed script parsing and fixtures under Windows PowerShell 5.1 and PowerShell 7: second Steam library with spaces, configured paths, JDK validation, settings preservation, and helper copy ownership/path/hash checks.
- Reproduced the review finding that strength controls silently did nothing when the saved property was absent. After the fix, the real control script passes flat/depth toggles in isolated fixtures with a missing property, an empty file, no final newline, and an existing property; unrelated settings and existing line endings are preserved. Both PowerShell versions pass.
- Original prototype files remain in the parent development folder. The game was closed during initial packaging; the later live check below covers the packaged launch path.

## Packaged launch and flat-mode cursor — September 29

The packaged checkout attached successfully and captured world depth, but its saved `strength=0` produced a flat view. Restoring 0.7 through the checkout's own controls restored both stereoscopy and the pointer, confirmed in Steam Frame by the user. Saved settings intentionally survive restart; the repository defaults remain 0.7.

This exposed a shared shader bug: selected flat mode and the no-world fallback returned before drawing the pointer. Cursor composition now follows both flat and stereo scene rendering. An isolated GPU test renders visible/hidden cursor cases for both eyes in all three modes, verifies the pointer's screen position, and checks that the background is preserved. Status now reports `live_flat` when depth is explicitly disabled, instead of incorrectly reporting `live_stereo` merely because a depth buffer is available.

The corrected runtime was stopped/reloaded into the running game successfully. Live status confirmed transitions from `live_flat` at 0 to `live_stereo` at 0.7, with roughly 60 updates/second and no reported errors. Depth was left enabled. Flat-mode pointer coverage is GPU-test verified; the headset confirmation above was with depth enabled.

## Reproducing checks

```powershell
.\tests\Test-Scripts.ps1     # No game, JDK or headset needed
.\Start-Live.ps1 -BuildOnly # Installed game + JDK; no attach
.\Validate-Live.ps1         # Installed game + JDK + OpenGL; no attach
```

For live diagnostics, start the overlay in a single-player scene:

```powershell
.\Control-Live.ps1 -Snapshot
.\Capture-Layers.ps1 -Name a-new-diagnostic-name
.\Capture-Depth.ps1
```

Snapshot writes stereo/depth PNGs and depth-coverage statistics under `live-control/`. Layer capture writes 12 samples plus the first/last layer images under `diagnostics/`. The one-shot probe writes a new `captures/` directory with color, normalized depth, and little-endian, top-down raw float depth. Snapshot output belongs to the current renderer; diagnostic agent classes persist until game exit, so restart before testing edits to those classes.

Generated files stay local and are ignored by Git. When reporting a bug, include the game version, GPU/headset, trigger steps, and relevant status values. Inspect any optional logs/captures before sharing them.
