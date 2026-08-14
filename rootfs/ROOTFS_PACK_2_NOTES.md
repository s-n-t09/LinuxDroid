# LinuxDroid RootFS Pack 2

This is the only supported RootFS pack for LinuxDroid 0.3.0 and later. It contains customized images for Ubuntu, Debian Trixie, Alpine Linux, Arch Linux, and Fedora Linux only.

Each image includes LinuxDroid's PulseAudio client configuration, an audio helper (`linuxdroid-audio`), an audible `paplay` test file, an explanatory MOTD, storage mountpoint directories, and current HTTPS package-mirror settings. The app's Desktop Setup wizard installs the native PulseAudio client and ALSA Pulse bridge when it installs a desktop. In a minimal image, run `linuxdroid-audio setup` once to install those client packages, then run `linuxdroid-audio test`.

Fedora is available for `arm64-v8a` only because the upstream Termux PRoot Distro project does not publish a verified `armeabi-v7a` Fedora archive. LinuxDroid continues to support only `arm64-v8a` and `armeabi-v7a`; no x86/x86_64 images are included.
