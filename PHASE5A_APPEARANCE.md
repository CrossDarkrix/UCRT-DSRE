# Phase 5A Appearance Foundation

This build adds the UCRT appearance foundation without changing audio, USB DAC, DSRE, metadata, or library behavior.

## Included

- Standard, Translucent, Lightweight Glass, Liquid Glass, and Custom presets
- Atomic JSON persistence through DataStore
- Common transparency, blur, border, gloss, corner, and depth settings
- Per-component overrides for cards/lists, controls, overlays, navigation, player, and visualization panels
- Animation and power-save behavior settings
- Safe popup opacity limit and invalid-value clamping
- Material 3 extension through CompositionLocal visual tokens
- Common UCRT Surface, Card, ListItem, Button, Slider, Dialog, and VisualizationPanel components
- Realtime appearance preview
- Initial application to Settings cards, Settings sections, root background, and Mini Player
- Standard preset fallback and one-action reset

## Notes

The blur value is resolved and exposed as a design token. Phase 5A keeps actual rendering conservative so unsupported or low-performance devices do not fail. Later screen migrations can consume the same token while retaining the Lightweight Glass fallback.
