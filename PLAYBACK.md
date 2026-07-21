# PTV Playback Architecture

## Video
- **Component**: `VideoPlayerActivity`
- **Engine**: ExoPlayer 3 / Media3
- **Negotiation**: `PlaybackNegotiator` checks server capabilities vs hardware decoders.
- **Reporting**: Reports progress every 15s to Jellyfin.

## Audio
- **Component**: `AudioPlayerService`
- **Engine**: MediaSessionService
- **Capabilities**: Background playback, notification controls, Fire TV media cards.

## Reading
- **Component**: `ReaderActivity`
- **Engine**: ViewPager2
- **Formats**: CBZ, CBR, PDF (rendered as images by Jellyfin).
