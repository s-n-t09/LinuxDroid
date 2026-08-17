# Debian Trixie RootFS diagnosis

The published `rootfs-pack-3` Debian images contain a resolver configuration inherited from the build host. It names the local `systemd-resolved` stub (`127.0.0.53`), but that service does not run inside an Android PRoot guest. This explains APT output ending in `Temporary failure resolving ...`; it is a guest DNS configuration defect rather than a missing Trixie archive.

The Trixie archive endpoints were verified as reachable from the build environment. Debian's current APT documentation recommends a `debian.sources` deb822 configuration and shows `deb.debian.org/debian-security` for the `trixie-security` suite. The RootFS update should therefore remove inherited source definitions, install a static `/etc/resolv.conf` with public recursive resolvers, and create `/etc/apt/sources.list.d/debian.sources` using one official host and the packaged Debian archive keyring.

References:

1. https://manpages.debian.org/trixie/apt/sources.list.5.en.html
2. https://wiki.debian.org/SourcesList
