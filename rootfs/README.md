# LinuxDroid RootFS Release Contract

LinuxDroid obtains RootFS metadata directly from the public [GitHub Releases API](https://api.github.com/repos/s-n-t09/LinuxDroid/releases/tags/rootfs-pack-1). It does not expose a configurable RootFS URL.

The application queries the immutable release tag `rootfs-pack-1`, parses supported archive names, and downloads only matching assets from that release. The release is intentionally hosted in the same `s-n-t09/LinuxDroid` repository as the application source.

## Required release layout

| Release asset | Purpose |
| --- | --- |
| `linuxdroid-rootfs__<distro-id>__<version>__arm64-v8a.tar.xz` | AArch64 RootFS archive for a 64-bit ARM Android device |
| `linuxdroid-rootfs__<distro-id>__<version>__armeabi-v7a.tar.xz` | ARMv7 RootFS archive for a 32-bit ARM Android device, when upstream provides it |
| `<archive-name>.sha256` | SHA-256 sidecar consumed by LinuxDroid before extraction |
| `ROOTFS_SOURCES.md` | Human-auditable source, release, and SHA-256 provenance record |

For example:

```text
linuxdroid-rootfs__archlinux__pd-4.34.2__arm64-v8a.tar.xz
linuxdroid-rootfs__archlinux__pd-4.34.2__arm64-v8a.tar.xz.sha256
```

The archive-name parser is intentionally strict. Do not rename an existing asset in place or reuse a version token for altered bytes.

## Preparing a release pack

Run the source-controlled `prepare-release-pack.sh` script from this directory. It discovers all public, checksum-bearing `aarch64` and `arm` RootFS archives offered by the official Termux PRoot Distro release feed, checks their upstream SHA-256 digests, maps their architectures to Android ABI names, and writes the release-ready assets into `rootfs/release-pack-1/`.

```bash
./rootfs/prepare-release-pack.sh
```

The generated directory is intentionally ignored by Git. Publish its `*.tar.xz`, `*.tar.xz.sha256`, and `ROOTFS_SOURCES.md` files as assets for `rootfs-pack-1`; do not commit large RootFS archives to the source branch.

## RootFS rules

Each RootFS must be native to its declared ABI. Do not place AArch64 executables in an `armeabi-v7a` archive or ARMv7 executables in an `arm64-v8a` archive. LinuxDroid does not bundle QEMU and does not emulate a foreign CPU architecture.

Do not archive device nodes, sockets, host files, or absolute host-specific configuration. Include a minimal `/etc/hosts`, `/etc/resolv.conf`, standard package-manager keys, a working `/bin/sh`, and package repositories compatible with the image. Do not make the archive world-writable.

The desktop setup recognizes `apt-get`, `pacman`, `apk`, and `dnf`. Test that at least one of those package managers and its configured repositories works before publishing a new distribution.

> The application verifies the exact SHA-256 value from the matching sidecar before extraction. Use a new version token and a new release tag if published bytes change. Immutable versioned release assets make rollback and troubleshooting safer.
