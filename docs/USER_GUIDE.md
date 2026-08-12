# LinuxDroid User Guide

## 1. Before the first installation

LinuxDroid needs an Android device with an ARM processor supported by the selected RootFS. Most modern devices are `arm64-v8a`; older 32-bit devices are commonly `armeabi-v7a`. The RootFS architecture must match the device. The app runs a Linux **userland**, not a complete virtual machine: it cannot boot a Linux kernel, gain Android root, run Docker, load kernel modules, or reliably use systemd. [1]

Open **Configure RootFS source** and paste the direct HTTPS URL of your project’s `catalog.json`. LinuxDroid refuses non-HTTPS catalogs and refuses archives with a mismatched SHA-256. Choose **Install a distribution**, select an available image, and wait for verification and extraction to finish. You may install several distributions, but LinuxDroid allows only one active session so two PRoot processes cannot compete for memory and storage.

## 2. Shared files and privacy

By default, the Linux RootFS is isolated. If you grant the optional Android **All files access** special permission and enable shared-storage binding, Android shared storage appears at `/sdcard` inside the running guest. This does not grant access to Android app-private folders belonging to other applications. [2]

Use the setting only if broad file access is genuinely required. On a Play-distributed build, this special permission may require a policy declaration and approval because it is restricted to direct core use cases. [3]

## 3. Start, stop, and terminal controls

Choose **Start** next to an installed distribution. LinuxDroid immediately creates a foreground session notification; keep it visible while the Linux session is running. Select **Terminal** to open the integrated terminal. The lower control row provides Ctrl, Alt, Shift, Tab, Escape, and arrow keys. Tap **Keyboard** to show the Android soft keyboard.

Always use **Session controls → Stop session** before removing a distribution, changing its RootFS manually, or starting another distribution. The foreground service is deliberately started only from a visible user action because Android 12 and later restrict foreground-service launches from the background. [4]

## 4. Desktop, VNC, and browser choices

Select **Setup** on the target distribution. Choose one desktop environment, Firefox/Chromium/none, and whether to install media and text utilities. LinuxDroid sends the resulting setup script to the active terminal. Keep the terminal open until the guest package manager completes.

After setup completes, run the following command inside the Linux terminal:

```sh
~/start-linuxdroid-desktop
```

This starts a localhost-only VNC server on display `:1`, normally `127.0.0.1:5901`. Open **Configure internal VNC** to set the port, password, view-only mode, and desktop command. Then select **VNC** next to the installed distribution. Keep the VNC host at `127.0.0.1` for local desktop sessions. Do not expose VNC to Wi-Fi, mobile data, port-forwarding, or the public Internet without an authenticated encrypted tunnel.

## 5. Audio through PulseAudio

When audio is enabled, LinuxDroid starts a host PulseAudio process restricted to `127.0.0.1:4713`, and the guest receives `PULSE_SERVER=tcp:127.0.0.1:4713`. Install a PulseAudio client library inside the RootFS if the application you use needs one. A basic verification command is:

```sh
printf '\a'
# or, after installing pulseaudio-utils:
pactl info
```

Audio output depends on the Android device’s OpenSL ES / AAudio support and vendor implementation. If sound fails, confirm that the Linux session was started after audio was enabled, reopen the session, and inspect the app-private `pulseaudio.log` through Android Studio device explorer. Do not change the loopback listener to `0.0.0.0`.

## 6. Signal 9 and unexpected session termination

A process exit reported as **signal 9** means the process was killed with `SIGKILL`. In a non-root Android environment, the application cannot intercept or cancel that signal. Android background limits and OEM memory-management policies can terminate a session when it loses foreground importance or memory is scarce. Android documents that background services are stopped after an app becomes idle; foreground services have a more visible lifecycle but are not an absolute guarantee against memory pressure. [5]

Use the following recovery checklist in order.

| Check | Action | Why it helps |
| --- | --- | --- |
| Ongoing notification | Start the distribution from the visible LinuxDroid screen and keep its session notification active. | The active session remains user-visible through a foreground service. |
| Battery optimization | Open Android settings for LinuxDroid and select **Unrestricted**, **Don’t optimize**, or the closest manufacturer-specific equivalent. | Prevents aggressive vendor battery policies from treating the session as disposable. |
| Recent-apps lock | On devices offering “Lock app”, “Keep open”, or “Pin”, apply it to LinuxDroid. | Some OEM task managers otherwise sweep the process. |
| Memory pressure | Stop other heavy apps, reduce VNC resolution, use Fluxbox or LXDE, and avoid compiling large packages in the guest. | PRoot adds process and memory overhead; the low-memory killer can still terminate processes. |
| Android developer options | Ensure **Background process limit** is set to **Standard limit** rather than a restrictive custom limit. | A restrictive limit can cause the system to remove processes sooner. |
| Logs | Record the time, Android version, device model, and the last terminal output; capture `adb logcat` if available. | Distinguishes an Android kill from a guest command failure. |

> Do not use “RAM cleaner”, battery-saver, or task-killer applications while running a desktop session. They commonly cause the very Signal 9 failure they claim to prevent.

If the device still kills LinuxDroid, restart the session and use a lighter desktop. Persist work inside the RootFS or `/sdcard`; a killed in-memory process cannot be resumed safely.

## 7. Common issues

| Symptom | Likely cause | Resolution |
| --- | --- | --- |
| `exec format error` | RootFS architecture does not match device ABI. | Install the matching `arm64-v8a` or `armeabi-v7a` archive. |
| `PRoot runtime ... not included` | The APK was built without running runtime preparation. | Rebuild through the supplied GitHub Actions workflow or run `scripts/prepare-runtime.sh` first. |
| RootFS checksum mismatch | The release archive changed or catalog hash is incorrect. | Re-upload as a new version, recalculate SHA-256, and update the catalog. |
| VNC connection refused | Desktop server is not running or wrong port. | Run `~/start-linuxdroid-desktop`; verify `127.0.0.1:5901`. |
| VNC authentication failed | Saved password differs from `~/.vnc/passwd`. | Set a new password with `vncpasswd`, then update internal VNC settings. |
| Desktop starts then closes | Missing D-Bus or incompatible desktop package. | Re-run setup; inspect `~/.vnc/*.log`; try Fluxbox. |
| No sound | PulseAudio host runtime missing or guest client absent. | Rebuild runtime, restart session, install guest PulseAudio client utilities, and read logs. |
| Cannot see `/sdcard` | Optional broad storage permission not granted or binding disabled. | Enable shared-storage binding and grant the system’s All files access setting. |

## References

[1]: https://proot-me.github.io/ "PRoot documentation"
[2]: https://developer.android.com/training/data-storage/manage-all-files "Android all-files access documentation"
[3]: https://support.google.com/googleplay/android-developer/answer/10467955 "Google Play policy on MANAGE_EXTERNAL_STORAGE"
[4]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start "Android foreground-service restrictions"
[5]: https://developer.android.com/about/versions/oreo/background "Android background execution limits"
