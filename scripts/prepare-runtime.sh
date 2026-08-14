#!/usr/bin/env bash
# Prepares GPL-compatible Android runtime assets for LinuxDroid.
# Run this locally or in CI before Gradle packaging.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets/runtime"
WORK="$ROOT/.runtime-work"
mkdir -p "$ASSETS" "$WORK"

prepare_proot() {
  local abi="$1" archive url sha output
  case "$abi" in
    arm64-v8a)
      archive="proot-android-aarch64.tar.gz"
      url="https://raw.githubusercontent.com/green-green-avk/build-proot-android/master/packages/$archive"
      sha="9629eb30cdf86e95c6ba681f8ab89c6fdaa9eca093d5577163513c99af5ca281"
      ;;
    armeabi-v7a)
      archive="proot-android-armv7a.tar.gz"
      url="https://raw.githubusercontent.com/green-green-avk/build-proot-android/master/packages/$archive"
      sha="3b91c7200adf60c5a61707cf1c7540f3fd16cc97357bb17fea3f4b38819142cb"
      ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 64 ;;
  esac
  local package="$WORK/$archive" target="$ASSETS/$abi" native_target="$ROOT/app/src/main/jniLibs/$abi"
  rm -rf "$target"; mkdir -p "$target" "$native_target"
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 -o "$package" "$url"
  echo "$sha  $package" | sha256sum --check --status
  tar -xzf "$package" -C "$WORK"
  install -m 0755 "$WORK/root/bin/proot" "$target/proot"
  # APK assets are extracted into app data, which can be mounted noexec. Package
  # PRoot as a native library too so Android installs it under nativeLibraryDir.
  install -m 0755 "$WORK/root/bin/proot" "$native_target/libproot.so"
  install -m 0755 "$WORK/root/libexec/proot/loader" "$native_target/libproot_loader.so"
  rm -rf "$WORK/root"
}

record_for() {
  local index="$1" package="$2"
  awk -v pkg="$package" 'BEGIN { RS=""; FS="\n" } $1 == "Package: " pkg { print; exit }' "$index"
}

field_of() {
  local name="$1"
  awk -F': ' -v key="$name" '$1 == key { sub("^[^:]*: ", ""); print; exit }'
}

prepare_pulse() {
  local abi="$1" termux_arch index url target queue_file seen_file native_target
  case "$abi" in
    arm64-v8a) termux_arch="aarch64" ;;
    armeabi-v7a) termux_arch="arm" ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 64 ;;
  esac
  url="https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-$termux_arch/Packages.gz"
  index="$WORK/Packages-$termux_arch"
  target="$ASSETS/$abi/pulse"
  native_target="$ROOT/app/src/main/jniLibs/$abi"
  queue_file="$WORK/pulse-queue-$termux_arch"
  seen_file="$WORK/pulse-seen-$termux_arch"
  rm -rf "$target"; mkdir -p "$target" "$native_target"; : > "$queue_file"; : > "$seen_file"
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "$url" | gzip -dc > "$index"
  echo pulseaudio >> "$queue_file"

  while IFS= read -r package; do
    [[ -n "$package" ]] || continue
    grep -Fxq "$package" "$seen_file" && continue
    echo "$package" >> "$seen_file"
    local record filename depends deb extract
    record="$(record_for "$index" "$package")"
    [[ -n "$record" ]] || { echo "Skipping unavailable dependency: $package" >&2; continue; }
    filename="$(printf '%s\n' "$record" | field_of Filename)"
    depends="$(printf '%s\n' "$record" | field_of Depends || true)"
    deb="$WORK/${package}_${termux_arch}.deb"
    extract="$WORK/extract-$package-$termux_arch"
    curl --fail --location --retry 3 --proto '=https' --tlsv1.2 -o "$deb" "https://packages.termux.dev/apt/termux-main/$filename"
    rm -rf "$extract"; mkdir -p "$extract"; dpkg-deb -x "$deb" "$extract"
    if [[ -d "$extract/data/data/com.termux/files/usr" ]]; then
      cp -a "$extract/data/data/com.termux/files/usr/." "$target/"
    fi
    if [[ -n "$depends" ]]; then
      printf '%s\n' "$depends" | tr ',' '\n' | while IFS= read -r dependency; do
        dependency="$(printf '%s' "$dependency" | sed -E 's/^ *//; s/ \([^)]*\)//; s/\|.*//; s/ *$//')"
        [[ -n "$dependency" ]] && echo "$dependency" >> "$queue_file"
      done
    fi
  done < "$queue_file"

  # Only runtime executables and libraries are needed in the APK. Development headers,
  # documentation, and package metadata are large and may contain external symlinks.
  rm -rf "$target/include" "$target/share" "$target/var" "$target/etc" "$target/lib/pkgconfig"
  [[ -x "$target/bin/pulseaudio" ]] || { echo "PulseAudio binary was not extracted for $abi" >&2; exit 1; }
  chmod 0755 "$target/bin/pulseaudio"
  find "$target/lib" -type f -name '*.so*' -exec chmod 0644 {} + 2>/dev/null || true

  # Android app-data directories are mounted noexec on modern devices. PulseAudio
  # must therefore live beside PRoot in the APK native-library directory, which
  # Android extracts as executable when android:extractNativeLibs=true.
  install -m 0755 "$target/bin/pulseaudio" "$native_target/liblinuxdroid_pulseaudio.so"
  declare -A copied_native_names=()
  while IFS= read -r library; do
    name="$(basename "$library")"
    if [[ -n "${copied_native_names[$name]:-}" ]]; then
      # Termux may expose a library through two in-tree links with the same
      # basename. Keep a single native copy when both resolve to identical data.
      if cmp -s "$native_target/$name" "$library"; then
        continue
      fi
      echo "Conflicting PulseAudio native file name: $name" >&2
      exit 1
    fi
    copied_native_names[$name]="$library"
    # Install dereferences each Termux symlink, preserving both names such as
    # libiconv.so and libiconv.so.2 as executable APK native-library files.
    install -m 0644 "$library" "$native_target/$name"
  done < <(find "$target/lib" \( -type f -o -type l \) -name '*.so*' -print | sort)

  # The daemon and all required loader modules are now shipped through jniLibs;
  # do not duplicate the full Termux runtime in noexec app assets.
  rm -rf "$target"
}

for abi in arm64-v8a armeabi-v7a; do
  echo "Preparing runtime for $abi"
  prepare_proot "$abi"
  prepare_pulse "$abi"
done

echo "Prepared PRoot and PulseAudio runtime assets for arm64-v8a and armeabi-v7a."
