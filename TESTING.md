# PTV Testing Strategy

## Unit Tests
- **Package**: `app/src/test/java/com/piggie/tv/`
- **Framework**: JUnit 4 + Robolectric.
- **Coverage**:
    - Metadata parsing.
    - Session logic.
    - Focus graph navigation.
    - URL normalization.

## Hardware Validation
- Primary Target: **Fire TV Stick 4K Max**.
- Tool: `PerformanceMonitor.kt` (Developer Overlay).
- acceptance: Locked 60fps Home scrolling, zero OOMs during 2-hour stress test.
