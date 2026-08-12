# LinuxDroid

LinuxDroid is a native Android application for running a **single active Linux userland** through PRoot. It installs verified RootFS archives discovered directly from the project's own GitHub Release, keeps multiple installed distributions in app-private storage, provides a Termux-style terminal, opens a local internal VNC client, and exposes guest audio through a loopback-only PulseAudio host.

> PRoot is a userspace implementation of `chroot` and bind mounts. It does not provide a Linux kernel, kernel privileges, containers, Docker, systemd, or hardware-driver access. [1]

## Supported ABI targets

| Artifact | ABI | Requirement |
| --- | --- | --- |
| LinuxDroid APK | `arm64-v8a` | Native 64-bit ARM Android device |
| LinuxDroid APK | `armeabi-v7a` | Native 32-bit ARM Android device |
| RootFS archive | `arm64-v8a` | AArch64 Linux userland |
| RootFS archive | `armeabi-v7a` | ARMv7 Linux userland, when published by its upstream provider |

The build deliberately excludes x86, x86_64, and the legacy `armeabi` target. One application build can include both runtime assets, while Gradle also emits ABI-specific APKs.

## Main features

LinuxDroid keeps every distribution as a separately installed RootFS and only allows **one PRoot session at a time**. The terminal uses the Termux terminal-view API with supplementary Ctrl, Alt, Shift, Tab, Escape, and arrow controls. The local VNC screen supports an RFB 3.3/3.7/3.8 loopback connection, classic password authentication, keyboard/mouse input, raw framebuffer updates, copy-rect, and desktop resize. Its connection workflow and VNC authentication behavior are compatible with bVNC conventions; the compatibility layer is attributed in `THIRD_PARTY_NOTICES.md`.

The desktop setup dialog writes a guest setup script for XFCE, LXDE, MATE, or Fluxbox. It can also select Firefox, Chromium, or no browser and an optional media/text bundle. The script configures a localhost-only VNC server on display `:1`, which maps to port `5901`.

Audio is designed around an Android-native PulseAudio runtime on `127.0.0.1:4713`. The runtime is assembled from the Termux PulseAudio package graph and invokes its Android sound sink. LinuxDroid sets `PULSE_SERVER=tcp:127.0.0.1:4713` in supported PRoot sessions. This behavior requires real-device testing because Android audio backends vary by vendor and Android release.

## RootFS releases

LinuxDroid queries the public GitHub Releases API for the project's fixed [`rootfs-pack-1` release](https://github.com/s-n-t09/LinuxDroid/releases/tag/rootfs-pack-1). Users select a discovered distribution from the app; no manual RootFS URL configuration is required.

The client accepts release assets named `linuxdroid-rootfs__<distro-id>__<version>__<android-abi>.tar.xz` together with a same-name `.sha256` sidecar. It verifies the SHA-256 checksum before extracting an archive. The current pack provides every checksum-bearing ARM RootFS currently available from the official Termux PRoot Distro release feed: all available `arm64-v8a` images and the subset currently published upstream for `armeabi-v7a`.

See [`rootfs/README.md`](rootfs/README.md) for the release contract and reproducible preparation procedure. The installer resumes downloads when the server supports HTTP Range requests, verifies the exact archive SHA-256, extracts into a staging directory, and only then records the installation.

## Build

Use JDK 17, Android SDK Platform 35, and Android Build Tools 35.0.0. The GitHub Actions workflow is the recommended reproducible route:

```bash
./scripts/prepare-runtime.sh
./gradlew assembleRelease bundleRelease
```

The runtime preparation script fetches known PRoot packages over HTTPS and checks their pinned SHA-256 values. It then resolves PulseAudio and its declared dependency graph from the public Termux package index for each supported ARM ABI. Review and update the pins before every release.

The workflow uploads the ABI split APKs, universal APK, unsigned release APKs, and AAB as CI artifacts. To publish a signed release, configure standard signing secrets in the repository and add a signing step; do not commit a keystore or credentials.

## First-run privacy and process behavior

LinuxDroid operates in isolated mode by default. Shared storage is bound inside the guest as `/sdcard` only after the user explicitly opts into Android's **All files access** setting. This special permission is subject to Android and distribution-store policies; apps should request it only when broad file access is a direct core feature. [2] [3]

The running Linux session is hosted by a user-started foreground service with an ongoing notification. Android places strict limits on background services and limits foreground-service launches from background states. [4] [5] Read the [User Guide](docs/USER_GUIDE.md) before relying on long-running desktop sessions.

## License and notices

LinuxDroid is distributed under **GPL-3.0-or-later** to remain compatible with its terminal and VNC-related components. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## References

[1]: https://proot-me.github.io/ "PRoot documentation"
[2]: https://developer.android.com/training/data-storage/manage-all-files "Android all-files access documentation"
[3]: https://support.google.com/googleplay/android-developer/answer/10467955 "Google Play policy on MANAGE_EXTERNAL_STORAGE"
[4]: https://developer.android.com/about/versions/oreo/background "Android background execution limits"
[5]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start "Android foreground-service background-start restrictions"
