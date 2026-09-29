# Third-party notices

ZomboidVR's original code is covered by the repository's [MIT license](LICENSE).

## Valve OpenVR headers

`vendor/openvr/openvr.h` and `vendor/openvr/openvr_capi.h` are from the [official Valve OpenVR repository](https://github.com/ValveSoftware/openvr/tree/master/headers). Their original copyright notices remain intact. They are redistributed under [Valve's accompanying license](vendor/openvr/LICENSE).

The header copies were obtained during the September 26, 2026 prototype work. The exact upstream commit was not recorded; SHA-256 below identifies the local bytes, rather than implying an unverified SDK release:

```text
1E6ED57199896CC1F7C5484E50FA18955E97BE15BE690BEB28D998C877EAD7FD  openvr.h
3D32D8EB5BCBE2E250610123AEE8142C68F0E7D4DA823F87513B6A2A333078D7  openvr_capi.h
```

The runtime currently requests the header's `IVROverlay_028` and `IVRSystem_026` interfaces.

## Locally installed dependencies

Project Zomboid and its bundled Java libraries, SteamVR's native runtime, and the JDK are supplied by the user and are not redistributed here. The source builds against the installed game JAR and calls its LWJGL/JNA libraries. Game titles and trademarks belong to their respective owners.
