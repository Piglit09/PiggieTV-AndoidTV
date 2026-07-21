# PTV Theming & Design System

## Global Themes
PiggieTV supports multiple native themes defined in `PTVTheme.kt`.
- **Piggie Purple**: Standard PTV branding.
- **OLED Black**: Infinite contrast for high-end displays.
- **Deep Blue**: Cinematic classic feel.

## PTVDesignSystem.kt
All UI components must use `PTVColors`, `PTVTypography`, and `PTVShapes`.
- **Never** hardcode hex colors in layouts.
- **Never** hardcode pixel sizes for text.
- Use `setTextSizeRes(R.dimen.tv_text_size_...)` for consistency.

## Focus System
TV focus is handled via `PTVShapes.applyFocusEffect(view, focused)`.
- Default: `1.05x` scale + subtle elevation.
- Border: Cyan (`#00F2FF`) focus ring.
