# PTV Reading Platform

## Data Structure
Books in PTV are treated as `MediaItem` with type `Book`.
- Uses `PageCount` for navigation.
- Uses `getPageImageUrl` for incremental page loading.

## Reader UI
- **Activity**: `ReaderActivity`
- **Navigation**: DPAD LEFT/RIGHT turns pages.
- **Performance**: Loads only current and adjacent pages to minimize memory on low-end TV sticks.
- **Resume**: Future updates will sync page index with Jellyfin `UserData.PlaybackPositionTicks` (translated to page).
