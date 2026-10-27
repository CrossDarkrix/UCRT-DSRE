# Phase 5B Global Glass Integration

Phase 5B integrates Phase 5A with the whole Compose application through a nested Material 3 design system rather than duplicating screen-specific colors.

## Global coverage

The resolved appearance now supplies the active Material 3 ColorScheme and Shapes for all descendants. This covers library, lists, search, category tiles, navigation, mini player, full player, playlists, selection UI, dialogs, bottom sheets, settings, DSRE, metadata editor, CD, burn, equalizer, Spotify, streaming, external media, Cast, and audio/USB settings.

## Role mapping

- Background transparency: app and Scaffold backgrounds
- Card/List override: surfaceVariant and surfaceContainer
- Controls override: primary/secondary/tertiary containers
- Overlay override: surfaceContainerHighest and popup scrim
- Navigation override: surface tint and navigation surfaces
- Player override: surfaceContainerHigh and Mini Player token
- Visualization override: surfaceContainerLow and visualization token

## Global behavior

- Material components keep matching on-colors in light, dark, dynamic-color, theme-color, and custom-text-color modes.
- Material shapes inherit the selected UCRT corner level.
- Dialog opacity remains clamped for readability.
- Power saver behavior is resolved before Material colors and UCRT tokens are supplied.
- A solid base theme background sits behind translucent surfaces.
- Standard returns opaque Material behavior.

## Native build compatibility

The CMake policy range is declared as 3.22.1...4.4. ZIP timestamps are fixed to January 1, 2020 to avoid Ninja future-timestamp regeneration loops on Termux.
