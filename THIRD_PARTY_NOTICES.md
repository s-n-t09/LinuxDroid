# Third-Party Notices

LinuxDroid is distributed as GPL-3.0-or-later software. Source code, notices, and corresponding-source obligations for all components must remain available with every distribution.

| Component | Usage in LinuxDroid | License / notice |
| --- | --- | --- |
| PRoot | ABI-specific relocatable runtime executable | PRoot project license; Android build packaging from `green-green-avk/build-proot-android` is MIT. [1] [2] |
| Termux terminal-view and terminal-emulator | Integrated terminal PTY, renderer, input and extra-key handling | GPL-3.0-or-later compatibility required. [3] [4] |
| bVNC | VNC connection semantics and classic VNC password compatibility helper reference | bVNC is GPL v3 and includes notices for android-vnc-viewer, TightVNC, TigerVNC, FreeRDP, and others. The LinuxDroid helper retains the relevant attribution. [5] |
| PulseAudio runtime | Local Android host sound daemon and Android sound sink module | PulseAudio licensing and the Termux package build recipe apply. [6] [7] |
| OkHttp | HTTPS catalog and RootFS downloads | Apache License 2.0. [8] |
| Apache Commons Compress | Verified compressed tar RootFS extraction | Apache License 2.0. [9] |
| Kotlin serialization and coroutines | Catalog/state encoding and asynchronous work | Apache License 2.0. [10] |

## bVNC compatibility attribution

`app/src/main/java/io/linuxdroid/app/vnc/BvncCompatibility.kt` implements the legacy VNC DES key-bit ordering used by bVNC’s `DesCipher`. The upstream source identifies bVNC as GPL v3 and includes its own upstream copyright inventory. Any expansion that copies bVNC classes, resources, native code, or build modules must also copy the full relevant copyright notices and make the complete corresponding source available under GPL-compatible terms.

## Runtime source review

`scripts/prepare-runtime.sh` downloads dependencies only over HTTPS, verifies pinned PRoot SHA-256 hashes, and extracts public Termux packages for both supported ABIs. Review the source repository, version, package index, hashes, dependency closure, and licenses before publishing an APK. A maintainer is responsible for compliance with all upstream licensing, Android distribution policies, and local law.

## References

[1]: https://proot-me.github.io/ "PRoot"
[2]: https://github.com/green-green-avk/build-proot-android "build-proot-android"
[3]: https://github.com/termux/termux-app "Termux application source"
[4]: https://github.com/termux/termux-app/wiki/Termux-Libraries "Termux libraries documentation"
[5]: https://github.com/iiordanov/remote-desktop-clients/blob/master/COPYRIGHT-bVNC "bVNC copyright and license notice"
[6]: https://www.freedesktop.org/wiki/Software/PulseAudio/ "PulseAudio"
[7]: https://github.com/termux/termux-packages/tree/master/packages/pulseaudio "Termux PulseAudio package recipe"
[8]: https://square.github.io/okhttp/ "OkHttp"
[9]: https://commons.apache.org/proper/commons-compress/ "Apache Commons Compress"
[10]: https://github.com/Kotlin/kotlinx.serialization "kotlinx.serialization"
