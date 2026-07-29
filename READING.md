# PTV Reading Platform

> **Android TV status:** Unsupported and disabled. The Android TV build does not
> advertise reader capabilities or expose books through navigation, search, or
> recommendations. The isolated reader source remains for other PTV platforms
> and possible future work.

## Data Structure
Books in PTV are treated as `MediaItem` with type `Book`.
- Uses `pageCount` for navigation.
- Uses `getPageImageUrl` for incremental page loading.
- Uses `isFavorite` and `isPlayed` for library state.

## Reader UI
- **Activity**: `ReaderActivity`
- **Navigation**:
    - DPAD LEFT/RIGHT turns pages.
    - DPAD UP toggles Reading Direction (LTR/RTL).
    - DPAD CENTER/MENU toggles Overlay.
- **Features**:
    - **RTL Support**: Built-in layout direction flipping for Manga.
    - **Progress Persistence**: Locally cached in `ReadingSettings` and reported to Jellyfin as position ticks.
    - **Incremental Loading**: ViewPager2 + Coil ensures low memory overhead by only loading visible and adjacent pages.

## Supported Formats
- **Image-based**: CBZ, CBR, PDF (rendered by Jellyfin).
- **Text-based**: EPUB/MOBI (if rendered as images by Jellyfin server).
