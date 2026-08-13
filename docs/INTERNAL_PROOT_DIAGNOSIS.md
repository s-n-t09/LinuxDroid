# Internal PRoot Diagnostic Notes

## Evidence from Android session logs

Both Alpine and Debian reached `execve()` with a valid guest shell path but failed with `ENOENT`. This made the fault independent of a particular RootFS archive.

## Root cause

The Android PRoot package used by LinuxDroid ships two required components:

- `root/bin/proot`
- `root/libexec/proot/loader` (and `loader32`)

LinuxDroid previously bundled only `root/bin/proot`. The package README describes it as an unbundled-loader, freely relocatable file tree. PRoot source in `src/execve/enter.c` resolves `PROOT_LOADER` or its configured unbundled loader path before executing guest ELF binaries. Missing the loader produces the observed generic `execve("/bin/sh"): No such file or directory` for all distributions.

## Planned remediation

Bundle the matching loader as an Android native library, use its extracted native-library path through `PROOT_LOADER`, and preserve the PRoot executable and loader together in the same installable APK. Retain the session logger to confirm the resolved loader path at runtime.

Sources consulted:

- https://github.com/green-green-avk/build-proot-android/blob/master/README.md
- https://github.com/green-green-avk/proot/blob/master/doc/usage/android/start-script-example
- https://github.com/termux/proot/issues/292
