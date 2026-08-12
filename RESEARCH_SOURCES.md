# Technical Research Record

## Architecture decisions

LinuxDroid will be a native Android/Kotlin application rather than a wrapper around another terminal app. The project is designed around a single actively running PRoot session, app-private rootfs storage, resumable rootfs downloads from configurable GitHub release URLs, and an explicit foreground service with a permanent notification while a distribution is running.

| Component | Decision | Rationale |
| --- | --- | --- |
| PRoot runtime | Package relocatable PRoot binaries as ABI-specific native assets; extract and mark executable on first use | The Android PRoot build scripts produce statically linked, relocatable binaries and are MIT-licensed. |
| Linux rootfs | Use a versioned JSON manifest with SHA-256 checksums and direct GitHub Release asset URLs, split by `arm64-v8a` and `armeabi-v7a` | This permits controlled first-party rootfs releases and verifies archive integrity before extraction. |
| VNC | Internal viewer module with bVNC-compatible connection profile semantics; retain a clear GPL-3.0 boundary and attribution when code is vendored | The upstream bVNC source is GPL-3.0. It must not be copied into a closed-source artifact. |
| Audio | Start a host PulseAudio daemon bound to loopback and set the guest `PULSE_SERVER` to the host socket/TCP endpoint | Loopback-only transport prevents exposing the audio server to the LAN. |
| Android lifecycle | Run the single PRoot session under a user-started foreground service with a visible ongoing notification | Modern Android imposes background service and foreground-service start restrictions. |
| Host files | Request `MANAGE_EXTERNAL_STORAGE` only after an explicit user choice and always offer the scoped document-picker alternative | Broad shared-storage access is a special permission and is subject to distribution-store policy. |

## Key implementation constraints

PRoot does not provide kernel virtualization or root privileges. It translates guest system calls in user space, so systemd, Docker, kernel modules, and hardware-dependent services are not supported. Rootfs images must use the same CPU family as the device: aarch64 rootfs for `arm64-v8a` and ARMv7 rootfs for `armeabi-v7a`.

To keep long-running desktop sessions reliable, LinuxDroid must start its session only from a visible activity, promptly promote the service to the foreground, maintain an ongoing notification, and explicitly guide users to disable battery optimization for the app. The app must never claim that Signal 9 can be universally prevented: OEM firmware and memory pressure can still terminate a process.

## References

[1]: https://github.com/green-green-avk/build-proot-android "build-proot-android — relocatable Android PRoot build scripts"
[2]: https://proot-me.github.io/ "PRoot documentation"
[3]: https://github.com/iiordanov/remote-desktop-clients "bVNC / remote-desktop-clients source repository"
[4]: https://github.com/termux/proot-distro "Termux PRoot-Distro source repository"
[5]: https://developer.android.com/about/versions/oreo/background "Android background execution limits"
[6]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start "Android foreground-service background-start restrictions"
[7]: https://developer.android.com/training/data-storage/manage-all-files "Android all-files access documentation"
[8]: https://support.google.com/googleplay/android-developer/answer/10467955 "Google Play policy on MANAGE_EXTERNAL_STORAGE"

## Verified PRoot package paths

The upstream Android package directory currently exposes `proot-android-aarch64.tar.gz` and `proot-android-armv7a.tar.gz`. Each archive contains the relocatable runtime tree with the executable at `root/bin/proot`. The build workflow pins the source to a reviewed commit and verifies a recorded SHA-256 value before copying `root/bin/proot` into the matching Android asset directory. This avoids silently accepting an altered upstream artifact.

Source listing: https://api.github.com/repos/green-green-avk/build-proot-android/contents/packages

## Terminal, VNC, and audio integration notes

The internal terminal uses the Termux `terminal-view` and `terminal-emulator` libraries through their documented JitPack dependency path. LinuxDroid implements the `TerminalSession` / `TerminalSessionClient` contract so the PRoot process is spawned against a real pseudoterminal rather than raw process pipes. The project must remain GPL-3.0-or-later compatible because it incorporates GPL-licensed terminal components.

The internal VNC transport is an independently implemented, minimal RFB 3.3/3.7/3.8 loopback viewer that supports classic VNC authentication, raw framebuffer updates, copy-rect, desktop resize, mouse, and keyboard. Its interaction model and connection profile are compatible with the bVNC workflow, while bVNC itself is kept as an optional upstream reference because importing the complete upstream module requires a large dependency graph. bVNC’s source and derived code are GPL v3; any future direct vendoring must retain its copyright notices and complete corresponding source.

The native Android PulseAudio host bundle is prepared from the Termux PulseAudio build recipe, whose source includes Android-specific `module-sles-sink.c` and `module-aaudio-sink.c`. LinuxDroid starts it loopback-only (`127.0.0.1:4713`) and exposes `PULSE_SERVER` to PRoot guests. The bundle needs device testing because OpenSL ES / AAudio support varies by Android build and vendor.

Additional references:

[9]: https://github.com/termux/termux-app/wiki/Termux-Libraries "Termux libraries integration guide"
[10]: https://raw.githubusercontent.com/termux/termux-app/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java "Termux TerminalSession API"
[11]: https://raw.githubusercontent.com/termux/termux-app/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalSessionClient.java "Termux TerminalSessionClient API"
[12]: https://raw.githubusercontent.com/termux/termux-app/master/terminal-view/src/main/java/com/termux/view/TerminalViewClient.java "Termux TerminalViewClient API"
[13]: https://github.com/iiordanov/remote-desktop-clients/blob/master/COPYRIGHT-bVNC "bVNC attribution and GPL-v3 terms"
[14]: https://github.com/termux/termux-packages/tree/master/packages/pulseaudio "Termux PulseAudio Android build recipe"
