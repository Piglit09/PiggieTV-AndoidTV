# PTV Navigation System

## NativeRoute.kt
Navigation is governed by an enum `NativeRoute`.
- `HOME`, `MOVIES`, `SHOWS`, `MUSIC`, `READING`, `SEARCH`, `SETTINGS`, `PROFILE`.

## PtvHostActivity.kt
A single-activity architecture where `PtvHostActivity` manages fragments.
- Back behavior is mapped via `NativeRouteNavigator.backTarget()`.
- Most routes return to **HOME**.

## Remote Control Mapping
- **Back**: Returns to Home or previous screen logic.
- **D-Pad**: Standard Android Focus navigation.
- **Center**: Select / Play.
