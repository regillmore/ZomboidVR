# Architecture

## Frame path

1. The launcher compiles a small Java agent and a separate runtime JAR using the installed game's API classes.
2. `LiveBootstrap` instruments `SpriteRenderer$RingBuffer$StateRun.render()` and LWJGL's `Display.swapBuffers()` in memory. `LiveBridge` is the stable target for callbacks.
3. `UiCaptureHook` relocates the bootstrap's initial batch callback to immediately before the real sprite draw. Operation-only and empty batches return before this point. This is essential: a stale UI texture reference in an operation-only batch must not trigger a capture of an already-composited frame.
4. When the UI framebuffer is about to be drawn onto the default framebuffer, `LiveSession` copies the postprocessed world image and matching world depth into owned GPU textures.
5. Before the display swap it copies the final image, renders left/right views, and submits the side-by-side OpenGL texture through an OpenVR overlay.

Normal frames have no CPU image readback. Explicit diagnostics do read back pixels and may briefly stall rendering. The overlay only displays images; normal game input continues independently.

## Pixel extent versus camera projection

In the tested Build 42 renderer, the physical world viewport stays at the screen's pixel dimensions. `PlayerCamera.offscreenWidth/Height` describe the logical projection extent, which grows when zooming out. They are not the occupied pixel rectangle inside the padded world framebuffer.

Depth copies use `camera.width/height`, matching the game's world-texture presentation. The projection height separately scales the stereo depth span. `worldRegion` and `projectionSize` in status distinguish these values. An undersized source is rejected rather than stretched to fill the destination.

## Stereo and UI composition

The shader uses raw orthographic depth and a nine-sample central median as its reference plane. It gathers inverse-warp candidates, resolves overlaps toward nearer valid samples, and limits eye separation with the strength setting. It cannot reconstruct occluded surfaces.

For premultiplied UI, `final = world * (1 - alpha) + UI`. The output changes only the world contribution:

```text
stereo = final + (warpedWorld - world) * (1 - uiAlpha)
```

This keeps opaque menu pixels at the same position in both eyes and preserves translucent UI over the stereo world. The world-only capture must be clean and aligned with the final image for this relation to hold. Native cursor visibility is represented by an added fixed-plane pointer in depth mode.

## Loading and cleanup

The system-loader bootstrap remains until game exit. A child class loader owns each renderer build, so stop/rebuild/start can replace renderer code without restarting the game. For compatibility with the original running prototype, `UiCaptureHook` accesses the bootstrap's own instrumentation handle by reflection; it does not inspect unrelated applications. Stop removes this extra transformer before the bootstrap retransforms the two original methods.

`GlState` saves/restores compatibility attributes and the modern bindings touched by the added rendering. All OpenGL work runs on the game's render thread. SteamVR uses the installed `openvr_api.dll` selected by the launcher; its path is written to an ignored local file. Vendor headers determine exact OpenVR function-table versions/offsets. Unsupported interfaces fail rather than guessing.

## Source map

| Location | Role |
| --- | --- |
| `src/bootstrap/pzvr/` | Attach entry point, stable bridge, base transformations and shutdown. |
| `src/live/pzvr/live/` | Frame capture, UI draw hook, GL state, stereo submission, controls and diagnostics. |
| `live-shaders/` | GPU stereo shader and fullscreen triangle. |
| `src/validation/` | Offline bytecode and isolated OpenGL checks. |
| `src/pzvr/` | Attach utility, one-shot depth probe, saved-image stereo proof utility. |
| `src/diagnostics/` | Short layer-capture probe used to diagnose flicker. |
| `scripts/Common.ps1` | Installation discovery, local settings, process matching and helper ownership. |
| `tests/Test-Scripts.ps1` | Host-independent script/fixture checks; usable in CI. |

The source targets the inspected Build 42.20.4 APIs. Renderer changes in a later game build may require new hook points or depth handling; compiler success alone does not establish game compatibility.
