#!/usr/bin/env bash
# Prepare a single LinuxDroid GitHub Release pack from the ARM rootfs images
# published by the official Termux PRoot Distro project.
#
# Output asset convention:
#   linuxdroid-rootfs__<distro>__<upstream-release>__<android-abi>.tar.xz
#   linuxdroid-rootfs__<distro>__<upstream-release>__<android-abi>.tar.xz.sha256
#
# The Android app parses those asset names from GitHub Releases directly. It
# does not use a JSON catalog. Every source and resulting asset is verified.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT/rootfs/release-pack-1}"
CACHE_DIR="${CACHE_DIR:-$ROOT/.rootfs-source-cache}"
UPSTREAM_REPO="termux/proot-distro"
PER_PAGE=100

require() { command -v "$1" >/dev/null || { echo "Missing required command: $1" >&2; exit 1; }; }
for command in curl jq sha256sum; do require "$command"; done

mkdir -p "$OUT_DIR" "$CACHE_DIR"
RELEASES_JSON="$CACHE_DIR/proot-distro-releases.json"
curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
  "https://api.github.com/repos/$UPSTREAM_REPO/releases?per_page=$PER_PAGE" \
  -o "$RELEASES_JSON"

SELECTION="$CACHE_DIR/arm-rootfs-selection.tsv"
jq -r '
  .[] | .tag_name as $tag | .assets[] |
  select(.name | test("-(aarch64|arm)-pd-v")) |
  (.name | capture("^(?<distro>.+)-(?<arch>aarch64|arm)-pd-v")) as $parsed |
  select(.digest != null and (.digest | test("^sha256:[a-fA-F0-9]{64}$"))) |
  [$parsed.distro, $parsed.arch, $tag, .name, .browser_download_url, (.digest | sub("^sha256:"; ""))] | @tsv
' "$RELEASES_JSON" | awk -F '\t' '!seen[$1 FS $2]++' > "$SELECTION"

if [[ ! -s "$SELECTION" ]]; then
  echo "No verified ARM RootFS assets were discovered upstream." >&2
  exit 1
fi

PROVENANCE="$OUT_DIR/ROOTFS_SOURCES.md"
cat > "$PROVENANCE" <<'HEADER'
# LinuxDroid RootFS Source Provenance

This release pack republishes unmodified ARM RootFS archives originating from the public releases of [Termux PRoot Distro](https://github.com/termux/proot-distro). LinuxDroid verifies each upstream SHA-256 digest before publishing the corresponding LinuxDroid asset. The application downloads only from this LinuxDroid release and verifies the published SHA-256 sidecar before extraction.

| LinuxDroid asset | Android ABI | Upstream asset | Upstream release | Upstream SHA-256 |
| --- | --- | --- | --- | --- |
HEADER

count=0
while IFS=$'\t' read -r distro upstream_arch release_tag upstream_name source_url source_hash; do
  case "$upstream_arch" in
    aarch64) abi="arm64-v8a" ;;
    arm) abi="armeabi-v7a" ;;
    *) continue ;;
  esac
  version="pd-${release_tag#v}"
  target="linuxdroid-rootfs__${distro}__${version}__${abi}.tar.xz"
  cached="$CACHE_DIR/$upstream_name"
  output="$OUT_DIR/$target"

  if [[ ! -f "$cached" ]]; then
    echo "==> Downloading $upstream_name"
    curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "$source_url" -o "$cached"
  fi
  printf '%s  %s\n' "$source_hash" "$cached" | sha256sum --check --status || {
    rm -f "$cached"
    echo "Upstream checksum mismatch for $upstream_name" >&2
    exit 1
  }

  cp -f "$cached" "$output"
  published_hash="$(sha256sum "$output" | awk '{print $1}')"
  printf '%s  %s\n' "$published_hash" "$target" > "$output.sha256"
  printf '| `%s` | `%s` | `%s` | `%s` | `%s` |\n' "$target" "$abi" "$upstream_name" "$release_tag" "$source_hash" >> "$PROVENANCE"
  count=$((count + 1))
done < "$SELECTION"

printf '%s\n' "Prepared $count verified RootFS assets in $OUT_DIR"
