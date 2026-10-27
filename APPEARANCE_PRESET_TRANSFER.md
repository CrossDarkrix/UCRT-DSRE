# Appearance Preset Transfer

## File

- Default name: UCRT-Appearance-Preset.json
- Schema: ucrt-appearance-preset
- Version: 1

## Exported state

- Theme mode
- Dynamic color
- Theme color
- Text color
- Full normalized appearance configuration, including preset, common settings, component overrides, animation, power-save behavior, scrolling blur behavior, and low-performance fallback

## Import behavior

- Schema and version are required.
- Theme mode must be 0, 1, or 2.
- Theme and appearance objects are required.
- Appearance values pass through the existing normalization and safe enum fallback.
- Import updates theme and appearance keys in one DataStore edit transaction.
- Failure leaves current settings unchanged.
