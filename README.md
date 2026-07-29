# PiggieTV Android TV

Native Android TV client for Jellyfin. High performance, zero WebView, built for the living room.

## Current Status (v0.8.6 Beta)
PiggieTV has transitioned to its first controlled beta. Movies, Shows, and Music are unified under a high-performance native Shell.

## Key Features
- **Cinematic Experience**: 4K dynamic backdrops and crossfades.
- **Background Music**: Full Media3 integration for audio.
- **Privacy First**: Encrypted local storage and user-triggered diagnostics.

Reading is not supported by the Android TV build and is not exposed through its navigation, search, recommendations, or capabilities.

## Format Support
| Media Type | Native Engine | Server Rendering Required |
| :--- | :--- | :--- |
| **Video** | ExoPlayer (H.264/HEVC) | For unsupported codecs |
| **Audio** | ExoPlayer (MP3/AAC/FLAC) | No (Direct stream) |

## Development
See [ARCHITECTURE.md](ARCHITECTURE.md) for data flow and design system details.
