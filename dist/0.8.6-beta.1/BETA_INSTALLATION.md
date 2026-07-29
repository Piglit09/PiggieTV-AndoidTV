# PiggieTV v0.8.6-beta.1 Installation Guide

## Prerequisites
- Physical Fire TV Stick 4K Max (recommended) or any Android TV device (API 24+).
- Jellyfin Server URL and login credentials.

## Installation Steps
1. Enable **Developer Options** and **ADB Debugging** on your Fire TV.
2. Note your Fire TV's IP address.
3. On your computer, connect via ADB:
   `adb connect <FIRE_TV_IP>`
4. Install the beta package:
   `adb install PiggieTV-AndroidTV-0.8.6-beta.1.apk`

## Upgrading from v0.8.5
If you have an existing 0.8.5 build, you can attempt an upgrade:
`adb install -r PiggieTV-AndroidTV-0.8.6-beta.1.apk`
*Note: If signing certificates differ, a clean install may be required.*

## Support
- Generate a diagnostic report from **Settings > Diagnostics** and copy it to your clipboard if you encounter issues.
- Report bugs using the provided `BETA_ISSUE_TEMPLATE.md`.
