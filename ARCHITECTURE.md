# PiggieTV Android TV Architecture

## Overview
PiggieTV uses a **Modular Clean Architecture** designed for high-performance media browsing and playback on Android TV (API 24+).

## Directory Structure
- `auth/`: Native authentication (Password, Quick Connect).
- `core/`: Application host (`PtvHostActivity`) and global policies.
- `data/`:
    - `api/`: Jellyfin REST API integration via OkHttp.
    - `models/`: Unified domain models (`MediaItem`, `NativeSession`).
    - `repositories/`: Business logic layer (Recommendation, Playback, Users).
    - `playback/`: Media3 / ExoPlayer stream negotiation.
    - `session/`: Encrypted SharedPrefs for session persistence.
- `navigation/`: Centralized TV route management and Shell UI.
- `theme/`: Global Design System (PTVColors, PTVTypography, PTVShapes).
- `ui/`: Feature-specific fragments organized by media type.
- `services/`: Background services (MediaSessionService).
- `util/`: Hardware and dimension utilities.

## Data Flow
1. **UI** requests data from a **Repository**.
2. **Repository** fetches from **Api** (Jellyfin) or Local Cache.
3. **Api** returns JSON which is parsed into **Domain Models** (`MediaItem`).
4. **UI** renders models using the **Global Design System**.

## Playback
- **Video**: Full-screen ExoPlayer via `VideoPlayerActivity`. Supports HLS and Direct Play.
- **Audio**: Background-capable `AudioPlayerService` using Media3 MediaSession.
- **Reading**: Native `ReaderActivity` using ViewPager2 for image-based books/comics.
