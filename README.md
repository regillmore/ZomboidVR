# ZomboidVR

**Experimental live stereo depth for Project Zomboid on a SteamVR virtual screen.**

ZomboidVR turns the game's world image and depth buffer into separate left/right views, while keeping menus at a shared screen depth. Frames stay on the GPU and go directly to SteamVR. The game keeps its normal keyboard, mouse, and controller controls.

The prototype has been tested with **Project Zomboid 42.20.4 on Windows, an RTX 2080 Ti, and Steam Frame through Steam Link**. Headset testing confirmed comfortable depth, full coverage through zoom changes, stable pause menus, and working movement/inventory controls. See the [test record](docs/TESTING.md) for the evidence and limits.

This is a stereoscopic isometric screen experience. It does not add first-person rendering, freely orbiting world views, or VR motion-controller interactions. It is a development prototype, not a Steam Workshop release.

## Requirements

- Windows x64 with Windows PowerShell 5.1 or PowerShell 7.
- Your own installed copy of Project Zomboid. **42.20.4 is the tested build**; other versions may need code changes.
- SteamVR installed and running, with a connected headset. Only Steam Frame through Steam Link has been tested here.
- A **64-bit JDK 25 or newer** containing `java`, `javac`, and `jar`; JDK 25 is tested. The game's bundled JRE cannot build the source.
- A single-player scene and the game's offscreen UI rendering enabled.

## Quick start

1. Clone or download this repository into a writable folder.
2. Start SteamVR, connect the headset, and start Project Zomboid normally. Load a single-player scene; pause for the first test.
3. Double-click **Start Live.cmd**. It finds the game, SteamVR, and JDK, builds the local source, and attaches to the running game.
4. Put on the headset. Dismiss the SteamVR dashboard if it covers the screen. Use **Recenter Live.cmd** to place the screen in front of you.

If discovery fails, copy [`config/local.paths.example.psd1`](config/local.paths.example.psd1) to **`local.paths.psd1` in the repository root** and fill in the paths you need. The local file is ignored by Git. You can also pass `-GameDir`, `-SteamVrDir`, and `-JdkDir` to `Start-Live.ps1`. Discovery checks Steam's library list, `JAVA_HOME`, `javac` on PATH, and common JDK install locations.

Use the same Windows account for the game and launcher. The launcher may need write access to the game directory to add its helper copy. If a previous prototype is running from another folder, stop it with that folder's **Stop Live.cmd** before starting this checkout.

| Control | Result |
| --- | --- |
| **Start Live.cmd** | Build and start the overlay; use once per running session. |
| **Stop Live.cmd** | Remove the overlay and restore its two modified in-memory methods. The game stays open. |
| **Recenter Live.cmd** | Place the screen ahead of the headset, level with the room. |
| **Live Flat.cmd** | Set depth strength to zero for comparison. |
| **Live Depth.cmd** | Restore the tested depth strength of 0.7. |
| **Cleanup After Closing Game.cmd** | Remove this checkout's recorded helper copy after the game exits. |

The launch scripts apply PowerShell execution-policy bypass only to their child process. They do not change your system policy or install a service.

## Settings

The first launch copies [`config/defaults.properties`](config/defaults.properties) to `live-control/settings.properties`. Existing settings are preserved, including flat mode (`strength=0`), across stop/start. Use **Live Depth.cmd** to restore depth. The renderer rereads settings twice per second. The mouse pointer remains visible in both flat and stereo modes when the game shows its cursor.

| Setting | Default | Meaning |
| --- | --- | --- |
| `strength` | `0.7` | Depth amount; 0 is flat, supported range 0–1.5. |
| `convergence` | `0` | Offset from the automatic central depth reference; range -0.5–0.5. |
| `eyeWidth` | `1600` | Maximum pixels per eye horizontally; preserves the game's aspect ratio. |
| `fpsLimit` | `60` | Maximum overlay updates per second, also limited by the game. |
| `screenWidthMeters` | `2.6` | Screen width; recenter after changing. |
| `distanceMeters` | `2.5` | Screen distance; recenter after changing. |

`live-control/status.properties` reports the state, source/output size, capture point, zoom, submitted frames, and timing. [Troubleshooting](docs/TROUBLESHOOTING.md) explains common states.

## Build and validate

From PowerShell in the repository folder:

```powershell
.\Start-Live.ps1 -BuildOnly
.\Validate-Live.ps1
.\tests\Test-Scripts.ps1
```

Build-only does not attach to or modify the game and does not require SteamVR. Validation needs the installed game JAR, JDK, and a working OpenGL context, but does not launch or attach to the game. It verifies bytecode, shader compilation, selected OpenGL state restoration, UI capture placement, and depth coverage/alignment at seven zoom levels across two resolutions.

GitHub Actions runs the script checks on Windows PowerShell 5.1 and PowerShell 7. Full game/GPU/headset checks remain local because the repository does not include the game or VR runtime. See [contributing](CONTRIBUTING.md).

## Known limits

- Some furniture, railings, translucent objects, and animated attachments lack useful final depth. These can warp incorrectly.
- Depth-based reprojection cannot reveal hidden surfaces; thin edges and newly exposed gaps can show artifacts.
- Depth strength is image-relative, not a calibrated physical stereo camera. Automatic convergence can change with scene content.
- Split-screen stereo is not implemented. Missing world/UI buffers produce a flat fallback.
- Other GPUs/headsets, interiors, multiple floors, cutaways, weather, and long sessions need broader testing.

The zoom-coverage and intermittent UI flicker bugs found during initial testing are fixed. [Test notes](docs/TESTING.md) explain both causes and checks.

## What changes on your PC

The Java agent temporarily instruments two loaded methods and creates its own GPU resources. **Original game files, shaders, saves, and SteamVR settings are not overwritten.** Stop removes the transformers and restores the methods. Java classes remain loaded until the game exits; restart the game when changing bootstrap/bridge or diagnostic-agent classes. Runtime renderer changes can be loaded by stopping and starting again.

The launcher may copy the game's own `jre64\bin\jli.dll` to `jli.dll` at the game-directory root so Java instrumentation can load. It records ownership only when it creates that copy. Cleanup verifies the exact path and recorded SHA-256, then removes only that copy after game exit. An already-existing matching DLL is used but not claimed or removed; a different DLL is never overwritten.

Build outputs, local paths, settings, logs, captures, and helper receipts stay in ignored directories. No game JARs, game assets, DLLs, or Java/SteamVR installations are distributed here.

## Project

- [Architecture and source map](docs/ARCHITECTURE.md)
- [Test record and diagnostics](docs/TESTING.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Changes](CHANGELOG.md)

Next work: improve missing depth and edge handling, test more scenes/hardware, and make calibration easier.

Project code is under the existing [MIT license](LICENSE). Bundled Valve headers retain their [own license](vendor/openvr/LICENSE); see [third-party notices](THIRD_PARTY_NOTICES.md). This is an independent project, unaffiliated with The Indie Stone or Valve.
