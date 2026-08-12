#!/usr/bin/env bash
# Build LinuxDroid's Debian Bookworm RootFS archives for Android ARM devices.
# Requires: debootstrap, qemu-user-static, sudo, xz-utils.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT/rootfs/out}"
WORK_DIR="${WORK_DIR:-$ROOT/.rootfs-work}"
SUITE="${SUITE:-bookworm}"
MIRROR="${MIRROR:-https://deb.debian.org/debian}"
VERSION="${VERSION:-1}"
KEYRING_URL="https://deb.debian.org/debian/pool/main/d/debian-archive-keyring/debian-archive-keyring_2025.1_all.deb"
KEYRING_SHA256="9ea7778e443144ca490668737a8ab22dd3e748bb99e805e22ec055abeb3c7fac"

mkdir -p "$OUTPUT_DIR" "$WORK_DIR"

build_rootfs() {
  local abi="$1" deb_arch qemu root archive packages
  case "$abi" in
    arm64-v8a) deb_arch="arm64"; qemu="/usr/bin/qemu-aarch64-static" ;;
    armeabi-v7a) deb_arch="armhf"; qemu="/usr/bin/qemu-arm-static" ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 64 ;;
  esac

  root="$WORK_DIR/debian-$SUITE-$abi"
  archive="$OUTPUT_DIR/debian-$SUITE-$abi.tar.xz"
  sudo rm -rf "$root"
  rm -f "$archive"
  mkdir -p "$root"

  echo "==> Bootstrap Debian $SUITE for $abi ($deb_arch)"
  sudo debootstrap --foreign --arch="$deb_arch" --variant=minbase \
    --include=bash,ca-certificates,curl,wget,nano,vim-tiny,less,file,procps,iproute2,iputils-ping,net-tools,sudo,locales,tzdata \
    "$SUITE" "$root" "$MIRROR"

  # The host's enabled binfmt QEMU handlers execute both guest architectures inside
  # the isolated chroot and preserve child-process execution during debootstrap.
  sudo chroot "$root" /debootstrap/debootstrap --second-stage

  # Bookworm's bootstrap keyring predates current archive signing keys. Update it
  # from a pinned Debian package before the first APT transaction.
  local keyring_deb="$WORK_DIR/debian-archive-keyring_2025.1_all.deb"
  local keyring_extract="$WORK_DIR/keyring-extract-$abi"
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 -o "$keyring_deb" "$KEYRING_URL"
  echo "$KEYRING_SHA256  $keyring_deb" | sha256sum --check --status
  rm -rf "$keyring_extract"; mkdir -p "$keyring_extract"
  dpkg-deb -x "$keyring_deb" "$keyring_extract"
  sudo cp -a "$keyring_extract/usr/share/keyrings/." "$root/usr/share/keyrings/"
  sudo cp -a "$keyring_extract/etc/apt/trusted.gpg.d/." "$root/etc/apt/trusted.gpg.d/"

  sudo chroot "$root" /bin/sh <<'GUEST_SETUP'
set -eux
export DEBIAN_FRONTEND=noninteractive
printf '%s\n' \
  'deb [signed-by=/usr/share/keyrings/debian-archive-keyring.pgp] https://deb.debian.org/debian bookworm main contrib non-free non-free-firmware' \
  'deb [signed-by=/usr/share/keyrings/debian-archive-keyring.pgp] https://deb.debian.org/debian bookworm-updates main contrib non-free non-free-firmware' \
  'deb [signed-by=/usr/share/keyrings/debian-archive-keyring.pgp] https://security.debian.org/debian-security bookworm-security main contrib non-free non-free-firmware' \
  > /etc/apt/sources.list
printf '%s\n' 'linuxdroid' > /etc/hostname
cat > /etc/hosts <<'HOSTS'
127.0.0.1 localhost
127.0.1.1 linuxdroid
::1 localhost ip6-localhost ip6-loopback
HOSTS
# Utility packages are installed during debootstrap's second stage, which avoids
# running a second networked APT transaction under QEMU user-mode emulation.
printf '%s\n' 'en_US.UTF-8 UTF-8' > /etc/locale.gen
locale-gen
cat > /etc/profile.d/linuxdroid.sh <<'PROFILE'
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
export PULSE_SERVER=tcp:127.0.0.1:4713
PROFILE
chmod 0644 /etc/profile.d/linuxdroid.sh
printf '%s\n' 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' >> /root/.profile
dpkg-query -W -f='${binary:Package}\t${Version}\n' | sort > /etc/linuxdroid-rootfs-packages.txt
rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* /tmp/* /var/tmp/*
GUEST_SETUP

  sudo rm -f "$root/etc/resolv.conf"
  printf '%s\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' | sudo tee "$root/etc/resolv.conf" >/dev/null
  sudo find "$root" -xdev -type f -name '*.pyc' -delete
  sudo tar --numeric-owner --xattrs --acls --sort=name \
    --mtime='UTC 2026-08-13' -C "$root" -cJf "$archive" .
  sudo chown "$(id -u):$(id -g)" "$archive"
  sha256sum "$archive" > "$archive.sha256"
  packages="$OUTPUT_DIR/debian-$SUITE-$abi-packages.txt"
  sudo cp "$root/etc/linuxdroid-rootfs-packages.txt" "$packages"
  sudo chown "$(id -u):$(id -g)" "$packages"
  printf 'Built %s\n' "$archive"
}

build_rootfs arm64-v8a
build_rootfs armeabi-v7a
printf '\nRootFS outputs:\n'
sha256sum "$OUTPUT_DIR"/*.tar.xz
