# Ubuntu Terminal for Android

A native Android app that provides a real Ubuntu Linux terminal environment using PRoot.
No root access required — PRoot uses ptrace-based syscall interception to run an actual
Ubuntu root filesystem on your phone.

## Features

- **Real Ubuntu environment** — runs actual Linux binaries (bash, apt, python, gcc, etc.)
- **No root required** — uses PRoot for user-space filesystem isolation
- **Full terminal emulator** — ANSI/VT100 escape sequences, 256 colors, UTF-8
- **Beautiful dark UI** — Ubuntu-inspired color scheme with Material Design
- **Extra keys bar** — ESC, TAB, CTRL, ALT, arrows, function keys
- **Multiple architectures** — arm64-v8a, armeabi-v7a, x86_64, x86

## How it works

1. The app bundles a PRoot binary (downloaded from Termux packages during CI build)
2. On first launch, it downloads an Ubuntu 22.04 minimal rootfs from the official Ubuntu CDN
3. PRoot creates a virtual filesystem namespace mapping the Ubuntu rootfs as `/`
4. Android's `/dev`, `/proc`, `/sys` are bind-mounted into the chroot
5. Bash runs inside this environment — it's a real Ubuntu, not a simulation

## Building

GitHub Actions automatically builds the APK on every push. The workflow:
1. Downloads and extracts PRoot binaries from Termux packages (for all architectures)
2. Compiles the Android app with Gradle
3. Uploads the debug and release APKs as artifacts

Download the APK from the Actions tab → latest run → Artifacts.

## Usage

1. Install the APK
2. On first launch, wait for Ubuntu rootfs to download (~30MB) and extract
3. You'll get a real bash shell running in Ubuntu 22.04
4. Install packages with `apt update && apt install <package>`

## Technical details

- **Terminal emulator**: Custom implementation handling ANSI X3.64, SGR colors,
  cursor movement, scroll regions, alternate screen, UTF-8
- **PRoot**: Pre-built binaries from Termux package repository
- **Rootfs**: Ubuntu Base 22.04 LTS minimal from cdimage.ubuntu.com
- **Min Android version**: 7.0 (API 24)

## License

MIT
