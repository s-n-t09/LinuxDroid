#!/usr/bin/env bash
# Build LinuxDroid's customized RootFS pack. The published pack contains only:
# Ubuntu, Debian Trixie, Alpine, Arch Linux, and Fedora.
#
# Each archive receives a LinuxDroid overlay with:
#   - PulseAudio TCP client configuration for the app-managed local server
#   - a portable linuxdroid-audio helper and a short audible WAV test
#   - a clear MOTD and storage mountpoint directories
#   - distribution-appropriate HTTPS mirror configuration
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT/rootfs/release-pack-3}"
CACHE_DIR="${CACHE_DIR:-$ROOT/.rootfs-source-cache}"
WORK_DIR="${WORK_DIR:-$ROOT/.rootfs-work-pack-3}"
UPSTREAM_REPO="termux/proot-distro"
PACK_VERSION="${PACK_VERSION:-ld-2026.08-r2}"
RELEASES_JSON="$CACHE_DIR/proot-distro-releases.json"

require() { command -v "$1" >/dev/null || { echo "Missing required command: $1" >&2; exit 1; }; }
for command in curl jq sha256sum tar python3; do require "$command"; done

mkdir -p "$OUT_DIR" "$CACHE_DIR" "$WORK_DIR"
rm -f "$OUT_DIR"/linuxdroid-rootfs__*.tar.xz "$OUT_DIR"/linuxdroid-rootfs__*.tar.xz.sha256

# GitHub returns release entries newest first. Preserve the first verified asset for each ABI.
if [[ ! -s "$RELEASES_JSON" || "${REFRESH_SOURCES:-0}" == 1 ]]; then
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
    "https://api.github.com/repos/$UPSTREAM_REPO/releases?per_page=100" -o "$RELEASES_JSON"
fi

cat > "$OUT_DIR/ROOTFS_RELEASE_NOTES.md" <<'EOF'
# LinuxDroid RootFS Pack 3

This is the only supported RootFS pack for LinuxDroid 0.4.0 and later. It contains customized images for Ubuntu, Debian Trixie, Alpine Linux, Arch Linux, and Fedora Linux only.

Each image includes LinuxDroid's PulseAudio client configuration, an audio helper (`linuxdroid-audio`), an audible `paplay` test file, an explanatory MOTD, storage mountpoint directories, and current HTTPS package-mirror settings. The app's Desktop Setup wizard installs the native PulseAudio client and ALSA Pulse bridge when it installs a desktop. In a minimal image, run `linuxdroid-audio setup` once to install those client packages, then run `linuxdroid-audio test`.

Fedora is available for `arm64-v8a` only because the upstream Termux PRoot Distro project does not publish a verified `armeabi-v7a` Fedora archive. LinuxDroid continues to support only `arm64-v8a` and `armeabi-v7a`; no x86/x86_64 images are included.
EOF

cat > "$OUT_DIR/ROOTFS_SOURCES.md" <<'EOF'
# LinuxDroid RootFS Pack 3 Source Provenance

| LinuxDroid asset | Android ABI | Upstream asset | Upstream release | Upstream SHA-256 |
| --- | --- | --- | --- | --- |
EOF

# target-id:upstream-id; Ubuntu is intentionally presented as one Ubuntu choice.
SELECTIONS=(
  "alpine:alpine"
  "archlinux:archlinux"
  "debian-trixie:debian-trixie"
  "fedora:fedora"
  "ubuntu:ubuntu-questing"
)

find_asset() {
  local upstream_id="$1" upstream_arch="$2"
  jq -r --arg id "$upstream_id" --arg arch "$upstream_arch" '
    .[] | .tag_name as $tag | .assets[] |
    select(.name | test("^(debian-trixie|ubuntu-questing|alpine|archlinux|fedora)-(aarch64|arm)-pd-v")) |
    (.name | capture("^(?<distro>.+)-(?<arch>aarch64|arm)-pd-v")) as $parsed |
    select($parsed.distro == $id and $parsed.arch == $arch) |
    select(.digest != null and (.digest | test("^sha256:[a-fA-F0-9]{64}$"))) |
    [.name, .browser_download_url, (.digest | sub("^sha256:"; "")), $tag] | @tsv
  ' "$RELEASES_JSON" | head -n 1
}

write_wav_tone() {
  local destination="$1"
  python3 - "$destination" <<'PY'
import math
import struct
import sys
import wave

path = sys.argv[1]
rate = 44100
seconds = 1.0
with wave.open(path, "wb") as wav:
    wav.setnchannels(1)
    wav.setsampwidth(2)
    wav.setframerate(rate)
    for sample in range(int(rate * seconds)):
        envelope = min(1.0, sample / (rate * 0.03), (rate * seconds - sample) / (rate * 0.05))
        value = int(0.24 * envelope * 32767 * math.sin(2.0 * math.pi * 880.0 * sample / rate))
        wav.writeframesraw(struct.pack("<h", value))
PY
}

overlay_common() {
  local stage="$1" distro="$2"
  mkdir -p "$stage/etc/pulse" "$stage/etc/profile.d" "$stage/usr/local/bin" \
    "$stage/usr/share/linuxdroid" "$stage/storage/emulated/0" "$stage/sdcard"
  cat > "$stage/etc/pulse/client.conf" <<'EOF'
# LinuxDroid guest clients use the private PulseAudio server started by the app.
default-server = tcp:127.0.0.1:4713
autospawn = no
daemon-binary = /bin/true
EOF
  cat > "$stage/etc/profile.d/linuxdroid-audio.sh" <<'EOF'
# LinuxDroid private audio bridge. The host service accepts loopback connections only.
export PULSE_SERVER="${PULSE_SERVER:-tcp:127.0.0.1:4713}"
export PULSE_COOKIE="${PULSE_COOKIE:-/tmp/linuxdroid-pulse.cookie}"
export PULSE_CLIENTCONFIG="${PULSE_CLIENTCONFIG:-/etc/pulse/client.conf}"
export PULSE_LATENCY_MSEC="${PULSE_LATENCY_MSEC:-60}"
export ALSA_CONFIG_PATH="${ALSA_CONFIG_PATH:-/etc/asound.conf}"
EOF
  cat > "$stage/etc/profile.d/linuxdroid-motd.sh" <<'EOF'
# Print the LinuxDroid MOTD once per interactive login shell.
if [ "${LINUXDROID_MOTD_ENABLED:-1}" = "1" ] && [ -n "${PS1:-}" ] && [ "${LINUXDROID_MOTD_SHOWN:-0}" != "1" ] && [ -r /etc/motd ]; then
  cat /etc/motd
  export LINUXDROID_MOTD_SHOWN=1
fi
EOF
  cat > "$stage/etc/asound.conf" <<'EOF'
pcm.!default {
  type pulse
  fallback "sysdefault"
}
ctl.!default {
  type pulse
  fallback "sysdefault"
}
EOF
  cp "$stage/etc/asound.conf" "$stage/root/.asoundrc"
  cat > "$stage/usr/local/bin/linuxdroid-audio" <<'EOF'
#!/bin/sh
set -eu
export PULSE_SERVER="${PULSE_SERVER:-tcp:127.0.0.1:4713}"
export PULSE_COOKIE="${PULSE_COOKIE:-/tmp/linuxdroid-pulse.cookie}"

setup() {
  if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update && apt-get install -y pulseaudio-utils libpulse0 libasound2-plugins
  elif command -v pacman >/dev/null 2>&1; then
    pacman -Syu --noconfirm pulseaudio alsa-plugins
  elif command -v apk >/dev/null 2>&1; then
    apk add pulseaudio-utils alsa-plugins-pulse
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y pulseaudio-utils alsa-plugins-pulseaudio
  else
    echo "No supported package manager was found." >&2
    exit 2
  fi
}

test_audio() {
  if ! command -v pactl >/dev/null 2>&1 || ! command -v paplay >/dev/null 2>&1; then
    echo "Installing the LinuxDroid PulseAudio client first…"
    setup
  fi
  echo "PULSE_SERVER=$PULSE_SERVER"
  pactl info >/dev/null || {
    echo "The LinuxDroid PulseAudio service is unavailable. Stop and restart the distribution, then review Session logs for pulseaudio.log." >&2
    exit 1
  }
  pactl list short sinks
  paplay /usr/share/linuxdroid/tone.wav
  echo "LinuxDroid audio test finished."
}

case "${1:-test}" in
  setup) setup ;;
  test) test_audio ;;
  info) command -v pactl >/dev/null 2>&1 && pactl info || true ;;
  *) echo "Usage: linuxdroid-audio {setup|test|info}" >&2; exit 64 ;;
esac
EOF
  chmod 755 "$stage/usr/local/bin/linuxdroid-audio"
  write_wav_tone "$stage/usr/share/linuxdroid/tone.wav"
  cat > "$stage/etc/motd" <<EOF
Welcome to $distro on LinuxDroid.

Shared Android storage appears at /storage/emulated/0 when All files access is enabled in LinuxDroid.
Audio uses LinuxDroid's private PulseAudio server at tcp:127.0.0.1:4713.
Run: linuxdroid-audio test
For minimal images, the command installs guest PulseAudio and ALSA bridge packages on its first run.
Use `pactl info` to inspect the client connection and `speaker-test -t sine` after setup.
EOF
}

configure_mirrors() {
  local stage="$1" distro="$2"
  case "$distro" in
    debian-trixie)
      cat > "$stage/etc/apt/sources.list" <<'EOF'
deb https://deb.debian.org/debian trixie main contrib non-free non-free-firmware
deb https://deb.debian.org/debian trixie-updates main contrib non-free non-free-firmware
deb https://security.debian.org/debian-security trixie-security main contrib non-free non-free-firmware
EOF
      ;;
    ubuntu)
      cat > "$stage/etc/apt/sources.list" <<'EOF'
deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] https://ports.ubuntu.com/ubuntu-ports questing main restricted universe multiverse
deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] https://ports.ubuntu.com/ubuntu-ports questing-updates main restricted universe multiverse
deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] https://ports.ubuntu.com/ubuntu-ports questing-security main restricted universe multiverse
EOF
      ;;
    alpine)
      cat > "$stage/etc/apk/repositories" <<'EOF'
https://dl-cdn.alpinelinux.org/alpine/v3.22/main
https://dl-cdn.alpinelinux.org/alpine/v3.22/community
EOF
      ;;
    archlinux)
      mkdir -p "$stage/etc/pacman.d"
      cat > "$stage/etc/pacman.d/mirrorlist" <<'EOF'
Server = https://mirror.archlinuxarm.org/$arch/$repo
EOF
      ;;
    fedora)
      printf '\nfastestmirror=True\nmax_parallel_downloads=10\n' >> "$stage/etc/dnf/dnf.conf"
      ;;
  esac
}

count=0
for selection in "${SELECTIONS[@]}"; do
  target_id="${selection%%:*}"
  upstream_id="${selection##*:}"
  for upstream_arch in aarch64 arm; do
    record="$(find_asset "$upstream_id" "$upstream_arch" || true)"
    if [[ -z "$record" ]]; then
      echo "No verified $upstream_arch image for $upstream_id; skipping."
      continue
    fi
    IFS=$'\t' read -r upstream_name source_url upstream_hash release_tag <<< "$record"
    abi="arm64-v8a"; [[ "$upstream_arch" == arm ]] && abi="armeabi-v7a"
    cached="$CACHE_DIR/$upstream_name"
    if [[ ! -f "$cached" ]]; then
      echo "==> Downloading $upstream_name"
      curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "$source_url" -o "$cached"
    fi
    printf '%s  %s\n' "$upstream_hash" "$cached" | sha256sum --check --status

    stage_root="$WORK_DIR/${target_id}-${abi}"
    rm -rf "$stage_root"
    mkdir -p "$stage_root"
    tar -xJf "$cached" -C "$stage_root" --exclude='*/dev/*'
    # Upstream rootfs archives may retain root-only helper modes. The current
    # build user owns the extracted tree, so grant it read access for repacking.
    chmod -R u+rwX "$stage_root"
    source_root="$(find "$stage_root" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
    [[ -n "$source_root" && ( -f "$source_root/bin/sh" || -L "$source_root/bin/sh" ) ]] || { echo "Invalid RootFS layout: $upstream_name" >&2; exit 1; }
    overlay_common "$source_root" "$target_id"
    configure_mirrors "$source_root" "$target_id"

    target="linuxdroid-rootfs__${target_id}__${PACK_VERSION}__${abi}.tar.xz"
    output="$OUT_DIR/$target"
    echo "==> Building $target"
    tar --numeric-owner --xattrs --acls -C "$stage_root" -cJf "$output" "$(basename "$source_root")"
    published_hash="$(sha256sum "$output" | awk '{print $1}')"
    printf '%s  %s\n' "$published_hash" "$target" > "$output.sha256"
    printf '| `%s` | `%s` | `%s` | `%s` | `%s` |\n' "$target" "$abi" "$upstream_name" "$release_tag" "$upstream_hash" >> "$OUT_DIR/ROOTFS_SOURCES.md"
    count=$((count + 1))
  done
done

[[ "$count" -gt 0 ]] || { echo "No customized RootFS assets were produced." >&2; exit 1; }
printf 'Prepared %s customized RootFS assets in %s\n' "$count" "$OUT_DIR"
