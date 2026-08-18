# LinuxDroid RootFS 1

LinuxDroid 1.0 discovers Linux images directly from the public [RootFS 1 GitHub Release](https://github.com/s-n-t09/LinuxDroid/releases/tag/rootfs-1). The release is the source of truth: the application does not download, cache, or interpret a `catalog.json` file.

## Supported distributions and architectures

RootFS 1 contains only the LinuxDroid-supported distributions: Ubuntu, Debian Trixie, Alpine Linux, Arch Linux, and Fedora Linux. The project supports only Android ARM ABIs.

| Artifact | Rule |
| --- | --- |
| `arm64-v8a` image | Native AArch64 guest programs only. |
| `armeabi-v7a` image | Native ARMv7 guest programs only, when a verified upstream image exists. |
| x86 / x86_64 image | Never publish; LinuxDroid does not include CPU emulation. |

## Required release layout

Every RootFS archive must have a same-name SHA-256 sidecar.

```text
linuxdroid-rootfs__<distro-id>__<version>__arm64-v8a.tar.xz
linuxdroid-rootfs__<distro-id>__<version>__arm64-v8a.tar.xz.sha256
linuxdroid-rootfs__<distro-id>__<version>__armeabi-v7a.tar.xz
linuxdroid-rootfs__<distro-id>__<version>__armeabi-v7a.tar.xz.sha256
ROOTFS_RELEASE_NOTES.md
ROOTFS_SOURCES.md
```

For example:

```text
linuxdroid-rootfs__debian-trixie__ld-1.0__arm64-v8a.tar.xz
linuxdroid-rootfs__debian-trixie__ld-1.0__arm64-v8a.tar.xz.sha256
```

The Android client accepts only this strict naming format and verifies the exact SHA-256 value before extraction. Never replace bytes under an already published asset name.

## Building RootFS 1

The source-controlled builder obtains checksum-bearing upstream archives from the official Termux PRoot Distro release feed, verifies each upstream digest, applies LinuxDroid’s overlay, and creates release-ready assets. It configures PulseAudio client defaults, ALSA defaults, the `linuxdroid-audio` helper, MOTD, storage mountpoints, and package mirrors. Debian Trixie receives its official deb822 APT sources and a baseline resolver; LinuxDroid then refreshes its resolver from Android’s active network at session start.

```bash
chmod +x rootfs/build-rootfs-1.sh
rootfs/build-rootfs-1.sh
```

The output is written to `rootfs/release-1/`. This generated directory is intentionally not committed. Publish its archives, checksum sidecars, `ROOTFS_RELEASE_NOTES.md`, and `ROOTFS_SOURCES.md` as the `rootfs-1` GitHub Release assets.

## Safety and compatibility rules

Each RootFS must contain a working `/bin/sh`, valid package-manager configuration, package-manager keys, and no device nodes, sockets, Android host paths, or architecture-mismatched executables. LinuxDroid’s desktop setup supports `apt-get`, `apk`, `pacman`, and `dnf`; test the relevant package manager before publishing a new image.

> A new RootFS version token and a new release tag are required whenever published archive bytes change. This keeps checksum verification, rollback, and troubleshooting trustworthy.
