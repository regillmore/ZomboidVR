# Troubleshooting

## Launcher closes or paths are missing

Use **Start Live.cmd**, which pauses on failure, or run `Start-Live.ps1` in PowerShell to retain the output. Copy `config/local.paths.example.psd1` to `local.paths.psd1` in the root and fill in any missing paths. `GameDir` must contain `projectzomboid.jar`; `SteamVrDir` must contain `bin\win64\openvr_api.dll`; `JdkDir` must contain a complete x64 JDK 25 or newer.

Command-line paths take priority over the local configuration, which takes priority over discovery. Do not edit Java source to change the SteamVR location. If PowerShell blocks a directly invoked script, use the provided `.cmd` launcher; it changes policy only for that child process.

## Cannot attach or create the helper

Start the game normally and use the same Windows account for the launcher. Only one game process should be running unless you supply `-GameProcessId`. The launcher verifies that the selected process matches `GameDir`. A game running with a different elevation level can prevent attachment. The game directory must allow the helper copy to be created if it is absent. A different existing `jli.dll` is deliberately not replaced.

## Overlay does not appear

Check `live-control/status.properties`:

| State | Action |
| --- | --- |
| `waiting_for_headset_tracking` | Put on/wake the headset and confirm SteamVR tracking. The screen appears once a valid pose is available. |
| `live_stereo` | Dismiss the SteamVR dashboard and use **Recenter Live.cmd**. |
| `live_flat` | Flat mode is selected. Use **Live Depth.cmd** to restore depth. |
| `flat_fallback_no_world_or_ui` | Load a single-player scene and enable offscreen UI rendering in the game. |
| `error` | Read the `error` value; stop the overlay before retrying. |
| `stopped` | Start again when ready. |

The game menu without a loaded world can be flat. Split-screen is not supported. A status file can be left over after a crash or game exit: check its update time and whether the game is still running.

## The overlay appears but has no depth

Check `strength` in `live-control/settings.properties`. A value of `0` selects flat mode, and restarting preserves that selection. Double-click **Live Depth.cmd** in the same folder that launched the overlay to restore 0.7; no restart is needed. Each checkout has its own settings and controls. If depth is enabled but the status says `flat_fallback_no_world_or_ui`, use the guidance above.

Earlier builds also skipped the pointer in flat mode. The current shader draws it in stereo, selected flat mode, and the no-world fallback, following the game's cursor visibility setting.

## Already installed, switching folders, or changed code

Stop the overlay using the checkout that launched it, wait for `live-control/hooks.txt` to say `removed`, then start from the desired checkout. Restart Project Zomboid for changes to `src/bootstrap/` or an already-loaded diagnostic agent. Runtime renderer/shader edits can use stop/rebuild/start. Closing the game clears all loaded agents and hooks.

Do not run the historical static-preview overlay at the same time as the live overlay. The old preview app and saved captures are not required by this repository.

## Depth is imperfect or uncomfortable

Use **Live Flat.cmd** for comparison, then lower `strength` in `live-control/settings.properties`. **Live Depth.cmd** restores 0.7. Furniture, railings, translucency, and thin edges remain known problem areas because the available depth does not describe every visible surface. This differs from the fixed zoom-coverage and UI capture-timing bugs.

If rectangular flat bands or pause-title flicker return, capture the zoom/position steps and `capturePoint`, `worldRegion`, and `projectionSize` from status. `capturePoint` should be `before_ui_draw`; the pixel region should remain screen-sized as the projection changes with zoom.

## Cleanup

Exit Project Zomboid, then run **Cleanup After Closing Game.cmd**. It removes only a helper copy created and recorded by this checkout whose path and hash still match. The original `jre64\bin\jli.dll` is never the target. A pre-existing helper, including one owned by an earlier prototype folder, is left alone; use that earlier folder's cleanup if appropriate.
