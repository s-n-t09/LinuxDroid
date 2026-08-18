# LinuxDroid

> **Run a real Linux userland on Android — locally, privately, and without root.**

**LinuxDroid 1.0** is a native Android application that runs Linux distributions through [PRoot][proot]. It downloads verified RootFS archives from this repository’s own GitHub Releases, keeps every installed distribution isolated in app-private storage, and provides a Termux-style terminal, an internal VNC viewer, desktop setup, shared-storage binding, and PulseAudio forwarding.

| Official release | Download |
| --- | --- |
| LinuxDroid application | [v1.0 APKs](https://github.com/s-n-t09/LinuxDroid/releases/tag/v1.0) |
| Verified Linux images | [RootFS 1](https://github.com/s-n-t09/LinuxDroid/releases/tag/rootfs-1) |
| Build automation | [GitHub Actions](https://github.com/s-n-t09/LinuxDroid/actions) |

> PRoot is a userspace implementation of a chroot-like environment. It does **not** grant root access to Android, provide a separate kernel, run Docker, replace Android, or make `systemd` available.[^proot]

## Highlights

LinuxDroid supports five carefully prepared distributions: **Ubuntu, Debian Trixie, Alpine Linux, Arch Linux, and Fedora Linux**. More than one distribution can be installed, while the application enforces one active PRoot environment at a time to protect storage and resources.

| Area | Included capabilities |
| --- | --- |
| Terminal | Multi-tab terminal, copy/paste, pinch-to-zoom text, system keyboard, extra keys, function keys, arrows, modifiers, and an explicit Exit control. |
| Desktop | Guided setup for XFCE, LXDE, MATE, or Fluxbox; Firefox, Chromium, or no browser; and optional media/text tools. TightVNC is preferred, with Xvfb + x11vnc as a fallback. |
| VNC | Local RFB viewer, relative Touchpad or Direct touch, configurable port/password, full-screen immersive display, classic bottom key strip, configurable orientation, and safe mouse/key transport. |
| Virtual gamepad | Transparent floating controls; drag a button to reposition it, add or delete buttons, hide individual buttons, prevent overlap, and optionally invert the D-pad directions. |
| Audio | Private Android-hosted PulseAudio server on `127.0.0.1:4713`, guest-side PulseAudio/ALSA configuration, and the `linuxdroid-audio` setup/test helper. |
| Storage | Optional Android shared-storage binding at `/storage/emulated/0` and `/sdcard`, only after the user grants All files access. |
| Startup services | Per-distribution commands that run once after that specific distribution starts. They are a practical replacement for simple `systemd` service use cases. |

## Supported architectures

LinuxDroid deliberately supports only ARM Android devices.

| Artifact | Supported ABI |
| --- | --- |
| Application APK | `arm64-v8a`, `armeabi-v7a` |
| RootFS images | `arm64-v8a`, `armeabi-v7a` when a verified upstream image exists |
| Excluded | `x86`, `x86_64`, legacy `armeabi` |

Fedora availability follows the verified upstream ARM archives. The application never publishes or installs x86 images.

## Quick start

Install the APK that matches your phone’s architecture, then open **Distros** and select a distribution. LinuxDroid downloads its RootFS directly from the [RootFS 1 release](https://github.com/s-n-t09/LinuxDroid/releases/tag/rootfs-1), verifies its SHA-256 checksum, extracts it into a staging directory, and activates it only after installation succeeds.

After installation, press **Start**. LinuxDroid opens the terminal automatically when the session is ready. For a desktop, choose **Setup** on that distribution card, complete the desktop wizard, start the generated desktop command in the terminal, then open **VNC**.

Use **Services** on a distribution card to create startup commands for that distribution only. Each enabled command runs in the background once after the next session start, and its output is written to `/tmp/linuxdroid-startup-*.log` inside that guest.

## VNC controls

The VNC viewer opens in immersive full-screen mode so Android system bars do not crowd the desktop. Swipe from a screen edge to reveal Android system bars temporarily when needed.

Use **Touchpad** when you want relative cursor motion: touching the screen does not teleport the pointer, and dragging moves it from its current remote position. Use **Direct touch** when you want the cursor to follow the touch location. The bottom strip contains keyboard modifiers, arrows, function keys, mouse buttons, scaling, keyboard, Gamepad, and Disconnect controls.

The optional virtual gamepad is enabled in **Settings → Configure VNC**. While VNC is open, press **Edit pad** to add, hide, delete, or reposition buttons. Dragging a gamepad button saves its position and will not allow it to overlap another button.

## Debian Trixie networking

LinuxDroid repairs Debian’s inherited `systemd-resolved` stub at runtime. When Debian Trixie starts, the app writes `/etc/resolv.conf` inside the installed RootFS using the active Android network’s DNS servers. Restart Debian after updating the application, then run:

```sh
cat /etc/resolv.conf
getent hosts deb.debian.org
apt update
```

If the resolver file contains valid nameservers but hostname lookup still fails, the active Wi-Fi or mobile network is blocking or misconfiguring DNS rather than the guest image.

## Audio

LinuxDroid starts a private PulseAudio server on `127.0.0.1:4713` when a Linux session starts. In a minimal RootFS, run this once:

```sh
linuxdroid-audio setup
linuxdroid-audio test
```

The first command installs the guest PulseAudio client and ALSA bridge with that distribution’s package manager; the second verifies the connection and plays a short test tone. Audio hardware and battery behavior can vary across Android devices and vendor builds.

## Shared storage and privacy

The guest is isolated by default. Shared Android storage is unavailable until you explicitly enable **Shared storage access** and grant Android’s **All files access** permission. When enabled, LinuxDroid binds shared storage as `/storage/emulated/0` and `/sdcard` inside the running guest.[^all-files]

Linux sessions run in a user-visible foreground service. Keep the LinuxDroid notification visible and exclude the application from battery optimization if Android kills a long session with Signal 9.[^fgs]

## RootFS release contract

RootFS 1 is the only RootFS release queried by LinuxDroid 1.0. Images use the following naming contract:

```text
linuxdroid-rootfs__<distribution-id>__<version>__<android-abi>.tar.xz
linuxdroid-rootfs__<distribution-id>__<version>__<android-abi>.tar.xz.sha256
```

The application never uses a `catalog.json`. The GitHub Release itself is the source of truth. See [`rootfs/README.md`](rootfs/README.md) for source provenance and rebuild instructions.

## Building from source

The project uses Kotlin, Gradle, JDK 17, Android SDK Platform 35, and Android Build Tools 35.0.0. GitHub Actions is the recommended reproducible build path.

```bash
./scripts/prepare-runtime.sh
./gradlew --no-daemon clean assembleDebug
```

The runtime preparation script retrieves the pinned PRoot loader/runtime and the ABI-specific PulseAudio dependency graph. Do not commit keys, tokens, or Android signing material. The official release workflow builds both ABI-split APKs and RootFS 1 from the same commit.

## License and third-party notices

LinuxDroid is distributed under **GPL-3.0-or-later**. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for license and attribution information, including terminal and VNC compatibility components.

[^proot]: [PRoot documentation][proot]
[^all-files]: [Android All files access documentation][all-files]
[^fgs]: [Android foreground-service restrictions][fgs]

[proot]: https://proot-me.github.io/ "PRoot documentation"
[all-files]: https://developer.android.com/training/data-storage/manage-all-files "Android All files access"
[fgs]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start "Android foreground-service restrictions"
