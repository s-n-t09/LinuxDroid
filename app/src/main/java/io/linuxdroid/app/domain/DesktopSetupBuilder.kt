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
            "if command -v vncpasswd >/dev/null 2>&1; then printf %s ${shellQuote(password)} | vncpasswd -f > \"${'$'}HOME/.vnc/passwd\"; else x11vnc -storepasswd ${shellQuote(password)} \"${'$'}HOME/.vnc/passwd\"; fi; chmod 600 \"${'$'}HOME/.vnc/passwd\""
        } ?: "echo 'No VNC password was supplied. Before starting the desktop, run: x11vnc -storepasswd YOUR_PASSWORD ~/.vnc/passwd'"

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
              apt-get install -y ${'$'}desktop_pkgs tightvncserver x11vnc xvfb ${'$'}audio_pkgs ${'$'}browser_pkgs ${'$'}media_pkgs
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
              pacman -Syu --noconfirm ${'$'}desktop_pkgs x11vnc xorg-server-xvfb ${'$'}audio_pkgs ${'$'}browser_pkgs ${'$'}media_pkgs
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
              apk add ${'$'}desktop_pkgs x11vnc xvfb ${'$'}audio_pkgs ${'$'}browser_pkgs ${'$'}media_pkgs
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
              dnf install -y ${'$'}desktop_pkgs x11vnc xorg-x11-server-Xvfb ${'$'}audio_pkgs ${'$'}browser_pkgs ${'$'}media_pkgs
            }
            if command -v apt-get >/dev/null 2>&1; then install_apt
            elif command -v pacman >/dev/null 2>&1; then install_pacman
            elif command -v apk >/dev/null 2>&1; then install_apk
            elif command -v dnf >/dev/null 2>&1; then install_dnf
            else echo "Unsupported package manager. Install x11vnc and Xvfb, then run ${desktopCommand}." >&2; exit 2
            fi
            mkdir -p "${'$'}HOME/.vnc"
            ${passwordCommand}
            cat > "${'$'}HOME/.vnc/xstartup" <<'EOF'
            #!/bin/sh
            unset SESSION_MANAGER
            unset DBUS_SESSION_BUS_ADDRESS
            ${desktopCommand}
            EOF
            chmod 700 "${'$'}HOME/.vnc/xstartup"
            cat > "${'$'}HOME/start-linuxdroid-desktop" <<'EOF'
            #!/bin/sh
            set -eu
            DISPLAY_NUM=:1
            VNC_PORT=5901
            AUTH_FILE="${'$'}HOME/.vnc/passwd"
            [ -f "${'$'}AUTH_FILE" ] || { echo "Set a VNC password first: x11vnc -storepasswd YOUR_PASSWORD ~/.vnc/passwd" >&2; exit 1; }
            pkill -f "Xvfb ${'$'}DISPLAY_NUM" >/dev/null 2>&1 || true
            pkill -f "x11vnc.*${'$'}DISPLAY_NUM" >/dev/null 2>&1 || true
            if command -v tightvncserver >/dev/null 2>&1; then
              tightvncserver -kill ${'$'}DISPLAY_NUM >/dev/null 2>&1 || true
              tightvncserver ${'$'}DISPLAY_NUM -localhost -geometry 1280x720 -depth 24
              exit 0
            fi
            Xvfb ${'$'}DISPLAY_NUM -screen 0 1280x720x24 -nolisten tcp >/tmp/linuxdroid-xvfb.log 2>&1 &
            sleep 1
            DISPLAY=${'$'}DISPLAY_NUM "${'$'}HOME/.vnc/xstartup" >/tmp/linuxdroid-desktop.log 2>&1 &
            DISPLAY=${'$'}DISPLAY_NUM x11vnc \
              -display ${'$'}DISPLAY_NUM \
              -rfbauth "${'$'}AUTH_FILE" \
              -forever \
              -shared \
              -localhost \
              -rfbport ${'$'}VNC_PORT \
              -noxdamage \
              -nowf \
              -noshm >/tmp/linuxdroid-x11vnc.log 2>&1 &
            echo "Desktop started with Xvfb + x11vnc on 127.0.0.1:${'$'}VNC_PORT."
            EOF
            chmod 700 "${'$'}HOME/start-linuxdroid-desktop"
            echo "Desktop setup complete. Start it with: ~/start-linuxdroid-desktop"
            echo "Then open LinuxDroid VNC using 127.0.0.1:5901."
        """.trimIndent() + "\n"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
