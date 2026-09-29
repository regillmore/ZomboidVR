# Contributing

Start with the tested Build 42.20.4 setup and read [architecture](docs/ARCHITECTURE.md). Keep changes small enough to test in a loaded single-player scene. Include the trigger and resulting behavior in a pull request, plus which checks actually ran.

Run `tests/Test-Scripts.ps1` for launcher changes. With your own game installation, run `Start-Live.ps1 -BuildOnly` and `Validate-Live.ps1` for Java/shader changes. Compile diagnostic tools with `Capture-Depth.ps1 -BuildOnly` and `Capture-Layers.ps1 -BuildOnly` when editing them. Use Windows PowerShell 5.1 as well as PowerShell 7 for script changes.

For rendering changes, test the headset, zoom extremes, menus, movement, and stop/restart. Do not treat offline validation as proof of headset comfort or full compatibility with a new game build. Record the game version and relevant hardware.

Keep game JARs, DLLs, assets, disassembled/decompiled game files, captures, local paths, and generated outputs out of commits. Compile against the contributor's installed game. Preserve the Valve license when changing the vendored headers. CI intentionally runs script/fixture checks only; proprietary game dependencies and hardware tests remain local.

Useful next contributions include missing-depth handling for translucent layers, edge reconstruction, broader scene/hardware testing, and calibration controls. Avoid globally forcing the game's depth writes: that can change transparency and occlusion behavior.
