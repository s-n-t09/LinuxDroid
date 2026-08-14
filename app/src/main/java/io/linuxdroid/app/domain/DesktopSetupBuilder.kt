package io.linuxdroid.app.domain

import io.linuxdroid.app.data.BrowserChoice
import io.linuxdroid.app.data.DesktopEnvironment
import io.linuxdroid.app.data.SetupSelection

class DesktopSetupBuilder {
    fun build(selection: SetupSelection): String {
        val desktop = selection.desktop.name
        val browser = selection.browser.name
        val media = if (selection.mediaAndTextTools) "yes" else "no"
        val desktopCommand = when (selection.desktop) {
            DesktopEnvironment.XFCE -> "startxfce4"
            DesktopEnvironment.LXDE -> "startlxde"
            DesktopEnvironment.MATE -> "mate-session"
            DesktopEnvironment.FLUXBOX -> "fluxbox"
        }
        val passwordCommand = selection.createVncPassword?.takeIf { it.isNotBlank() }?.let { password ->
            "printf %s ${shellQuote(password)} | vncpasswd -f > \"${'$'}HOME/.config/tigervnc/passwd\" && chmod 600 \"${'$'}HOME/.config/tigervnc/passwd\""
        } ?: "echo 'No VNC password was supplied. Set one with: vncpasswd'"

        return """
            #!/bin/sh
            set -eu
            DESKTOP=${shellQuote(desktop)}
            BROWSER=${shellQuote(browser)}
            MEDIA=${shellQuote(media)}
            install_apt() {
              export DEBIAN_FRONTEND=noninteractive
              case "${'$'}DESKTOP" in
                XFCE) desktop_pkgs="xfce4 xfce4-goodies dbus-x11" ;;
                LXDE) desktop_pkgs="lxde dbus-x11" ;;
                MATE) desktop_pkgs="mate-desktop-environment dbus-x11" ;;
                FLUXBOX) desktop_pkgs="fluxbox dbus-x11" ;;
              esac
              case "${'$'}BROWSER" in FIREFOX) browser_pkgs="firefox-esr" ;; CHROMIUM) browser_pkgs="chromium" ;; *) browser_pkgs="" ;; esac
              media_pkgs=""; [ "${'$'}MEDIA" = yes ] && media_pkgs="mpv vlc ffmpeg imagemagick nano vim less file"
              audio_pkgs="pulseaudio-utils libasound2-plugins"
              apt-get update
              apt-get install -y ${'$'}desktop_pkgs tigervnc-standalone-server ${'$'}audio_pkgs ${'$'}browser_pkgs ${'$'}media_pkgs
            }
            install_pacman() {
              case "${'$'}DESKTOP" in
                XFCE) desktop_pkgs="xfce4 xfce4-goodies" ;;
                LXDE) desktop_pkgs="lxde" ;;
                MATE) desktop_pkgs="mate mate-extra" ;;
                FLUXBOX) desktop_pkgs="fluxbox" ;;
              esac
              case "${'$'}BROWSER" in FIREFOX) browser_pkgs="firefox" ;; CHROMIUM) browser_pkgs="chromium" ;; *) browser_pkgs="" ;; esac
              media_pkgs=""; [ "${'$'}MEDIA" = yes ] && media_pkgs="mpv vlc ffmpeg imagemagick nano vim less file"
              audio_pkgs="pulseaudio alsa-plugins"
              pacman -Syu --noconfirm ${'$'}desktop_pkgs tigervnc ${'$'}audio_pkgs ${'$'}browser_pkgs ${'$'}media_pkgs
            }
            install_apk() {
              case "${'$'}DESKTOP" in
                XFCE) desktop_pkgs="xfce4 xfce4-terminal" ;;
                LXDE) desktop_pkgs="lxde" ;;
                MATE) desktop_pkgs="mate" ;;
                FLUXBOX) desktop_pkgs="fluxbox" ;;
              esac
              case "${'$'}BROWSER" in FIREFOX) browser_pkgs="firefox-esr" ;; CHROMIUM) browser_pkgs="chromium" ;; *) browser_pkgs="" ;; esac
              media_pkgs=""; [ "${'$'}MEDIA" = yes ] && media_pkgs="mpv vlc ffmpeg imagemagick nano vim less file"
              audio_pkgs="pulseaudio-utils alsa-plugins-pulse"
              apk add ${'$'}desktop_pkgs tigervnc ${'$'}audio_pkgs ${'$'}browser_pkgs ${'$'}media_pkgs
            }
            install_dnf() {
              case "${'$'}DESKTOP" in
                XFCE) desktop_pkgs="@xfce-desktop-environment" ;;
                LXDE) desktop_pkgs="lxde-common lxpanel openbox" ;;
                MATE) desktop_pkgs="@mate-desktop-environment" ;;
                FLUXBOX) desktop_pkgs="fluxbox" ;;
              esac
              case "${'$'}BROWSER" in FIREFOX) browser_pkgs="firefox" ;; CHROMIUM) browser_pkgs="chromium" ;; *) browser_pkgs="" ;; esac
              media_pkgs=""; [ "${'$'}MEDIA" = yes ] && media_pkgs="mpv vlc ffmpeg ImageMagick nano vim-enhanced less file"
              audio_pkgs="pulseaudio-utils alsa-plugins-pulseaudio"
              dnf install -y ${'$'}desktop_pkgs tigervnc-server ${'$'}audio_pkgs ${'$'}browser_pkgs ${'$'}media_pkgs
            }
            if command -v apt-get >/dev/null 2>&1; then install_apt
            elif command -v pacman >/dev/null 2>&1; then install_pacman
            elif command -v apk >/dev/null 2>&1; then install_apk
            elif command -v dnf >/dev/null 2>&1; then install_dnf
            else echo "Unsupported package manager. Install a VNC server and run ${desktopCommand}." >&2; exit 2
            fi
            # TigerVNC 1.15+ uses ~/.config/tigervnc; ~/.vnc is deprecated.
            rm -rf "${'$'}HOME/.vnc"
            mkdir -p "${'$'}HOME/.config/tigervnc"
            cat > "${'$'}HOME/.config/tigervnc/xstartup" <<'EOF'
            #!/bin/sh
            unset SESSION_MANAGER
            unset DBUS_SESSION_BUS_ADDRESS
            ${desktopCommand}
            EOF
            chmod 700 "${'$'}HOME/.config/tigervnc/xstartup"
            ${passwordCommand}
            cat > "${'$'}HOME/start-linuxdroid-desktop" <<'EOF'
            #!/bin/sh
            set -eu
            vncserver -kill :1 >/dev/null 2>&1 || true
            vncserver :1 -localhost yes -geometry 1280x720 -depth 24
            EOF
            chmod 700 "${'$'}HOME/start-linuxdroid-desktop"
            echo "Desktop setup complete. Start it with: ~/start-linuxdroid-desktop"
            echo "Then open LinuxDroid VNC using 127.0.0.1:5901."
        """.trimIndent() + "\n"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
