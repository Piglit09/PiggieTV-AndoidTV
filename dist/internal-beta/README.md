# PiggieTV Fire TV Internal Beta Package

## Documentation
- [Authoritative Acceptance Record](../../.artifacts/a9085f41-9582-431e-a85f-04ac67a9a37a/walkthrough.artifact.md)
- [Final Resource Configuration](../../.artifacts/a9085f41-9582-431e-a85f-04ac67a9a37a/implementation_plan.artifact.md)

## Build Metadata
- **App ID:** com.piggie.tv.debug
- **Version:** 0.8.6-beta.1-debug (v4)
- **Target:** Amazon Fire TV Stick 4K Max
- **APK Path:** `app/build/outputs/apk/debug/app-debug.apk`
- **SHA-256:** `ED0D1A371DBC2BF1D4AAA76255FC326F00CA8253F26C2FB080EA92902CD9D7CD`

## Installation Instructions (Fire TV / ADB)
1. Enable **ADB Debugging** on the target device.
2. Connect via ADB: `adb connect <device-ip>`
3. Install the APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Tester Checklist
1. Verify background fills screen (no black bars).
2. Verify all movie/show titles are readable in grids.
3. Verify reader loads CBZ files correctly and resumes page.
4. Verify smooth D-pad navigation across 30+ items.
