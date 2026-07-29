# PiggieTV v0.8.6-beta.1 Release Notes

## Overview
This release transitions PiggieTV from a development prototype to a controlled beta platform. The focus is on architecture stability, privacy-safe diagnostics, and unified platform hardening.

## Key Changes
- **Modular Architecture**: Complete refactor for better maintainability and performance.
- **Beta Build Variant**: Improved build types for safe user testing.
- **Privacy Hardening**:
    - Scoped reading progress by server/user.
    - Redacted logging for sensitive tokens and secrets.
    - Local-only crash reporting (non-uploading).
- **Diagnostics**: New "Copy Report" feature in Settings for easy issue reporting.
- **Format Support Update**: Clearly documented native vs. server-rendered capabilities.

## Format Support Status
| Format | Native in PTV | Jellyfin Server Requirement |
| :--- | :--- | :--- |
| **CBZ** | Partial (Images) | Jellyfin extracts images for native PTV display. |
| **CBR** | No | Jellyfin extracts images for native PTV display. |
| **PDF** | No | Jellyfin renders pages as images for PTV display. |
| **EPUB / MOBI** | No | Requires Jellyfin Book plugin to render pages as images. |

## Upgrade Instructions
- **v0.8.5 code 3** users can install this APK directly to preserve sessions and reading progress.
- Clean installs are recommended for new beta testers.

## Known Issues
- Page count may be inaccurate for some text-based formats.
- Global search for Manga may return duplicate author entries depending on server metadata.
- Performance overlay is hidden by default in Beta builds (available in Debug).
