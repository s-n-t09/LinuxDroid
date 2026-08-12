# LinuxDroid RootFS Repository Contract

Create a separate GitHub repository for RootFS assets, for example `your-org/linuxdroid-rootfs`. Publish archives as GitHub Release assets and publish `catalog.json` as a Release asset or a versioned raw file. LinuxDroid downloads only the catalog URL configured by the user and only accepts HTTPS artifact URLs with a valid 64-character SHA-256 checksum.

## Required release layout

| Release asset | Purpose |
| --- | --- |
| `debian-bookworm-arm64-v8a.tar.xz` | AArch64 RootFS for 64-bit Android devices |
| `debian-bookworm-armeabi-v7a.tar.xz` | ARMv7 RootFS for 32-bit Android devices |
| `catalog.json` | The schema-1 distribution catalog consumed by LinuxDroid |
| `SHA256SUMS` | Human-auditable release checksum record |

Use the direct GitHub Release asset address in the catalog, not a browser download page. For a release tag `debian-12.0`, a direct public asset URL has this shape:

```text
https://github.com/OWNER/REPOSITORY/releases/download/debian-12.0/debian-bookworm-arm64-v8a.tar.xz
```

## RootFS rules

Each RootFS must be native to its declared ABI. Do not place AArch64 executables in the `armeabi-v7a` archive or ARMv7 executables in the `arm64-v8a` archive. LinuxDroid does not bundle QEMU and does not emulate a foreign CPU architecture.

Do not archive device nodes, sockets, host files, or absolute host-specific configuration. Include a minimal `/etc/hosts`, `/etc/resolv.conf`, standard package-manager keys, a working `/bin/sh`, and package repositories compatible with the image. Do not make the archive world-writable.

The desktop setup currently recognizes `apt-get`, `pacman`, `apk`, and `dnf`. Test your image's package repositories and ensure one of those package managers is usable before publishing a distribution.

## Publishing procedure

Build each image in a controlled Linux environment, remove package caches and private keys, and create the archive deterministically where practical. Calculate the SHA-256 from the exact uploaded bytes, put that checksum in both `SHA256SUMS` and `catalog.json`, create the GitHub Release, and then test an install through a fresh LinuxDroid application data directory.

> Never replace an existing public release asset without changing its version and catalog hash. LinuxDroid correctly rejects a changed archive whose SHA-256 no longer matches, but immutable versioned releases make debugging and rollback substantially safer.

See `catalog.example.json` for the exact schema.
